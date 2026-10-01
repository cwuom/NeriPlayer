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
PLAYER_POLICY_FAMILIES = tuple(
    f"core/player/policy/{family}"
    for family in ("audio", "command", "offload", "pending", "progress", "service", "skip", "storage", "wake")
)
PLAYER_AUDIO_FAMILIES = ("core/player/audio/processing", "core/player/audio/reactive")
DOWNLOAD_RULE_FAMILIES = (
    "core/download/admission",
    "core/download/execution/state",
    "core/download/execution/retry",
    "core/download/execution/scheduling/queue",
    "core/download/policy/commit",
    "core/download/policy/clear",
    "core/download/policy/size",
    "core/download/policy/publication",
    "core/download/resource/permit",
    "core/download/resource/watchdog",
    "core/download/network",
    "core/download/ownership",
    "core/download/storage/metadata/codec",
    "core/download/storage/metadata/serialization",
)
SYNC_DOMAIN_FAMILIES = tuple(
    f"data/sync/{family}"
    for family in ("change", "codec", "mapping/stats", "remote", "retry", "runtime", "sanitize", "schedule")
)
APP_FAMILIES = (
    "core/player/download", "core/player/service", "data/settings",
    "ui/screen/tab/settings/component",
    "core/player/queue", "data/sync/merge",
    "data/sync",
)
LIBRARY_OWNED_FAMILIES = (
    "data/sync", "api/sync",
    "data",
    "data/local/database/dao", "data/local/database/entity", "data/local/database/migration",
    "core/download", "core/player/download",
    "core/player",
    "core/player/runtime", *PLAYER_POLICY_FAMILIES, *PLAYER_AUDIO_FAMILIES,
    "data/ltw", "listentogether",
    *DOWNLOAD_RULE_FAMILIES,
    "core/api", "core/lyrics", "core/player/queue", "data/sync/merge",
    *SYNC_DOMAIN_FAMILIES,
)
MODULE_LAYERS = {"core": 0, "api": 1, "data": 2, "feature": 3}
MODEL_MODULE = ":data:model"
# 已写入 Android 保存状态的 Parcelable 全名需要保持稳定
LEGACY_MODEL_TYPES = {
    f"moe.ouom.neriplayer.ui.viewmodel.tab.{name}"
    for name in ("PlaylistSummary", "AlbumSummary", "BiliPlaylist", "BiliPlaylistKind", "YouTubeMusicPlaylist")
}
LEGACY_MODEL_TYPES.add("moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem")
LEGACY_MODEL_TYPES.add("moe.ouom.neriplayer.data.sync.model.SyncCausalToken")
LEGACY_MODEL_TYPES.add("moe.ouom.neriplayer.core.download.naming.ParsedManagedDownloadFileName")
PACKAGE_OWNERS = {
    "moe.ouom.neriplayer.api.sync": ":api:sync",
    "moe.ouom.neriplayer.data.sync.store": ":data:sync-store",
    **{f"moe.ouom.neriplayer.data.{family}": ":data:repository"
       for family in ("settings", "history", "identity", "backup", "config", "traffic", "search",
                      "auth", "network", "playlist", "stats", "storage", "listentogether",
                      "local.media", "local.audioimport", "local.playlist", "local.storage")},
    "moe.ouom.neriplayer.data.auth.netease": ":data:netease",
    "moe.ouom.neriplayer.data.auth.bili": ":data:bilibili",
    "moe.ouom.neriplayer.data.youtube": ":data:youtube",
    **{f"moe.ouom.neriplayer.data.storage.{family}": ":data:storage"
       for family in ("accounting", "source", "scan", "cleanup", "policy")},
    "moe.ouom.neriplayer.data.platform.netease": ":data:netease",
    "moe.ouom.neriplayer.data.platform.youtube": ":data:youtube",
    "moe.ouom.neriplayer.data.platform.bili.cache": ":data:bilibili",
    "moe.ouom.neriplayer.data.local.database.dao": ":data:database",
    "moe.ouom.neriplayer.data.local.database.entity": ":data:database",
    "moe.ouom.neriplayer.data.local.database.migration": ":data:database",
    "moe.ouom.neriplayer.core.download": ":feature:download",
    "moe.ouom.neriplayer.core.player.download": ":feature:download",
    "moe.ouom.neriplayer.core.download.policy.settings": ":core:download",
    "moe.ouom.neriplayer.core.download.storage": (":core:common", ":core:download", ":feature:download"),
    "moe.ouom.neriplayer.core.player": ":feature:player",
    "moe.ouom.neriplayer.core.player.runtime": ":core:player-runtime",
    **{f"moe.ouom.neriplayer.{family.replace('/', '.')}": ":core:player-policy"
       for family in PLAYER_POLICY_FAMILIES},
    **{f"moe.ouom.neriplayer.{family.replace('/', '.')}": ":core:player-audio"
       for family in PLAYER_AUDIO_FAMILIES},
    "moe.ouom.neriplayer.data.ltw": ":data:ltw",
    "moe.ouom.neriplayer.listentogether": ":core:ltw-protocol",
    **{f"moe.ouom.neriplayer.{family.replace('/', '.')}": ":core:download"
       for family in DOWNLOAD_RULE_FAMILIES},
    **{f"moe.ouom.neriplayer.{family.replace('/', '.')}": ":data:sync"
       for family in SYNC_DOMAIN_FAMILIES},
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
            if name == ":feature:player" and dependency == ":feature:download":
                errors.append(f"{name}: use PlayerDownloadAccess instead of depending on {dependency}")
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
                owners = [(prefix, owner) for prefix, owner in PACKAGE_OWNERS.items()
                          if package[1] == prefix or package[1].startswith(prefix + ".")]
                if owners:
                    _, owner = max(owners, key=lambda item: len(item[0]))
                    legacy_contract = name == MODEL_MODULE and f"{package[1]}.{source.stem}" in LEGACY_MODEL_TYPES
                    if name not in (owner if isinstance(owner, tuple) else (owner,)) and owner != MODEL_MODULE and not legacy_contract:
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
                if name == ":feature:player" and (imported == "moe.ouom.neriplayer.core.player.PlayerManager"
                                                   or imported.startswith("moe.ouom.neriplayer.core.player.PlayerManager.")):
                    continue
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
        owned_sources = {
            source
            for family in LIBRARY_OWNED_FAMILIES
            for source in source_files(app_sources / "moe/ouom/neriplayer" / family)
        }
        for source in sorted(owned_sources):
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
