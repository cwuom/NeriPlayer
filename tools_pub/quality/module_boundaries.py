"""Check owned Gradle libraries without requiring the Android SDK."""

import argparse
from collections import Counter, defaultdict
from pathlib import Path
import re
import sys


PROJECT_REFERENCE = re.compile(r'project\("(:[\w:-]+)"\)')
CONFIGURED_PROJECT_REFERENCE = re.compile(
    r'\b(ksp|testImplementation|compileOnly)\s*\(\s*project\("(:[\w:-]+)"\)\s*\)'
)
MODULE_INCLUDE = re.compile(r'(?:include|includeOwnedLibrary)\("(:[\w:-]+)"\)')
OWNED_INCLUDE = re.compile(r'includeOwnedLibrary\("(:[\w:-]+)"\)')
PACKAGE = re.compile(r'^[ \t]*package\s+([\w.]+)', re.MULTILINE)
IMPORT = re.compile(r'^[ \t]*import\s+([\w.]+)', re.MULTILINE)
TYPE_DECLARATION = re.compile(
    r'^[ \t]*(?:(?:public|internal|private|protected|open|abstract|sealed|data|enum)\s+)*class\s+(\w+)',
    re.MULTILINE,
)
SOURCE_SYMBOL = re.compile(
    r'\b(?:class|interface|object|fun|val|var|typealias)\s+'
    r'(?:<[^>]+>\s*)?(?:[\w?<>.]+\.)?(\w+)'
)
OWNED_MODULE_PATH = re.compile(r':[a-z][a-z0-9-]*(?::[a-z][a-z0-9-]*)?')
MAX_DIRECTORY_SOURCES = 16
STORAGE_CALCULATION_FAMILIES = tuple(
    f"data.local.storage.{family}"
    for family in ("accounting", "source", "scan", "cleanup", "policy")
)
SYNC_DOMAIN_FAMILIES = tuple(
    f"data/sync/{family}"
    for family in ("change", "codec", "mapping/stats", "remote", "retry", "runtime", "sanitize", "schedule")
)
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
APP_FAMILIES = (
    "core/player/download", "core/player/service", "data/settings",
    "ui/screen/tab/settings/component",
    "core/player/queue", "data/sync/merge",
    "data/sync",
)
LIBRARY_OWNED_FAMILIES = (
    "data/sync", "api/sync",
    "core/player",
    "core/download", "core/player/download",
    "data",
    "core/player/runtime", *PLAYER_POLICY_FAMILIES, *PLAYER_AUDIO_FAMILIES,
    "data/ltw",
    "listentogether",
    "core/api", "common", "network", "platform", "lyrics", "core/player/queue", "data/sync/merge",
    *SYNC_DOMAIN_FAMILIES,
    "data/local/database/dao", "data/local/database/entity", "data/local/database/migration",
    *DOWNLOAD_RULE_FAMILIES,
)
MODEL_MODULE = ":model"
ALLOWED_DEPENDENCIES = {
    ":common": set(),
    ":native": set(),
    ":network": {":common"},
    MODEL_MODULE: set(),
    ":database": {MODEL_MODULE},
    ":playback:logic": {":common", MODEL_MODULE},
    ":download:logic": {":common", MODEL_MODULE},
    ":lyrics": {":common", MODEL_MODULE, ":accompanist-lyrics-core"},
    ":platform": {":common", ":database", ":lyrics", MODEL_MODULE, ":network"},
    ":sync": {":platform", ":common", MODEL_MODULE},
    ":listentogether": {":platform", ":common", MODEL_MODULE},
    ":local": {
        ":platform", ":common", ":database", ":download:logic", ":ksp-annotations",
        ":listentogether", ":lyrics", MODEL_MODULE, ":network", ":sync",
    },
    ":download:runtime": {
        ":platform", ":common", ":database", ":download:logic", ":local", ":lyrics", MODEL_MODULE, ":network",
    },
    ":playback:runtime": {
        ":platform", ":common", ":database", ":listentogether", ":local", ":lyrics", MODEL_MODULE,
        ":network", ":native", ":playback:logic", ":sync",
    },
}
CONFIGURED_DEPENDENCIES = {
    (":playback:runtime", "compileOnly"): {":hidden-api"},
    (":local", "ksp"): {":ksp-processor"},
    (":download:runtime", "testImplementation"): {":sync"},
}
# 旧 Parcelable 类型迁移前需要核对历史版本的状态读取方式
LEGACY_MODEL_TYPES = {
    f"moe.ouom.neriplayer.ui.viewmodel.tab.{name}"
    for name in ("PlaylistSummary", "AlbumSummary", "BiliPlaylist", "BiliPlaylistKind", "YouTubeMusicPlaylist")
}
LEGACY_MODEL_TYPES.add("moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem")
LEGACY_MODEL_TYPES.add("moe.ouom.neriplayer.data.sync.model.SyncCausalToken")
PACKAGE_OWNERS = {
    **{f"moe.ouom.neriplayer.platform.{platform}.api": ":platform"
       for platform in ("bilibili", "netease", "youtube")},
    "moe.ouom.neriplayer.platform.lyrics.api": ":platform",
    "moe.ouom.neriplayer.platform.search.api": ":platform",
    "moe.ouom.neriplayer.api.sync": ":sync",
    "moe.ouom.neriplayer.api.ltw": ":listentogether",
    "moe.ouom.neriplayer.common.logging": ":common",
    "moe.ouom.neriplayer.common": ":common",
    "moe.ouom.neriplayer.platform.comments": ":platform",
    "moe.ouom.neriplayer.lyrics.parser": ":lyrics",
    "moe.ouom.neriplayer.lyrics.lyricon": ":lyrics",
    "moe.ouom.neriplayer.lyrics.output": ":lyrics",
    "moe.ouom.neriplayer.platform.lyrics": ":platform",
    "moe.ouom.neriplayer.data.sync": ":sync",
    **{f"moe.ouom.neriplayer.data.sync.{family}": ":local"
       for family in ("cover", "github", "host", "mapping", "webdav", "work")},
    "moe.ouom.neriplayer.core.player": ":playback:runtime",
    "moe.ouom.neriplayer.core.download": ":download:runtime",
    "moe.ouom.neriplayer.core.player.download": ":download:runtime",
    "moe.ouom.neriplayer.core.download.policy.settings": ":download:logic",
    "moe.ouom.neriplayer.core.download.storage": (":common", ":download:logic", ":download:runtime"),
    **{f"moe.ouom.neriplayer.{family.replace('/', '.')}": ":sync"
       for family in SYNC_DOMAIN_FAMILIES},
    "moe.ouom.neriplayer.core.player.runtime": ":playback:logic",
    **{f"moe.ouom.neriplayer.{family.replace('/', '.')}": ":playback:logic"
       for family in PLAYER_POLICY_FAMILIES},
    **{f"moe.ouom.neriplayer.{family.replace('/', '.')}": ":playback:logic"
       for family in PLAYER_AUDIO_FAMILIES},
    "moe.ouom.neriplayer.data.ltw": ":listentogether",
    "moe.ouom.neriplayer.listentogether": ":listentogether",
    "moe.ouom.neriplayer.data.model": MODEL_MODULE,
    **{f"moe.ouom.neriplayer.data.{family}": ":local"
       for family in ("settings", "history", "identity", "backup", "config", "traffic", "search",
                      "auth", "network", "playlist", "stats", "storage", "listentogether",
                      "local.media", "local.audioimport", "local.playlist", "local.storage")},
    "moe.ouom.neriplayer.platform.netease.auth": ":platform",
    "moe.ouom.neriplayer.platform.bilibili.auth": ":platform",
    "moe.ouom.neriplayer.platform.youtube": ":platform",
    "moe.ouom.neriplayer.platform.netease": ":platform",
    "moe.ouom.neriplayer.platform.youtube.playlist": ":platform",
    "moe.ouom.neriplayer.platform.bilibili": ":platform",
    "moe.ouom.neriplayer.network": ":network",
    "moe.ouom.neriplayer.lyrics": ":lyrics",
    "moe.ouom.neriplayer.data.local.platform": ":local",
    "moe.ouom.neriplayer.core.player.queue": ":playback:logic",
    "moe.ouom.neriplayer.data.local.database": ":database",
    "moe.ouom.neriplayer.data.local.database.store": ":local",
    **{f"moe.ouom.neriplayer.{family.replace('/', '.')}": ":download:logic"
       for family in DOWNLOAD_RULE_FAMILIES},
}
SOURCE_OWNERS = {
    "moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore": ":database",
    "moe.ouom.neriplayer.data.sync.CoverUrlMapper": ":local",
}
FORBIDDEN_IMPORT = re.compile(
    r'^import moe\.ouom\.neriplayer\.(?:'
    r'core\.di\.|core\.player\.PlayerManager\b|ui\.|activity\.|'
    r'(?:R|BuildConfig|NeriPlayerApplication)\b)', re.MULTILINE
)
PROJECT_SOURCE_REFERENCE = re.compile(
    r'\b(?:moe\.ouom\.neriplayer|io\.github\.proify\.lyricon|com\.hchen\.superlyricapi)'
    r'(?:\.[A-Za-z_]\w*)+(?:\.\*)?'
)
ROOM_SOURCE_REFERENCE = re.compile(r'\bandroidx\.room\.(?:[A-Za-z_]\w*\.)*(?:[A-Za-z_]\w*|\*)')
SOURCE_COMMENTS_AND_LITERALS = re.compile(
    r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/'
)
PROJECT_PACKAGE = "moe.ouom.neriplayer."
METADATA_PARSER_REFERENCES = {
    PROJECT_PACKAGE + "lyrics.parser.normalizeLegacyLrcTimestamps",
    PROJECT_PACKAGE + "lyrics.parser.hasEditableLyricWordTiming",
}
PROVIDER_PARSER_REFERENCES = METADATA_PARSER_REFERENCES | {
    PROJECT_PACKAGE + "lyrics.parser.hasWordTimedEntries",
    PROJECT_PACKAGE + "lyrics.parser.parseNeteaseLyricsAuto",
    PROJECT_PACKAGE + "lyrics.parser.convertPlainLyricsToEntries",
    PROJECT_PACKAGE + "lyrics.parser.toEditableLyricsText",
}
PACKAGE_DEPENDENCY_RULES = (
    (("platform.bilibili.api",), {":common", MODEL_MODULE, ":network"}, (), set()),
    (("platform.netease.api",), {":common", MODEL_MODULE, ":network"}, (), set()),
    (("platform.youtube.api",), {":common", MODEL_MODULE, ":network"}, (), set()),
    (("platform.lyrics.api", "platform.search.api"), {":common", MODEL_MODULE, ":network"},
     ("platform.netease.api.client.NeteaseClient",), METADATA_PARSER_REFERENCES),
    (("platform.bilibili", "platform.bilibili.auth", "platform.bilibili.skip.storage.BiliVideoSkipRoomStore"),
     {":common", MODEL_MODULE, ":network", ":database"}, ("platform.bilibili.api",), set()),
    (("platform.netease", "platform.netease.auth"), {":common", MODEL_MODULE, ":database"},
     ("platform.netease.api",), set()),
    (("platform.youtube.playlist", "platform.youtube"), {":common", MODEL_MODULE, ":network", ":database"},
     ("platform.youtube.api",), set()),
    (("platform.comments",), {":common", MODEL_MODULE},
     ("platform.bilibili.api", "platform.netease.api"), {
         PROJECT_PACKAGE + "platform.bilibili.playback.resolver." + function
         for function in ("biliBvidOrNull", "biliCidOrNull", "buildBiliSongAlbum", "resolveBiliSong")
     }),
    (("platform.lyrics",), {":common", MODEL_MODULE}, ("platform.lyrics.api", "platform.search.api", "platform.youtube.api"),
     PROVIDER_PARSER_REFERENCES),
)


