"""Check owned Gradle libraries without requiring the Android SDK."""

import argparse
from collections import Counter
from pathlib import Path
import re
import sys


PROJECT_REFERENCE = re.compile(r'project\("(:[\w:-]+)"\)')
MODULE_INCLUDE = re.compile(r'(?:include|includeOwnedLibrary)\("(:[\w:-]+)"\)')
OWNED_INCLUDE = re.compile(r'includeOwnedLibrary\("(:[\w:-]+)"\)')
PACKAGE = re.compile(r'^[ \t]*package\s+([\w.]+)', re.MULTILINE)
MAX_DIRECTORY_SOURCES = 16
APP_FAMILIES = (
    "core/player/download", "core/player/service", "data/settings",
    "listentogether/session", "ui/screen/tab/settings/component",
    "core/player/queue", "data/sync/merge",
)
LIBRARY_OWNED_FAMILIES = ("core/api", "core/lyrics", "core/player/queue", "data/sync/merge")
MODULE_LAYERS = {"core": 0, "api": 1, "data": 2}
MODEL_MODULE = ":data:model"
# 已写入 Android 保存状态的 Parcelable 全名需要保持稳定
LEGACY_MODEL_TYPES = {
    f"moe.ouom.neriplayer.ui.viewmodel.tab.{name}"
    for name in ("PlaylistSummary", "AlbumSummary", "BiliPlaylist", "BiliPlaylistKind", "YouTubeMusicPlaylist")
}
LEGACY_MODEL_TYPES.add("moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem")
LEGACY_MODEL_TYPES.add("moe.ouom.neriplayer.data.sync.model.SyncCausalToken")
PACKAGE_OWNERS = {
    "moe.ouom.neriplayer.data.model": MODEL_MODULE,
    "moe.ouom.neriplayer.core.network": ":core:network",
    "moe.ouom.neriplayer.core.player.queue": ":core:playback-queue",
}
FORBIDDEN_IMPORT = re.compile(
    r'^import moe\.ouom\.neriplayer\.(?:'
    r'core\.di\.|core\.player\.PlayerManager\b|ui\.|activity\.|'
    r'(?:R|BuildConfig|NeriPlayerApplication)\b)', re.MULTILINE
)


def owned_module(name):
    return name.startswith(tuple(f":{layer}:" for layer in MODULE_LAYERS))


def source_files(directory):
    return [file for file in directory.rglob("*") if file.suffix in {".kt", ".java"}]


def verify_packages(root, source_root, errors):
    for source in source_files(source_root):
        package = PACKAGE.search(source.read_text())
        expected = ".".join(source.relative_to(source_root).parent.parts)
        actual = package[1] if package else ""
        if actual != expected:
            errors.append(f"{source.relative_to(root)}: package directory mismatch: {actual} != {expected}")


def verify_directory_capacity(root, directory, errors):
    counts = Counter(source.parent for source in source_files(directory))
    for parent, count in sorted(counts.items()):
        if count > MAX_DIRECTORY_SOURCES:
            errors.append(f"{parent.relative_to(root)}: {count} source files, maximum {MAX_DIRECTORY_SOURCES}")


