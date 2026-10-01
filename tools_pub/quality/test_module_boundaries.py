from pathlib import Path
import tempfile
import unittest

from module_boundaries import verify


class ModuleBoundariesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.settings = self.root / "settings.gradle.kts"
        self.settings.write_text('include(":app")\n')
        self.app = self.root / "app/build.gradle.kts"
        self.app.parent.mkdir()
        self.modules = []

    def module(self, name, dependency=None, source="package sample\nclass Sample\n"):
        self.settings.write_text(self.settings.read_text() + f'includeOwnedLibrary("{name}")\n')
        self.modules.append(name)
        self.app.write_text('val ownedLibraryPaths = listOf(' +
                            ', '.join(f'"{module}"' for module in self.modules) + ')\n')
        directory = self.root / "modules" / name.lstrip(":").replace(":", "/")
        sources = directory / "src/main/java/sample"
        sources.mkdir(parents=True)
        (sources / "Sample.kt").write_text(source)
        script = 'plugins { id("build-logic.android.feature-library") }\n'
        if dependency:
            script += f'dependencies {{ implementation(project("{dependency}")) }}\n'
        (directory / "build.gradle.kts").write_text(script)

    def test_accepts_downward_dependencies(self):
        self.module(":data:model")
        self.module(":data:music", ":data:model")
        self.assertEqual([], verify(self.root))

    def test_accepts_model_contract_dependency_from_core_and_api(self):
        self.module(":data:model")
        self.module(":core:lyrics", ":data:model")
        self.module(":api:music", ":data:model")
        self.assertEqual([], verify(self.root))

    def test_rejects_implementation_dependency_from_model(self):
        self.module(":core:network")
        self.module(":data:model", ":core:network")
        self.assertTrue(any("model contracts cannot depend on project implementations" in error
                            for error in verify(self.root)))

    def test_rejects_model_package_outside_model_module(self):
        self.module(":api:music")
        directory = self.root / "modules/api/music/src/main/java/moe/ouom/neriplayer/api/music/model"
        directory.mkdir(parents=True)
        (directory / "Track.kt").write_text(
            "package moe.ouom.neriplayer.api.music.model\ndata class Track(val id: String)\n"
        )
        self.assertTrue(any("model package belongs to :data:model" in error for error in verify(self.root)))

    def test_rejects_model_package_left_in_app(self):
        self.module(":data:model")
        directory = self.root / "app/src/main/java/moe/ouom/neriplayer/core/player/model"
        directory.mkdir(parents=True)
        (directory / "Track.kt").write_text(
            "package moe.ouom.neriplayer.core.player.model\ndata class Track(val id: String)\n"
        )
        self.assertTrue(any("model package belongs to :data:model" in error for error in verify(self.root)))

    def test_accepts_api_between_core_and_data(self):
        self.module(":data:model")
        self.module(":api:music", ":data:model")
        self.module(":data:music", ":api:music")
        self.assertEqual([], verify(self.root))

    def test_accepts_only_named_legacy_parcelable_imports(self):
        self.module(":data:model", source=(
            "package sample\n"
            "import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem\n"
            "class Sample(val video: BiliVideoItem)\n"
        ))
        self.assertEqual([], verify(self.root))

    def test_rejects_host_type_next_to_legacy_parcelable(self):
        self.module(":data:model", source=(
            "package sample\n"
            "import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliPlaylistDetailViewModel\n"
            "class Sample\n"
        ))
        self.assertTrue(any("model contract imports implementation" in error
                            for error in verify(self.root)))

    def test_rejects_data_dependency_from_api(self):
        self.module(":data:model")
        self.module(":data:music", ":data:model")
        self.module(":api:music", ":data:music")
        self.assertTrue(any("forbidden upward dependency" in error for error in verify(self.root)))

    def test_rejects_api_dependency_from_core(self):
        self.module(":api:music")
        self.module(":core:common", ":api:music")
        self.assertTrue(any("forbidden upward dependency" in error for error in verify(self.root)))

    def test_rejects_api_implementation_inside_data_module(self):
        self.module(":data:music")
        base = self.root / "modules/data/music/src/main/java/moe/ouom/neriplayer"
        for package in ("core/api/music", "api/music"):
            path = base / package
            path.mkdir(parents=True)
            (path / "Client.kt").write_text(
                "package moe.ouom.neriplayer." + package.replace("/", ".") + "\nclass Client\n"
            )
        errors = verify(self.root)
        self.assertEqual(2, len(errors))
        self.assertTrue(all("API implementation belongs in an api module" in error for error in errors))

    def test_rejects_shared_model_in_common_module(self):
        self.module(":core:common")
        directory = self.root / "modules/core/common/src/main/java/moe/ouom/neriplayer/data/model/auth"
        directory.mkdir(parents=True)
        (directory / "AuthState.kt").write_text(
            "package moe.ouom.neriplayer.data.model.auth\nenum class AuthState { Missing }\n"
        )
        errors = verify(self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("model package belongs to :data:model", errors[0])

    def test_accepts_shared_model_in_model_module(self):
        self.module(":data:model")
        directory = self.root / "modules/data/model/src/main/java/moe/ouom/neriplayer/data/model/auth"
        directory.mkdir(parents=True)
        (directory / "AuthState.kt").write_text(
            "package moe.ouom.neriplayer.data.model.auth\nenum class AuthState { Missing }\n"
        )
        self.assertEqual([], verify(self.root))

    def test_rejects_application_and_data_dependencies_from_core(self):
        self.module(":data:music", ":app")
        self.module(":core:common", ":data:music")
        errors = verify(self.root)
        self.assertEqual(2, len(errors))
        self.assertTrue(all("forbidden upward dependency" in error for error in errors))

    def test_rejects_cycle(self):
        self.module(":core:one", ":core:two")
        self.module(":core:two", ":core:one")
        self.assertTrue(any("Dependency cycle" in error for error in verify(self.root)))

    def test_rejects_app_import_and_oversized_source(self):
        self.module(":data:music", source=(
            "package sample\nimport moe.ouom.neriplayer.core.di.AppContainer\n" + "\n" * 1998
        ))
        errors = verify(self.root)
        self.assertTrue(any("application dependency" in error for error in errors))
        self.assertTrue(any("2000 lines" in error for error in errors))

    def test_rejects_undeclared_dependency(self):
        self.module(":data:model", ":missing")
        self.assertTrue(any("undeclared dependency" in error for error in verify(self.root)))

    def test_rejects_missing_coverage_registration(self):
        self.module(":data:model")
        self.app.write_text('val ownedLibraryPaths = listOf()\n')
        self.assertTrue(any("coverage registry" in error for error in verify(self.root)))

    def test_rejects_library_outside_modules_directory(self):
        self.module(":data:model")
        misplaced = self.root / "data/model"
        misplaced.parent.mkdir()
        (self.root / "modules/data/model").rename(misplaced)
        self.assertTrue(any("missing build.gradle.kts" in error for error in verify(self.root)))

    def test_rejects_unregistered_library(self):
        self.module(":data:model")
        extra = self.root / "modules/data/missing"
        extra.mkdir(parents=True)
        (extra / "build.gradle.kts").write_text("")
        self.assertTrue(any("unregistered library" in error for error in verify(self.root)))

    def test_rejects_package_directory_mismatch(self):
        self.module(":data:model", source="package different\nclass Sample\n")
        self.assertTrue(any("package directory mismatch" in error for error in verify(self.root)))

    def test_accepts_indented_package_declaration(self):
        self.module(":data:model", source=" package sample\nclass Sample\n")
        self.assertEqual([], verify(self.root))

    def test_rejects_test_package_directory_mismatch(self):
        self.module(":data:model")
        directory = self.root / "modules/data/model/src/test/java/sample"
        directory.mkdir(parents=True)
        (directory / "SampleTest.kt").write_text("package wrong\nclass SampleTest\n")
        self.assertTrue(any("package directory mismatch" in error for error in verify(self.root)))

    def test_rejects_crowded_library_directory(self):
        self.module(":data:model")
        directory = self.root / "modules/data/model/src/main/java/sample"
        for index in range(16):
            (directory / f"Extra{index}.kt").write_text(f"package sample\nclass Extra{index}\n")
        self.assertTrue(any("17 source files" in error for error in verify(self.root)))

    def test_rejects_crowded_app_family(self):
        self.module(":data:model")
        directory = self.root / "app/src/main/java/moe/ouom/neriplayer/core/player/download"
        directory.mkdir(parents=True)
        for index in range(17):
            (directory / f"Extra{index}.kt").write_text(
                f"package moe.ouom.neriplayer.core.player.download\nclass Extra{index}\n"
            )
        self.assertTrue(any("17 source files" in error for error in verify(self.root)))

    def test_rejects_production_sources_in_migrated_app_packages(self):
        self.module(":data:model")
        for language in ("java", "kotlin"):
            for family in ("core/api/search", "core/api/lyrics", "core/lyrics", "data/sync/merge"):
                package = "moe/ouom/neriplayer/" + family + "/nested"
                directory = self.root / "app/src/main" / language / package
                directory.mkdir(parents=True)
                (directory / "Stranded.kt").write_text(
                    "package " + package.replace("/", ".") + "\nclass Stranded\n"
                )
        errors = verify(self.root)
        self.assertEqual(8, len(errors))
        self.assertTrue(all("belongs in a library module" in error for error in errors))

    def test_allows_host_integration_tests_for_migrated_packages(self):
        self.module(":data:model")
        directory = self.root / "app/src/androidTest/java/moe/ouom/neriplayer/core/api/lyrics"
        directory.mkdir(parents=True)
        (directory / "HostTest.kt").write_text(
            "package moe.ouom.neriplayer.core.api.lyrics\nclass HostTest\n"
        )
        self.assertEqual([], verify(self.root))

    def test_listen_together_runtime_package_requires_its_own_module(self):
        self.module(":data:ltw")
        self.module(":core:common")
        directory = self.root / "modules/core/common/src/main/java/moe/ouom/neriplayer/data/ltw/session"
        directory.mkdir(parents=True)
        (directory / "Session.kt").write_text(
            "package moe.ouom.neriplayer.data.ltw.session\nclass Session\n"
        )
        self.assertTrue(any("package belongs to :data:ltw" in error for error in verify(self.root)))

    def test_listen_together_runtime_cannot_return_to_app(self):
        self.module(":data:ltw")
        directory = self.root / "app/src/main/java/moe/ouom/neriplayer/data/ltw/session"
        directory.mkdir(parents=True)
        (directory / "Session.kt").write_text(
            "package moe.ouom.neriplayer.data.ltw.session\nclass Session\n"
        )
        self.assertTrue(any("belongs in a library module" in error for error in verify(self.root)))

    def test_entire_legacy_listen_together_tree_must_stay_outside_app(self):
        self.module(":data:ltw")
        for language in ("java", "kotlin"):
            for family in ("", "invite", "validation", "session/membership"):
                package = "moe/ouom/neriplayer/listentogether" + ("/" + family if family else "")
                directory = self.root / "app/src/main" / language / package
                directory.mkdir(parents=True)
                (directory / "Stranded.kt").write_text(
                    "package " + package.replace("/", ".") + "\nclass Stranded\n"
                )
        errors = verify(self.root)
        self.assertEqual(8, len(errors))
        self.assertTrue(all("belongs in a library module" in error for error in errors))


    def test_rejects_database_infrastructure_left_in_app(self):
        self.module(":data:database")
        for language in ("java", "kotlin"):
            for family in ("dao", "entity", "migration"):
                package = "moe/ouom/neriplayer/data/local/database/" + family
                directory = self.root / "app/src/main" / language / package
                directory.mkdir(parents=True)
                (directory / "Stranded.kt").write_text(
                    "package " + package.replace("/", ".") + "\nclass Stranded\n"
                )
        errors = verify(self.root)
        self.assertEqual(6, len(errors))
        self.assertTrue(all("belongs in a library module" in error for error in errors))

    def test_rejects_database_infrastructure_in_another_library(self):
        self.module(":data:database")
        self.module(":data:music")
        directory = self.root / "modules/data/music/src/main/java/moe/ouom/neriplayer/data/local/database/dao"
        directory.mkdir(parents=True)
        (directory / "MisplacedDao.kt").write_text(
            "package moe.ouom.neriplayer.data.local.database.dao\ninterface MisplacedDao\n"
        )
        self.assertTrue(any("package belongs to :data:database" in error for error in verify(self.root)))

    def test_rejects_database_stores_left_in_app(self):
        self.module(":data:database")
        directory = self.root / "app/src/main/java/moe/ouom/neriplayer/data/local/database/store"
        directory.mkdir(parents=True)
        (directory / "HostStore.kt").write_text(
            "package moe.ouom.neriplayer.data.local.database.store\nclass HostStore\n"
        )
        self.assertTrue(any("belongs in a library module" in error for error in verify(self.root)))

    def test_all_data_implementations_must_stay_outside_app(self):
        self.module(":data:repository")
        for language in ("java", "kotlin"):
            for family in ("settings", "traffic", "auth/web", "local/media", "config"):
                package = "moe/ouom/neriplayer/data/" + family
                directory = self.root / "app/src/main" / language / package
                directory.mkdir(parents=True)
                (directory / "Stranded.kt").write_text(
                    "package " + package.replace("/", ".") + "\nclass Stranded\n"
                )
        errors = verify(self.root)
        self.assertEqual(10, len(errors))
        self.assertTrue(all("belongs in a library module" in error for error in errors))

    def test_platform_authentication_stays_with_platform_data(self):
        self.module(":data:repository")
        owners = {
            "auth/netease": ":data:netease",
            "auth/bili": ":data:bilibili",
            "youtube/auth": ":data:youtube",
        }
        for family, owner in owners.items():
            self.module(owner)
            package = "moe/ouom/neriplayer/data/" + family
            directory = self.root / "modules/data/repository/src/main/java" / package
            directory.mkdir(parents=True)
            (directory / "MisplacedAuth.kt").write_text(
                "package " + package.replace("/", ".") + "\nclass MisplacedAuth\n"
            )
        errors = verify(self.root)
        self.assertEqual(3, len(errors))
        for owner in owners.values():
            self.assertTrue(any(f"package belongs to {owner}" in error for error in errors))


    def test_sync_domain_sources_cannot_return_to_app(self):
        self.module(":data:sync")
        for family in ("change", "codec", "mapping/stats", "remote", "retry", "runtime", "sanitize", "schedule"):
            package = "moe/ouom/neriplayer/data/sync/" + family
            directory = self.root / "app/src/main/java" / package
            directory.mkdir(parents=True)
            (directory / "Stranded.kt").write_text(
                "package " + package.replace("/", ".") + "\nclass Stranded\n"
            )
        errors = verify(self.root)
        self.assertEqual(8, len(errors))
        self.assertTrue(all("belongs in a library module" in error for error in errors))

    def test_sync_domain_requires_sync_module(self):
        self.module(":data:sync")
        self.module(":core:common")
        directory = self.root / "modules/core/common/src/main/java/moe/ouom/neriplayer/data/sync/runtime"
        directory.mkdir(parents=True)
        (directory / "Misplaced.kt").write_text(
            "package moe.ouom.neriplayer.data.sync.runtime\nclass Misplaced\n"
        )
        self.assertTrue(any("package belongs to :data:sync" in error for error in verify(self.root)))

    def test_entire_sync_implementation_must_stay_outside_app(self):
        self.module(":data:sync")
        for language in ("java", "kotlin"):
            for family in ("data/sync", "data/sync/github", "data/sync/webdav", "data/sync/host",
                           "data/sync/store/state", "data/sync/work", "api/sync/github", "api/sync/webdav"):
                package = "moe/ouom/neriplayer/" + family
                directory = self.root / "app/src/main" / language / package
                directory.mkdir(parents=True)
                (directory / "Stranded.kt").write_text(
                    "package " + package.replace("/", ".") + "\nclass Stranded\n"
                )
        errors = verify(self.root)
        self.assertEqual(16, len(errors))
        self.assertTrue(all("belongs in a library module" in error for error in errors))

    def test_sync_transport_and_credentials_require_their_own_modules(self):
        self.module(":data:repository")
        for family, owner in (("api/sync/github", ":api:sync"),
                              ("data/sync/store/state", ":data:sync-store")):
            package = "moe/ouom/neriplayer/" + family
            directory = self.root / "modules/data/repository/src/main/java" / package
            directory.mkdir(parents=True)
            (directory / "Misplaced.kt").write_text(
                "package " + package.replace("/", ".") + "\nclass Misplaced\n"
            )
            self.assertTrue(any(f"package belongs to {owner}" in error for error in verify(self.root)))

    def test_application_integration_can_remain_in_app(self):
        self.module(":data:sync")
        directory = self.root / "app/src/main/java/moe/ouom/neriplayer/core/integration/sync"
        directory.mkdir(parents=True)
        (directory / "Host.kt").write_text("package moe.ouom.neriplayer.core.integration.sync\nclass Host\n")
        self.assertEqual([], verify(self.root))


if __name__ == "__main__":
    unittest.main()