def owned_modules(root, errors):
    registry = root / "gradle/owned-modules.txt"
    if not registry.is_file():
        errors.append("missing owned module registry: gradle/owned-modules.txt")
        return set()
    modules = set()
    for line in registry.read_text().splitlines():
        if not line.strip():
            continue
        if not OWNED_MODULE_PATH.fullmatch(line):
            errors.append(f"invalid owned module path: {line}")
        elif line in modules:
            errors.append(f"duplicate owned module: {line}")
        else:
            modules.add(line)
    return modules


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


def gradle_statement(script, start):
    while start < len(script) and script[start].isspace():
        start += 1
    depth = 0
    quote = None
    escaped = False
    for index in range(start, len(script)):
        character = script[index]
        if quote is not None:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == quote:
                quote = None
        elif character in "\"'":
            quote = character
        elif character in "([{":
            depth += 1
        elif character in ")]}":
            depth -= 1
        elif character == "\n" and depth == 0:
            following = re.sub(r'\A(?:\s+|//[^\n]*(?:\n|$)|/\*.*?\*/)*', "", script[index + 1:], flags=re.DOTALL)
            if not following.startswith((".", "?", "+", "-", "*", "/", "%", "&", "|", "^", "<", ">", "=", "!", "[", "as ", "is ")):
                return script[start:index].strip()
    return script[start:].strip()


