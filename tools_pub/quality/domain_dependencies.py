"""Verify direct JVM dependencies of computational domains using JDK tools."""

import argparse
from fnmatch import fnmatchcase
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile


CLASS_OWNER = re.compile(r"^\s*this_class:.*//\s+(\S+)$")
MEMBER = re.compile(r"^\s*#\d+\s+=\s+(Fieldref|Methodref|InterfaceMethodref)\s+.*//\s+(\S+)$")
EDGE = re.compile(r"^\s+(\S+)\s+->\s+(\S+)\s+.+$")


def load_domains(path):
    domains = json.loads(path.read_text())
    if not isinstance(domains, list) or not domains:
        raise ValueError("At least one domain is required")
    names = set()
    for domain in domains:
        required_fields = {
            "name", "package", "allowed_classes", "bridges", "descriptor_only"
        }
        if (not isinstance(domain, dict) or not required_fields.issubset(domain)
                or set(domain) - required_fields - {"excluded_classes"}):
            raise ValueError("Invalid domain fields")
        name, package = domain["name"], domain["package"]
        if not isinstance(name, str) or not name or name in names:
            raise ValueError("Domain names must be nonempty and unique")
        names.add(name)
        if not isinstance(package, str) or not re.fullmatch(r"(?:[a-zA-Z_]\w*\.)+", package):
            raise ValueError(f"{name}: package must be a dotted prefix ending with a dot")
        excluded = domain.get("excluded_classes", [])
        if not isinstance(excluded, list) or not all(
            isinstance(owner, str) and owner.startswith(package)
            and re.fullmatch(r"(?:[a-zA-Z_$][\w$]*\.)+[a-zA-Z_$][\w$]*", owner)
            for owner in excluded
        ) or len(set(excluded)) != len(excluded):
            raise ValueError(f"{name}: invalid excluded classes")
        allowed, bridges = domain["allowed_classes"], domain["bridges"]
        if not isinstance(allowed, list) or not all(isinstance(x, str) and x for x in allowed):
            raise ValueError(f"{name}: invalid allowed classes")
        if not isinstance(domain["descriptor_only"], list) or not all(
            isinstance(x, str) and x for x in domain["descriptor_only"]
        ):
            raise ValueError(f"{name}: invalid descriptor-only classes")
        if not isinstance(bridges, dict) or not all(
            isinstance(owner, str) and isinstance(members, list)
            and all(isinstance(member, str) and member for member in members)
            for owner, members in bridges.items()
        ):
            raise ValueError(f"{name}: invalid bridge members")
    return domains


def compiled_classes(inputs):
    classes = set()
    for path in inputs:
        if path.is_dir():
            entries = [file.relative_to(path).as_posix() for file in path.rglob("*.class")]
        elif path.is_file() and zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                entries = [name for name in archive.namelist() if name.endswith(".class")]
        else:
            raise ValueError(f"Missing or invalid class input: {path}")
        for entry in entries:
            if entry.startswith("META-INF/") or entry == "module-info.class":
                continue
            name = entry[:-6].replace("/", ".")
            if name in classes:
                raise ValueError(f"Duplicate compiled class: {name}")
            classes.add(name)
    if not classes:
        raise ValueError("No compiled classes found")
    return classes


def select_domains(classes, domains):
    selected = {}
    for domain in domains:
        excluded = domain.get("excluded_classes", [])
        matches = {
            name for name in classes
            if name.startswith(domain["package"])
            and not any(name == owner or name.startswith(owner + "$") for owner in excluded)
        }
        if not matches:
            raise ValueError(f"{domain['name']}: no compiled classes in protected package")
        for name in matches:
            if name in selected:
                raise ValueError(f"Overlapping domains for {name}")
            selected[name] = domain
    return selected


