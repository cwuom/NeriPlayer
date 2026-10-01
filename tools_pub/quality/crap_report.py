"""Report method CRAP using JaCoCo complexity coverage as a path-coverage proxy."""

import argparse
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


REPORT_THRESHOLD = 8
FAIL_THRESHOLD = 9


def source_index(root):
    index = {}
    for path in sorted(root.rglob("*")):
        if path.suffix not in {".kt", ".java"}:
            continue
        package = re.search(r"(?m)^\s*package\s+([\w.]+)", path.read_text(encoding="utf-8"))
        key = ((package.group(1).replace(".", "/") if package else ""), path.name)
        if key in index:
            raise ValueError(f"Ambiguous source mapping: {index[key]} and {path}")
        index[key] = path.relative_to(root).as_posix()
    return index


def scope_definition(root, config):
    definition = json.loads(config.read_text(encoding="utf-8"))
    patterns = definition["source_patterns"]
    if not isinstance(patterns, list) or not patterns:
        raise ValueError("CRAP scope must contain source_patterns")
    sources = set()
    for pattern in patterns:
        if not isinstance(pattern, str) or ".." in Path(pattern).parts:
            raise ValueError(f"Invalid scope pattern: {pattern}")
        matched = {path.relative_to(root).as_posix() for path in root.glob(pattern)
                   if path.is_file() and path.suffix in {".kt", ".java"}}
        if not matched:
            raise ValueError(f"Scope pattern matches no sources: {pattern}")
        sources.update(matched)
    method_scopes = set()
    for rule in definition.get("method_scopes", []):
        if not isinstance(rule, dict) or set(rule) != {"source", "class", "methods"}:
            raise ValueError(f"Invalid method scope: {rule}")
        source, owner, methods = rule["source"], rule["class"], rule["methods"]
        if (not isinstance(source, str) or ".." in Path(source).parts or
                not isinstance(owner, str) or not owner or
                not isinstance(methods, list) or not methods or
                any(not isinstance(name, str) or not name for name in methods)):
            raise ValueError(f"Invalid method scope: {rule}")
        if not (root / source).is_file() or Path(source).suffix not in {".kt", ".java"}:
            raise ValueError(f"Method scope source does not exist: {source}")
        method_scopes.update((source, owner, name) for name in methods)
    return sources, method_scopes


def read_methods(xml, sources, scope):
    scoped_sources, method_scopes = scope
    report = ET.parse(xml).getroot()
    if report.tag != "report":
        raise ValueError("Expected a JaCoCo report")
    rows = []
    seen_sources = set()
    seen_method_scopes = set()
    identities = set()
    for package in report.findall("package"):
        for owner in package.findall("class"):
            source = sources.get((package.get("name", ""), owner.get("sourcefilename")))
            if source is None:
                continue
            # 纯接口会出现在类报告中, 但没有可执行方法和复杂度计数
            seen_sources.add(source)
            for method in owner.findall("method"):
                counter = method.find("counter[@type='COMPLEXITY']")
                if counter is None:
                    raise ValueError(f"Missing complexity counter: {source} {method.get('name')}")
                missed, covered = int(counter.attrib["missed"]), int(counter.attrib["covered"])
                if missed < 0 or covered < 0 or missed + covered == 0:
                    raise ValueError(f"Invalid complexity counter: {source} {method.get('name')}")
                complexity = missed + covered
                coverage = covered / complexity
                score = complexity * complexity * (1 - coverage) ** 3 + complexity
                identity = (owner.attrib["name"], method.attrib["name"], method.attrib["desc"])
                if identity in identities:
                    raise ValueError(f"Duplicate method in coverage report: {identity}")
                identities.add(identity)
                owner_name = owner.attrib["name"].replace("/", ".")
                method_scope = (source, owner_name, method.attrib["name"])
                if method_scope in method_scopes:
                    seen_method_scopes.add(method_scope)
                rows.append({
                    "source": source,
                    "line": int(method.get("line", "0")),
                    "class": owner_name,
                    "method": method.attrib["name"],
                    "descriptor": method.attrib["desc"],
                    "complexity": complexity,
                    "covered_complexity": covered,
                    "coverage": coverage,
                    "crap": score,
                    "in_scope": source in scoped_sources or method_scope in method_scopes,
                })
    missing = scoped_sources - seen_sources
    if missing:
        raise ValueError("Scoped sources have no measured methods: " + ", ".join(sorted(missing)))
    missing_methods = method_scopes - seen_method_scopes
    if missing_methods:
        raise ValueError("Method scopes have no measured methods: " + ", ".join(map(str, sorted(missing_methods))))
    return sorted(rows, key=lambda row: (-row["crap"], row["class"], row["method"], row["descriptor"]))


def markdown(title, rows):
    lines = [f"# {title}", "", "CRAP = C² × (1 − cov)³ + C; "
             "cov = JaCoCo covered complexity / total complexity (path-coverage proxy).", "",
             "| Source | Method (JVM descriptor) | C | Coverage | CRAP | Gate scope |",
             "| --- | --- | ---: | ---: | ---: | --- |"]
    for row in rows:
        lines.append(
            f"| {row['source']}:{row['line']} | "
            f"`{row['class']}#{row['method']}{row['descriptor']}` | "
            f"{row['complexity']} | {row['coverage']:.2%} | {row['crap']:.6f} | "
            f"{'yes' if row['in_scope'] else 'no'} |"
        )
    return "\n".join(lines) + "\n"


def run(args):
    sources = source_index(args.source_root)
    scope = scope_definition(args.source_root, args.scope)
    rows = read_methods(args.xml, sources, scope)
    scoped = [row for row in rows if row["in_scope"]]
    listed = [row for row in rows if row["crap"] > REPORT_THRESHOLD]
    failures = [row for row in scoped if row["crap"] > FAIL_THRESHOLD]
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "methods.json").write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
    (args.output / "above-8.md").write_text(markdown("CRAP > 8", listed), encoding="utf-8")
    (args.output / "scope.md").write_text(markdown("All scoped methods", scoped), encoding="utf-8")
    print(f"CRAP: {len(rows)} methods, {len(listed)} > 8; "
          f"{len(scoped)} scoped methods, {len(failures)} > 9")
    print(f"Reports: {args.output}")
    for row in failures:
        print(f"FAIL {row['source']}:{row['line']} {row['class']}#"
              f"{row['method']}{row['descriptor']} CRAP={row['crap']:.6f}")
    return 1 if failures and not args.report_only else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--xml", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--scope", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report-only", action="store_true")
    try:
        return run(parser.parse_args())
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        print(f"CRAP report error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