def owned_registry_expression(script):
    declarations = list(re.finditer(r'^[ \t]*val\s+ownedLibraryPaths\s*=', script, re.MULTILINE))
    if not declarations:
        return None
    return gradle_statement(script, declarations[0].end()) if len(declarations) == 1 else ""


def canonical_registry_reader(expression, receiver):
    return re.fullmatch(
        re.escape(receiver) + r'\s*\(\s*"gradle/owned-modules.txt"\s*\)'
        r'\s*\.\s*readLines\s*\(\s*\)\s*\.\s*filter\s*\{\s*it\.isNotBlank\s*\(\s*\)\s*\}',
        expression,
    ) is not None


def reference_matches(reference, prefix):
    return reference == prefix or reference.startswith(prefix + ".")


def production_package_owners(root, modules):
    packages = defaultdict(set)
    for module in modules:
        directory = root / "modules" / module[1:].replace(":", "/") / "src/main"
        for language in ("java", "kotlin"):
            for source in source_files(directory / language):
                text = source.read_text()
                package = PACKAGE.search(text)
                if package:
                    packages[package[1]].add(module)
                    for symbol in SOURCE_SYMBOL.findall(text):
                        packages[f"{package[1]}.{symbol}"].add(module)
    return {package: next(iter(owners)) for package, owners in packages.items() if len(owners) == 1}