def run_tool(command):
    result = subprocess.run(command, capture_output=True, text=True, timeout=120, check=False)
    if result.returncode:
        raise ValueError(f"{Path(command[0]).name} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout


def check_class_dependencies(output, selected):
    seen, edges, errors = set(), set(), []
    for line in output.splitlines():
        match = EDGE.match(line)
        if not match or match[1] not in selected:
            continue
        owner, dependency = match.groups()
        seen.add(owner)
        edges.add((owner, dependency))
        domain = selected[owner]
        if dependency in selected and selected[dependency] is domain:
            continue
        if dependency in domain["bridges"] or dependency in domain["descriptor_only"]:
            continue
        if not any(fnmatchcase(dependency, pattern) for pattern in domain["allowed_classes"]):
            errors.append(f"{domain['name']}: {owner} -> forbidden class {dependency}")
    missing = set(selected) - seen
    if missing:
        raise ValueError(f"jdeps omitted protected classes: {', '.join(sorted(missing))}")
    return edges, errors


def check_bridge_members(output, selected):
    owner, seen, checked, errors = None, set(), set(), []
    for line in output.splitlines():
        declaration = CLASS_OWNER.match(line)
        if declaration:
            owner = declaration[1].replace("/", ".")
            seen.add(owner)
        match = MEMBER.match(line)
        if not match and re.match(r"^\s*#\d+\s+=\s+(?:Fieldref|Methodref|InterfaceMethodref)\b", line):
            raise ValueError(f"Unrecognized javap member reference: {line.strip()}")
        if not match or owner not in selected:
            continue
        kind, reference = match.groups()
        target_and_name, descriptor = reference.split(":", 1)
        target, member = target_and_name.rsplit(".", 1)
        target = target.replace("/", ".")
        domain = selected[owner]
        if target not in domain["bridges"] and target not in domain["descriptor_only"]:
            continue
        signature = f"{kind} {member.strip(chr(34))}:{descriptor}"
        checked.add((owner, target, signature))
        if signature not in domain["bridges"].get(target, []):
            errors.append(f"{domain['name']}: {owner} -> forbidden bridge member {target}#{signature}")
    missing = set(selected) - seen
    if missing:
        raise ValueError(f"javap omitted protected classes: {', '.join(sorted(missing))}")
    return checked, errors


def check_descriptor_types(output, selected, edges, members):
    errors, owner = [], None
    descriptors = {}
    for caller, target, signature in members:
        if signature in selected[caller]["bridges"].get(target, []):
            descriptors.setdefault(caller, set()).add(signature.split(":", 1)[1])
    for line in output.splitlines():
        declaration = CLASS_OWNER.match(line)
        if declaration:
            owner = declaration[1].replace("/", ".")
        if owner not in selected:
            continue
        for target in selected[owner]["descriptor_only"]:
            if (owner, target) not in edges:
                continue
            internal = target.replace(".", "/")
            constant = re.match(r"^\s*#\d+\s+=\s+Utf8\s+(.+)$", line)
            if constant and internal in constant[1]:
                # 只有获准桥接的完整描述符能提到该类型，类常量、字段和注解不能借此放行
                if constant[1] not in descriptors.get(owner, set()):
                    errors.append(f"{owner}: descriptor-only type {target} used outside an allowed bridge signature")
            if re.match(r"^\s*(?:descriptor|Signature):", line) and internal in line:
                errors.append(f"{owner}: descriptor-only type {target} declared on the computational class")
    return errors


def verify(inputs, domains, jdeps="jdeps", javap="javap"):
    selected = select_domains(compiled_classes(inputs), domains)
    include = "(?:" + "|".join(re.escape(domain["package"]) for domain in domains) + ").*"
    output = run_tool([jdeps, "-J-Duser.language=en", "-J-Duser.country=US",
                       "-verbose:class", "-filter:none", "--multi-release", "base",
                       "-include", include, *map(str, inputs)])
    edges, errors = check_class_dependencies(output, selected)
    names = sorted(selected)
    members = set()
    for start in range(0, len(names), 100):
        batch = names[start:start + 100]
        output = run_tool([javap, "-J-Duser.language=en", "-J-Duser.country=US", "-verbose", "-private", "-classpath",
                           os.pathsep.join(map(str, inputs)), *batch])
        checked, failures = check_bridge_members(output, {name: selected[name] for name in batch})
        errors.extend(check_descriptor_types(output, selected, edges, checked))
        members.update(checked)
        errors.extend(failures)
    return {
        "domains": {domain["name"]: sum(value is domain for value in selected.values()) for domain in domains},
        "class_dependencies": len(edges),
        "bridge_references": len(members),
        "errors": sorted(set(errors))
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--input", type=Path, action="append", required=True)
    parser.add_argument("--jdeps", default="jdeps")
    parser.add_argument("--javap", default="javap")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = verify(args.input, load_domains(args.config), args.jdeps, args.javap)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f"Domain dependency inputs invalid: {error}", file=sys.stderr)
        return 2
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    for error in report["errors"]:
        print(error, file=sys.stderr)
    print(f"Domain dependencies: {report['domains']}, {len(report['errors'])} violations")
    return int(bool(report["errors"]))


if __name__ == "__main__":
    sys.exit(main())