def verify(root):
    settings = (root / "settings.gradle.kts").read_text()
    declared = set(MODULE_INCLUDE.findall(settings))
    modules = {name for name in declared if owned_module(name)}
    if not modules:
        return ["No owned library modules declared"]
    errors = []
    registered_locations = set(OWNED_INCLUDE.findall(settings))
    for module in sorted(modules - registered_locations):
        errors.append(f"{module}: must register with includeOwnedLibrary")
    for build_file in (root / "modules").glob("*/*/build.gradle.kts"):
        module = ":" + ":".join(build_file.parent.relative_to(root / "modules").parts)
        if module not in modules:
            errors.append(f"{module}: unregistered library in modules directory")
    app_script = (root / "app/build.gradle.kts").read_text()
    coverage_registry = re.search(r'val ownedLibraryPaths = listOf\((.*?)\)', app_script, re.DOTALL)
    registered = set(re.findall(r'"(:[\w:-]+)"', coverage_registry[1])) if coverage_registry else set()
    if registered != modules:
        errors.append(f"App coverage registry differs from owned modules: {sorted(registered ^ modules)}")
    graph = {}
    for name in sorted(modules):
        directory = root / "modules" / name.lstrip(":").replace(":", "/")
        build_file = directory / "build.gradle.kts"
        if not build_file.is_file():
            errors.append(f"{name}: missing build.gradle.kts")
            continue
        script = build_file.read_text()
        if 'id("build-logic.android.feature-library")' not in script:
            errors.append(f"{name}: must use the library verification convention")
        dependencies = set(PROJECT_REFERENCE.findall(script))
        graph[name] = dependencies & modules
        for dependency in sorted(dependencies):
            if name == MODEL_MODULE:
                errors.append(f"{name}: model contracts cannot depend on project implementations: {dependency}")
            if dependency not in declared:
                errors.append(f"{name}: undeclared dependency {dependency}")
            owner_layer = MODULE_LAYERS[name.split(":")[1]]
            dependency_layer = MODULE_LAYERS.get(dependency.split(":")[1], -1)
            if dependency == ":app" or (dependency != MODEL_MODULE and dependency_layer > owner_layer):
                errors.append(f"{name}: forbidden upward dependency {dependency}")
        sources = [file for language in ("java", "kotlin")
                   for file in source_files(directory / "src/main" / language)]
        for source_set in ("main", "test", "androidTest"):
            for language in ("java", "kotlin"):
                source_root = directory / "src" / source_set / language
                verify_packages(root, source_root, errors)
                if source_set == "main":
                    verify_directory_capacity(root, source_root, errors)
        if not sources:
            errors.append(f"{name}: no owned production sources")
        for source in sources:
            text = source.read_text()
            label = source.relative_to(root)
            package = PACKAGE.search(text)
            if package:
                if "model" in package[1].split(".") and name != MODEL_MODULE:
                    errors.append(f"{label}: model package belongs to {MODEL_MODULE}")
                for prefix, owner in PACKAGE_OWNERS.items():
                    if (package[1] == prefix or package[1].startswith(prefix + ".")) and name != owner:
                        if owner != MODEL_MODULE:
                            errors.append(f"{label}: package belongs to {owner}")
            if name == MODEL_MODULE:
                for imported in re.findall(r'^import (moe\.ouom\.neriplayer\.[\w.]+)', text, re.MULTILINE):
                    if not imported.startswith("moe.ouom.neriplayer.data.model.") and imported not in LEGACY_MODEL_TYPES:
                        errors.append(f"{label}: model contract imports implementation {imported}")
            if name.startswith(":data:") and package and package[1].startswith((
                "moe.ouom.neriplayer.api.", "moe.ouom.neriplayer.core.api."
            )):
                errors.append(f"{label}: API implementation belongs in an api module")
            for imported in re.findall(r'^import ([\w.]+)', text, re.MULTILINE):
                if FORBIDDEN_IMPORT.match(f"import {imported}") and imported not in LEGACY_MODEL_TYPES:
                    errors.append(f"{label}: application dependency import {imported}")
            count = len(text.splitlines())
            if count >= 2000:
                errors.append(f"{label}: {count} lines, must remain below 2000")

    complete = set()

    def visit(name, path):
        if name in path:
            errors.append("Dependency cycle: " + " -> ".join(path + [name]))
            return
        if name in complete:
            return
        for dependency in sorted(graph.get(name, ())):
            visit(dependency, path + [name])
        complete.add(name)

    for name in sorted(graph):
        visit(name, [])
    for language in ("java", "kotlin"):
        app_sources = root / "app/src/main" / language
        verify_packages(root, app_sources, errors)
        for source in source_files(app_sources):
            package = PACKAGE.search(source.read_text())
            if package and "model" in package[1].split("."):
                errors.append(f"{source.relative_to(root)}: model package belongs to {MODEL_MODULE}")
        for family in LIBRARY_OWNED_FAMILIES:
            for source in source_files(app_sources / "moe/ouom/neriplayer" / family):
                errors.append(f"{source.relative_to(root)}: production code belongs in a library module")
        for family in APP_FAMILIES:
            verify_directory_capacity(root, app_sources / "moe/ouom/neriplayer" / family, errors)
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    errors = verify(args.root)
    for error in errors:
        print(error, file=sys.stderr)
    if not errors:
        print("Module boundaries: passed")
    return int(bool(errors))


if __name__ == "__main__":
    sys.exit(main())