def reference_owner(reference, packages):
    owners = [
        (prefix, owner)
        for collection in (packages, PACKAGE_OWNERS, SOURCE_OWNERS)
        for prefix, owner in collection.items()
        if reference_matches(reference, prefix)
    ]
    return max(owners, key=lambda item: len(item[0]))[1] if owners else None


def verify_package_dependencies(name, package, text, label, packages, errors):
    relative = package.removeprefix(PROJECT_PACKAGE)
    if name == ":lyrics":
        parser = any(reference_matches(relative, family)
                     for family in ("lyrics.parser", "lyrics.offset", "lyrics.embedded"))
        allowed_modules = {MODEL_MODULE} if parser else {":common", MODEL_MODULE, ":lyrics"}
        roots, allowed_roots, allowed_references = (), (), set()
        if parser:
            roots = ("lyrics.parser", "lyrics.offset", "lyrics.embedded")
    elif name == ":local" and any(reference_matches(relative, family)
                                  for family in STORAGE_CALCULATION_FAMILIES):
        roots = STORAGE_CALCULATION_FAMILIES
        allowed_modules, allowed_roots, allowed_references = {MODEL_MODULE}, (), set()
    else:
        if name != ":platform":
            return
        source_types = {relative + "." + label.stem} | {
            relative + "." + declaration for declaration in TYPE_DECLARATION.findall(text)
        }
        rule = next((rule for rule in PACKAGE_DEPENDENCY_RULES
                     if any(reference_matches(value, root)
                            for root in rule[0] for value in (relative, *source_types))), None)
        if rule is None:
            return
        roots, allowed_modules, allowed_roots, allowed_references = rule
    code = SOURCE_COMMENTS_AND_LITERALS.sub(
        lambda match: "" if match[0].startswith(("//", "/*")) else match[0], text
    )
    if name == ":local" and any(reference_matches(relative, family)
                                for family in STORAGE_CALCULATION_FAMILIES):
        for reference in sorted(set(ROOM_SOURCE_REFERENCE.findall(code))):
            errors.append(f"{label}: forbidden storage database dependency {reference}")
    for reference in sorted(set(PROJECT_SOURCE_REFERENCE.findall(code))):
        if reference == package:
            continue
        project_reference = reference.removeprefix(PROJECT_PACKAGE)
        if any(reference_matches(project_reference, root) for root in (*roots, *allowed_roots)):
            continue
        if reference in allowed_references or reference in LEGACY_MODEL_TYPES and MODEL_MODULE in allowed_modules:
            continue
        if name == ":lyrics" and reference_matches(relative, "lyrics.lyricon") and reference.startswith((
            "io.github.proify.lyricon.", "com.hchen.superlyricapi."
        )):
            continue
        if reference_owner(reference, packages) in allowed_modules:
            continue
        errors.append(f"{label}: forbidden package dependency {reference}")


