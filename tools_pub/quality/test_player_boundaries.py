from pathlib import Path
import json
import tempfile
import unittest

from module_boundaries import verify


class PlayerBoundariesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "settings.gradle.kts").write_text(
            'include(":app")\nincludeOwnedLibrary(":core:player-runtime")\n'
        )
        app = self.root / "app"
        app.mkdir()
        (app / "build.gradle.kts").write_text(
            'val ownedLibraryPaths = listOf(":core:player-runtime")\n'
        )
        module = self.root / "modules/core/player-runtime"
        module.mkdir(parents=True)
        (module / "build.gradle.kts").write_text(
            'plugins { id("build-logic.android.feature-library") }\n'
        )
        self.source(module / "src/main/java", "core/player/runtime/refresh")

    @staticmethod
    def source(root, family):
        directory = root / "moe/ouom/neriplayer" / family
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "Sample.kt").write_text(
            "package moe.ouom.neriplayer." + family.replace("/", ".") + "\nclass Sample\n"
        )

    def test_accepts_player_runtime_in_its_own_module(self):
        self.assertEqual([], verify(self.root))

    def test_rejects_player_runtime_returned_to_app(self):
        self.source(self.root / "app/src/main/java", "core/player/runtime/refresh")
        self.assertTrue(any("belongs in a library module" in error for error in verify(self.root)))

    def test_rejects_player_runtime_in_another_library(self):
        self.source(self.root / "modules/core/player-runtime/src/main/java", "core/player/audio/processing")
        self.assertTrue(any("package belongs to :core:player-audio" in error for error in verify(self.root)))

    def test_rejects_player_engine_returned_to_app(self):
        self.source(self.root / "app/src/main/java", "core/player/engine")
        self.assertTrue(any("belongs in a library module" in error for error in verify(self.root)))

    def test_feature_player_can_use_its_facade_but_cannot_import_app_container(self):
        settings = self.root / "settings.gradle.kts"
        settings.write_text(settings.read_text() + 'includeOwnedLibrary(":feature:player")\n')
        app_script = self.root / "app/build.gradle.kts"
        app_script.write_text('val ownedLibraryPaths = listOf(":core:player-runtime", ":feature:player")\n')
        module = self.root / "modules/feature/player"
        module.mkdir(parents=True)
        (module / "build.gradle.kts").write_text('plugins { id("build-logic.android.feature-library") }\n')
        self.source(module / "src/main/java", "core/player/engine")
        source = module / "src/main/java/moe/ouom/neriplayer/core/player/engine/Sample.kt"
        source.write_text(source.read_text() + '\nimport moe.ouom.neriplayer.core.player.PlayerManager\n')
        self.assertEqual([], verify(self.root))
        source.write_text(source.read_text() + 'import moe.ouom.neriplayer.core.di.AppContainer\n')
        self.assertTrue(any("application dependency import" in error for error in verify(self.root)))

    def test_core_player_cannot_depend_on_feature_implementation(self):
        settings = self.root / "settings.gradle.kts"
        settings.write_text(settings.read_text() + 'includeOwnedLibrary(":feature:player")\n')
        script = self.root / "modules/core/player-runtime/build.gradle.kts"
        script.write_text(script.read_text() + 'implementation(project(":feature:player"))\n')
        self.assertTrue(any("forbidden upward dependency :feature:player" in error for error in verify(self.root)))

    def test_feature_player_uses_download_host_instead_of_download_implementation(self):
        settings = self.root / "settings.gradle.kts"
        settings.write_text(settings.read_text() +
                            'includeOwnedLibrary(":feature:player")\nincludeOwnedLibrary(":feature:download")\n')
        app_script = self.root / "app/build.gradle.kts"
        app_script.write_text('val ownedLibraryPaths = listOf(":core:player-runtime", ":feature:player", ":feature:download")\n')
        for name, family in (("player", "core/player/engine"), ("download", "core/download/manager")):
            module = self.root / "modules/feature" / name
            module.mkdir(parents=True)
            (module / "build.gradle.kts").write_text('plugins { id("build-logic.android.feature-library") }\n')
            self.source(module / "src/main/java", family)
        self.assertEqual([], verify(self.root))
        script = self.root / "modules/feature/player/build.gradle.kts"
        script.write_text(script.read_text() + 'implementation(project(":feature:download"))\n')
        self.assertEqual([
            ":feature:player: use PlayerDownloadAccess instead of depending on :feature:download"
        ], verify(self.root))

    def test_all_player_library_sources_are_in_complexity_scope(self):
        root = Path(__file__).resolve().parents[2]
        scope = json.loads((root / "config/quality/crap-scope.json").read_text())
        for module in ("player-policy", "player-runtime", "player-audio"):
            source_root = root / "modules/core" / module / "src/main/java"
            sources = list(source_root.rglob("*.kt"))
            self.assertTrue(sources, module)
            scoped = {path for pattern in scope["source_patterns"] for path in source_root.glob(pattern)}
            for source in sources:
                relative = source.relative_to(source_root).as_posix()
                self.assertIn(source, scoped, f"{module}: {relative} is outside the complexity gate")

    def test_new_nested_player_files_are_automatically_in_complexity_scope(self):
        root = Path(__file__).resolve().parents[2]
        scope = json.loads((root / "config/quality/crap-scope.json").read_text())
        families = (
            "policy/audio", "policy/command", "policy/offload", "policy/pending",
            "policy/progress", "policy/service", "policy/skip", "policy/storage", "policy/wake",
            "runtime/refresh", "audio/processing", "audio/reactive",
            "host", "integration/ltw", "presentation/widget", "policy/usb"
        )
        source_root = self.root / "future-sources"
        for family in families:
            self.source(source_root, f"core/player/{family}/nested")
        scoped = {path for pattern in scope["source_patterns"] for path in source_root.glob(pattern)}
        self.assertEqual(set(source_root.rglob("*.kt")), scoped)

    def test_app_player_adapters_are_in_complexity_scope(self):
        root = Path(__file__).resolve().parents[2]
        scope = json.loads((root / "config/quality/crap-scope.json").read_text())
        source_root = root / "app/src/main/java"
        adapters = list((source_root / "moe/ouom/neriplayer/core/di/player").glob("*.kt"))
        self.assertTrue(adapters)
        scoped = {path for pattern in scope["source_patterns"] for path in source_root.glob(pattern)}
        self.assertTrue(set(adapters).issubset(scoped))

    def test_original_app_player_source_sets_remain_empty(self):
        root = Path(__file__).resolve().parents[2]
        for source_set in ("main", "test", "androidTest"):
            for language in ("java", "kotlin"):
                source_root = root / "app/src" / source_set / language / "moe/ouom/neriplayer/core/player"
                sources = [path for path in source_root.rglob("*") if path.suffix in {".kt", ".java"}]
                self.assertEqual([], sources, f"{source_set}: player implementations and tests belong in their modules")


if __name__ == "__main__":
    unittest.main()
