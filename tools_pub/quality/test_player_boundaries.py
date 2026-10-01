from pathlib import Path
import json
import tempfile
import unittest

from module_boundaries import verify
from domain_dependencies import check_class_dependencies, load_domains, select_domains


class PlayerBoundariesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "settings.gradle.kts").write_text(
            'include(":app")\nincludeOwnedLibrary(":playback:logic")\n'
        )
        registry = self.root / "gradle/owned-modules.txt"
        registry.parent.mkdir()
        registry.write_text(":playback:logic\n")
        app = self.root / "app"
        app.mkdir()
        (app / "build.gradle.kts").write_text(
            'val ownedLibraryPaths = listOf(":playback:logic")\n'
        )
        module = self.root / "modules/playback/logic"
        module.mkdir(parents=True)
        (module / "build.gradle.kts").write_text(
            'plugins { id("build-logic.android.feature-library") }\n'
        )
        self.source(module / "src/main/java", "core/player/runtime/refresh")

    @staticmethod
    def source(root, family, name="Sample"):
        directory = root / "moe/ouom/neriplayer" / family
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f"{name}.kt").write_text(
            "package moe.ouom.neriplayer." + family.replace("/", ".") + f"\nclass {name}\n"
        )

    def add_module(self, name):
        registry = self.root / "gradle/owned-modules.txt"
        registry.write_text(registry.read_text() + name + "\n")
        settings = self.root / "settings.gradle.kts"
        settings.write_text(settings.read_text() + f'includeOwnedLibrary("{name}")\n')
        names = registry.read_text().splitlines()
        (self.root / "app/build.gradle.kts").write_text(
            'val ownedLibraryPaths = listOf(' + ', '.join(f'"{module}"' for module in names) + ')\n'
        )
        module = self.root / "modules" / name[1:].replace(":", "/")
        module.mkdir(parents=True)
        (module / "build.gradle.kts").write_text('plugins { id("build-logic.android.feature-library") }\n')
        self.source(module / "src/main/java", "sample")
        return module

    def test_accepts_player_runtime_in_its_own_module(self):
        self.assertEqual([], verify(self.root))

    def test_rejects_player_runtime_returned_to_app(self):
        self.source(self.root / "app/src/main/java", "core/player/runtime/refresh")
        self.assertTrue(any("belongs in a library module" in error for error in verify(self.root)))

    def test_rejects_player_runtime_in_another_library(self):
        self.source(self.root / "modules/playback/logic/src/main/java", "core/player/engine")
        self.assertTrue(any("package belongs to :playback:runtime" in error for error in verify(self.root)))

    def test_rejects_player_engine_returned_to_app(self):
        self.source(self.root / "app/src/main/java", "core/player/engine")
        self.assertTrue(any("belongs in a library module" in error for error in verify(self.root)))

    def test_feature_player_can_use_its_facade_but_cannot_import_app_container(self):
        settings = self.root / "settings.gradle.kts"
        settings.write_text(settings.read_text() + 'includeOwnedLibrary(":playback:runtime")\n')
        (self.root / "gradle/owned-modules.txt").write_text(":playback:logic\n:playback:runtime\n")
        app_script = self.root / "app/build.gradle.kts"
        app_script.write_text('val ownedLibraryPaths = listOf(":playback:logic", ":playback:runtime")\n')
        module = self.root / "modules/playback/runtime"
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
        settings.write_text(settings.read_text() + 'includeOwnedLibrary(":playback:runtime")\n')
        (self.root / "gradle/owned-modules.txt").write_text(":playback:logic\n:playback:runtime\n")
        script = self.root / "modules/playback/logic/build.gradle.kts"
        script.write_text(script.read_text() + 'implementation(project(":playback:runtime"))\n')
        self.assertTrue(any("forbidden domain dependency :playback:runtime" in error for error in verify(self.root)))

    def test_feature_player_uses_download_host_instead_of_download_implementation(self):
        settings = self.root / "settings.gradle.kts"
        settings.write_text(settings.read_text() +
                            'includeOwnedLibrary(":playback:runtime")\nincludeOwnedLibrary(":download:runtime")\n')
        (self.root / "gradle/owned-modules.txt").write_text(":playback:logic\n:playback:runtime\n:download:runtime\n")
        app_script = self.root / "app/build.gradle.kts"
        app_script.write_text('val ownedLibraryPaths = listOf(":playback:logic", ":playback:runtime", ":download:runtime")\n')
        for name, family in (("playback", "core/player/engine"), ("download", "core/download/manager")):
            module = self.root / "modules" / name / "runtime"
            module.mkdir(parents=True)
            (module / "build.gradle.kts").write_text('plugins { id("build-logic.android.feature-library") }\n')
            self.source(module / "src/main/java", family)
        self.assertEqual([], verify(self.root))
        script = self.root / "modules/playback/runtime/build.gradle.kts"
        script.write_text(script.read_text() + 'implementation(project(":download:runtime"))\n')
        self.assertEqual([
            ":playback:runtime: use PlayerDownloadAccess instead of depending on :download:runtime"
        ], verify(self.root))

    def test_original_playback_domain_sources_keep_complexity_scope(self):
        root = Path(__file__).resolve().parents[2]
        scope = json.loads((root / "config/quality/crap-scope.json").read_text())
        for module in ("playback/logic",):
            source_root = root / "modules" / module / "src/main/java"
            families = (
                "policy/audio", "policy/command", "policy/offload", "policy/pending",
                "policy/progress", "policy/service", "policy/skip", "policy/storage", "policy/wake",
                "runtime", "audio/processing", "audio/reactive",
            )
            sources = [source for family in families
                       for source in (source_root / "moe/ouom/neriplayer/core/player" / family).rglob("*.kt")]
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

    def test_usb_policy_stays_with_playback_runtime(self):
        module = self.add_module(":playback:runtime")
        self.source(module / "src/main/java", "core/player/policy/usb/quality")
        self.assertEqual([], verify(self.root))
        self.source(self.root / "modules/playback/logic/src/main/java", "core/player/policy/usb/quality")
        self.assertTrue(any("package belongs to :playback:runtime" in error for error in verify(self.root)))

    def test_lyricon_coordinator_ownership_does_not_move_other_player_lyrics(self):
        lyrics = self.add_module(":lyrics")
        playback = self.add_module(":playback:runtime")
        self.source(lyrics / "src/main/java", "core/player/lyrics", "LyriconUpdateCoordinator")
        self.source(playback / "src/main/java", "core/player/lyrics", "FloatingLyricsOverlayManager")
        self.assertEqual([], verify(self.root))
        self.source(playback / "src/main/java", "core/player/lyrics", "LyriconUpdateCoordinator")
        self.assertTrue(any("package belongs to :lyrics" in error for error in verify(self.root)))

    def test_lyricon_coordinator_cannot_bypass_ownership_by_renaming_its_file(self):
        playback = self.add_module(":playback:runtime")
        self.source(playback / "src/main/java", "core/player/lyrics", "LyriconUpdateCoordinator")
        directory = playback / "src/main/java/moe/ouom/neriplayer/core/player/lyrics"
        (directory / "LyriconUpdateCoordinator.kt").rename(directory / "OutputCoordinator.kt")
        self.assertTrue(any("package belongs to :lyrics" in error for error in verify(self.root)))

    def test_lyrics_output_integration_cannot_use_player_dependencies(self):
        lyrics = self.add_module(":lyrics")
        self.source(lyrics / "src/main/java", "lyrics/integration")
        source = lyrics / "src/main/java/moe/ouom/neriplayer/lyrics/integration/Sample.kt"
        self.assertEqual([], verify(self.root))
        source.write_text(source.read_text() + "\nimport moe.ouom.neriplayer.core.player.host.PlayerDependencies\n")
        self.assertTrue(any("player implementation import" in error for error in verify(self.root)))

    def test_lyricon_output_implementations_require_lyrics_runtime(self):
        lyrics = self.add_module(":lyrics")
        for family in ("core/lyricon", "lyrics/integration"):
            self.source(lyrics / "src/main/java", family)
        self.assertEqual([], verify(self.root))
        self.source(self.root / "modules/playback/logic/src/main/java", "core/lyricon")
        self.assertTrue(any("package belongs to :lyrics" in error for error in verify(self.root)))

    def test_future_policy_classes_cannot_reference_runtime_audio_or_queue(self):
        root = Path(__file__).resolve().parents[2]
        domains = [domain for domain in load_domains(root / "config/quality/domain-dependencies.json")
                   if domain["name"].startswith("player-policy-")]
        families = ("audio", "command", "offload", "pending", "progress", "service", "skip", "storage", "wake")
        classes = {f"moe.ouom.neriplayer.core.player.policy.{family}.nested.FuturePolicy" for family in families}
        selected = select_domains(classes, domains)
        for implementation in ("runtime.refresh.RequestGate", "audio.processing.BalanceProcessor", "queue.state.QueueStore"):
            with self.subTest(implementation=implementation):
                dependency = "moe.ouom.neriplayer.core.player." + implementation
                output = "\n".join(f"   {owner} -> {dependency} module" for owner in sorted(classes))
                _, errors = check_class_dependencies(output, selected)
                self.assertEqual(9, len(errors), errors)

    def test_policy_domains_allow_existing_model_and_framework_dependencies(self):
        root = Path(__file__).resolve().parents[2]
        domains = [domain for domain in load_domains(root / "config/quality/domain-dependencies.json")
                   if domain["name"].startswith("player-policy-")]
        dependencies = {
            "audio": "moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo",
            "command": "androidx.media3.common.Player",
            "offload": "moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource",
            "pending": "androidx.media3.common.Player",
            "progress": "androidx.media3.common.Player",
            "service": "kotlin.coroutines.Continuation",
            "skip": "moe.ouom.neriplayer.data.model.bilibili.skip.BiliSponsorBlockSegment",
            "storage": "java.lang.String",
            "wake": "androidx.media3.common.C",
        }
        classes = {f"moe.ouom.neriplayer.core.player.policy.{family}.ExistingPolicy" for family in dependencies}
        selected = select_domains(classes, domains)
        output = "\n".join(
            f"   moe.ouom.neriplayer.core.player.policy.{family}.ExistingPolicy -> {dependency} module"
            for family, dependency in dependencies.items()
        )
        _, errors = check_class_dependencies(output, selected)
        self.assertEqual([], errors)


if __name__ == "__main__":
    unittest.main()