def verify(root):
    errors = []
    modules = owned_modules(root, errors)
    if not modules:
        return errors or ["No owned library modules declared"]
    settings = (root / "settings.gradle.kts").read_text()
    declared = set(MODULE_INCLUDE.findall(settings))
    registered_locations = set(OWNED_INCLUDE.findall(settings))
    settings_expression = owned_registry_expression(settings)
    if settings_expression is not None:
        reader_valid = canonical_registry_reader(settings_expression, "file")
        if not reader_valid:
            errors.append("settings.gradle.kts: unsupported owned module registry expression")
        chains = [gradle_statement(settings, match.start())
                  for match in re.finditer(r'^[ \t]*ownedLibraryPaths\b', settings, re.MULTILINE)]
        registration_chains = [chain for chain in chains if "includeOwnedLibrary" in chain]
        chain_valid = bool(registration_chains) and all(re.fullmatch(
            r'ownedLibraryPaths\s*\.\s*forEach\s*\(\s*::includeOwnedLibrary\s*\)', chain
        ) for chain in registration_chains)
        if not chain_valid:
            errors.append("settings.gradle.kts: unsupported owned module registration chain")
        if reader_valid and chain_valid:
            registered_locations.update(modules)
            declared.update(modules)
    for module in sorted(modules - registered_locations):
        errors.append(f"{module}: must register with includeOwnedLibrary")
    for module in sorted(registered_locations - modules):
        errors.append(f"{module}: unregistered library in settings")
    project_directories = set()
    for module in modules:
        parts = tuple(module.lstrip(":").split(":"))
        project_directories.update(parts[:length] for length in range(1, len(parts) + 1))
    for build_file in (root / "modules").rglob("build.gradle.kts"):
        relative_parts = build_file.parent.relative_to(root / "modules").parts
        module = ":" + ":".join(relative_parts)
        project = max((parts for parts in project_directories if relative_parts[:len(parts)] == parts),
                      key=len, default=())
        remaining = relative_parts[len(project):]
        generated = bool(project) and len(remaining) > 1 and remaining[0] in {"build", ".gradle"}
        if module not in modules and not generated:
            errors.append(f"{module}: unregistered library in modules directory")
    app_script = (root / "app/build.gradle.kts").read_text()
    app_expression = owned_registry_expression(app_script)
    registered = set()
    if app_expression is not None:
        if canonical_registry_reader(app_expression, "rootProject.file"):
            registered = modules
        elif re.fullmatch(r'listOf\s*\(\s*(?:"(:[\w:-]+)"\s*(?:,\s*"(:[\w:-]+)"\s*)*,?)?\s*\)', app_expression):
            registered = set(re.findall(r'"(:[\w:-]+)"', app_expression))
        else:
            errors.append("app/build.gradle.kts: unsupported owned module registry expression")
    if registered != modules:
        errors.append(f"App coverage registry differs from owned modules: {sorted(registered ^ modules)}")
    package_owners = production_package_owners(root, modules)
    graph = {}
    for name in sorted(modules):
        directory = root / "modules" / name.lstrip(":").replace(":", "/")
        build_file = directory / "build.gradle.kts"
        if not build_file.is_file():
            errors.append(f"{name}: missing build.gradle.kts")
            continue
        script = build_file.read_text()
        convention = "library" if name == ":native" else "feature-library"
        if f'id("build-logic.android.{convention}")' not in script:
            errors.append(f"{name}: must use the library verification convention")
        dependencies = set(PROJECT_REFERENCE.findall(script))
        configured = list(CONFIGURED_PROJECT_REFERENCE.finditer(script))
        unapproved = {
            reference[1] for reference in PROJECT_REFERENCE.finditer(script)
            if reference[1] not in ALLOWED_DEPENDENCIES.get(name, set()) and not any(
                match.start() <= reference.start() and reference.end() <= match.end()
                and reference[1] in CONFIGURED_DEPENDENCIES.get((name, match[1]), set())
                for match in configured
            )
        }
        graph[name] = dependencies & modules
        if name not in ALLOWED_DEPENDENCIES:
            errors.append(f"{name}: no explicit domain dependency policy")
        for dependency in sorted(dependencies):
            if name == ":playback:runtime" and dependency == ":download:runtime":
                errors.append(f"{name}: use PlayerDownloadAccess instead of depending on {dependency}")
                continue
            if name == MODEL_MODULE:
                errors.append(f"{name}: model contracts cannot depend on project implementations: {dependency}")
            if dependency not in declared:
                errors.append(f"{name}: undeclared dependency {dependency}")
            if name != MODEL_MODULE and dependency in unapproved:
                errors.append(f"{name}: forbidden domain dependency {dependency}")
        sources = [file for language in ("java", "kotlin")
                   for file in source_files(directory / "src/main" / language)]
        for source_set in ("main", "test", "androidTest", "testFixtures"):
            for language in ("java", "kotlin"):
                source_root = directory / "src" / source_set / language
                verify_packages(root, source_root, errors)
                if source_set == "main":
                    verify_directory_capacity(root, source_root, errors)
        if name == ":native":
            if sources:
                errors.append(f"{name}: JNI callers belong in their business modules")
            if not (directory / "src/main/cpp/CMakeLists.txt").is_file():
                errors.append(f"{name}: missing native CMake entrypoint")
        elif not sources:
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
                source_type = f"{package[1]}.{source.stem}"
                owner = SOURCE_OWNERS.get(source_type)
                if owner is None and owners:
                    _, owner = max(owners, key=lambda item: len(item[0]))
                if owner is not None:
                    legacy_contract = name == MODEL_MODULE and source_type in LEGACY_MODEL_TYPES
                    if name not in (owner if isinstance(owner, tuple) else (owner,)) and owner != MODEL_MODULE and not legacy_contract:
                        errors.append(f"{label}: package belongs to {owner}")
                elif package[1].startswith((
                    "moe.ouom.neriplayer.api.", "moe.ouom.neriplayer.core.api."
                )):
                    errors.append(f"{label}: API implementation belongs in its registered platform or transport module")
                verify_package_dependencies(name, package[1], text, label, package_owners, errors)
            if name == MODEL_MODULE:
                for imported in IMPORT.findall(text):
                    if not imported.startswith("moe.ouom.neriplayer."):
                        continue
                    if not imported.startswith("moe.ouom.neriplayer.data.model.") and imported not in LEGACY_MODEL_TYPES:
                        errors.append(f"{label}: model contract imports implementation {imported}")
            for imported in IMPORT.findall(text):
                if name == ":lyrics" and imported.startswith((
                    "moe.ouom.neriplayer.core.player.host.", "moe.ouom.neriplayer.core.player.service."
                )):
                    errors.append(f"{label}: player implementation import {imported}")
                if name == ":playback:runtime" and (imported == "moe.ouom.neriplayer.core.player.PlayerManager"
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
    if (root / "app/src/main/cpp").exists():
        errors.append("app/src/main/cpp: native implementation belongs in :native")
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
