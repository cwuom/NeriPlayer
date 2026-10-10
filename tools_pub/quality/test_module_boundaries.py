from pathlib import Path
import tempfile
import unittest

from module_boundaries import verify
from domain_dependencies import check_class_dependencies, load_domains, select_domains


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
        self.registry = self.root / "gradle/owned-modules.txt"
        self.registry.parent.mkdir()
        self.registry.write_text("")

    def module(self, name, dependency=None, source="package sample\nclass Sample\n"):
        self.settings.write_text(self.settings.read_text() + f'includeOwnedLibrary("{name}")\n')
        self.modules.append(name)
        self.registry.write_text("\n".join(self.modules) + "\n")
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
        self.module(":model")
        self.module(":platform", ":model")
        self.assertEqual([], verify(self.root))

    def test_accepts_compile_only_framework_stubs_for_player(self):
        self.module(":playback:runtime")
        self.settings.write_text(self.settings.read_text() + 'include(":hidden-api")\n')
        build_file = self.root / "modules/playback/runtime/build.gradle.kts"
        build_file.write_text(build_file.read_text() +
                              'dependencies { compileOnly(project(":hidden-api")) }\n')
        self.assertEqual([], verify(self.root))

    def test_rejects_packaging_framework_stubs_with_player(self):
        self.module(":playback:runtime", ":hidden-api")
        self.settings.write_text(self.settings.read_text() + 'include(":hidden-api")\n')
        self.assertTrue(any("forbidden domain dependency :hidden-api" in error
                            for error in verify(self.root)))

    def native_module(self):
        self.module(":native")
        directory = self.root / "modules/native"
        (directory / "src/main/java/sample/Sample.kt").unlink()
        (directory / "build.gradle.kts").write_text(
            'plugins { id("build-logic.android.library") }\n'
        )
        cpp = directory / "src/main/cpp"
        cpp.mkdir(parents=True)
        (cpp / "CMakeLists.txt").write_text("project(neri_native LANGUAGES C CXX)\n")
        return directory

    def test_accepts_native_library_without_jvm_sources(self):
        self.native_module()
        self.module(":playback:runtime", ":native")
        self.assertEqual([], verify(self.root))

    def test_rejects_native_implementation_left_in_app(self):
        self.native_module()
        (self.root / "app/src/main/cpp").mkdir(parents=True)
        self.assertTrue(any("native implementation belongs in :native" in error
                            for error in verify(self.root)))

    def test_rejects_native_dependency_on_playback_runtime(self):
        directory = self.native_module()
        self.module(":playback:runtime")
        build_file = directory / "build.gradle.kts"
        build_file.write_text(build_file.read_text() +
                              'dependencies { implementation(project(":playback:runtime")) }\n')
        self.assertTrue(any("forbidden domain dependency" in error
                            for error in verify(self.root)))

    def test_rejects_missing_native_cmake_entrypoint(self):
        directory = self.native_module()
        (directory / "src/main/cpp/CMakeLists.txt").unlink()
        self.assertTrue(any("missing native CMake entrypoint" in error
                            for error in verify(self.root)))

    def test_accepts_model_contract_dependency_from_core_and_api(self):
        self.module(":model")
        self.module(":lyrics", ":model")
        self.module(":platform", ":model")
        self.assertEqual([], verify(self.root))

    def test_rejects_implementation_dependency_from_model(self):
        self.module(":network")
        self.module(":model", ":network")
        self.assertTrue(any("model contracts cannot depend on project implementations" in error
                            for error in verify(self.root)))

    def test_rejects_model_package_outside_model_module(self):
        self.module(":platform")
        directory = self.root / "modules/platform/src/main/java/moe/ouom/neriplayer/api/music/model"
        directory.mkdir(parents=True)
        (directory / "Track.kt").write_text(
            "package moe.ouom.neriplayer.api.music.model\ndata class Track(val id: String)\n"
        )
        self.assertTrue(any("model package belongs to :model" in error for error in verify(self.root)))

    def test_rejects_model_package_left_in_app(self):
        self.module(":model")
        directory = self.root / "app/src/main/java/moe/ouom/neriplayer/core/player/model"
        directory.mkdir(parents=True)
        (directory / "Track.kt").write_text(
            "package moe.ouom.neriplayer.core.player.model\ndata class Track(val id: String)\n"
        )
        self.assertTrue(any("model package belongs to :model" in error for error in verify(self.root)))

    def test_accepts_platform_between_shared_contracts_and_local_data(self):
        self.module(":model")
        self.module(":platform", ":model")
        self.module(":local", ":platform")
        self.assertEqual([], verify(self.root))

    def test_local_storage_calculations_cannot_read_repositories_or_room(self):
        self.module(":local")
        for family in ("accounting", "source", "scan", "cleanup", "policy"):
            directory = self.root / f"modules/local/src/main/java/moe/ouom/neriplayer/data/local/storage/{family}"
            directory.mkdir(parents=True)
            (directory / "Future.kt").write_text(
                f"package moe.ouom.neriplayer.data.local.storage.{family}\n"
                "class Future(val database: moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase)\n"
            )
        errors = verify(self.root)
        self.assertEqual(5, sum("forbidden package dependency" in error for error in errors), errors)

    def test_local_storage_scanner_can_use_models_and_storage_ports(self):
        self.module(":local")
        directory = self.root / "modules/local/src/main/java/moe/ouom/neriplayer/data/local/storage/scan"
        directory.mkdir(parents=True)
        (directory / "Future.kt").write_text(
            "package moe.ouom.neriplayer.data.local.storage.scan\n"
            "import moe.ouom.neriplayer.data.model.storage.FileStats\n"
            "import moe.ouom.neriplayer.data.local.storage.source.StorageUsageSource\n"
            "class Future\n"
        )
        self.assertEqual([], verify(self.root))

    def test_local_storage_calculations_cannot_import_room(self):
        self.platform_source("data/local/storage/accounting", (
            "import androidx.room.RoomDatabase\nclass Future(val database: RoomDatabase)"
        ), module=":local")
        self.assertTrue(any("forbidden storage database dependency" in error
                            for error in verify(self.root)))

    def test_local_storage_calculations_cannot_use_fully_qualified_room(self):
        self.platform_source("data/local/storage/scan", (
            "class Future(val database: androidx.room.RoomDatabase)"
        ), module=":local")
        self.assertTrue(any("forbidden storage database dependency" in error
                            for error in verify(self.root)))

    def test_local_storage_calculations_cannot_import_room_with_a_wildcard(self):
        self.platform_source("data/local/storage/accounting", (
            "import androidx.room.*\nclass Future(val database: RoomDatabase)"
        ), module=":local")
        self.assertTrue(any("forbidden storage database dependency" in error
                            for error in verify(self.root)))

    def test_accepts_only_named_legacy_parcelable_imports(self):
        self.module(":model", source=(
            "package sample\n"
            "import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem\n"
            "class Sample(val video: BiliVideoItem)\n"
        ))
        self.assertEqual([], verify(self.root))

    def test_rejects_host_type_next_to_legacy_parcelable(self):
        self.module(":model", source=(
            "package sample\n"
            "import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliPlaylistDetailViewModel\n"
            "class Sample\n"
        ))
        self.assertTrue(any("model contract imports implementation" in error
                            for error in verify(self.root)))

    def test_rejects_local_dependency_from_platform(self):
        self.module(":model")
        self.module(":local", ":model")
        self.module(":platform", ":local")
        self.assertTrue(any("forbidden domain dependency" in error for error in verify(self.root)))

    def test_rejects_api_dependency_from_core(self):
        self.module(":platform")
        self.module(":common", ":platform")
        self.assertTrue(any("forbidden domain dependency" in error for error in verify(self.root)))

    def test_rejects_api_implementation_inside_data_module(self):
        self.module(":local")
        base = self.root / "modules/local/src/main/java/moe/ouom/neriplayer"
        for package in ("core/api/music", "api/music"):
            path = base / package
            path.mkdir(parents=True)
            (path / "Client.kt").write_text(
                "package moe.ouom.neriplayer." + package.replace("/", ".") + "\nclass Client\n"
            )
        errors = verify(self.root)
        self.assertEqual(2, len(errors))
        self.assertTrue(all("API implementation belongs in its registered platform or transport module" in error
                            for error in errors))

    def test_rejects_shared_model_in_common_module(self):
        self.module(":common")
        directory = self.root / "modules/common/src/main/java/moe/ouom/neriplayer/data/model/auth"
        directory.mkdir(parents=True)
        (directory / "AuthState.kt").write_text(
            "package moe.ouom.neriplayer.data.model.auth\nenum class AuthState { Missing }\n"
        )
        errors = verify(self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("model package belongs to :model", errors[0])

    def test_accepts_shared_model_in_model_module(self):
        self.module(":model")
        directory = self.root / "modules/model/src/main/java/moe/ouom/neriplayer/data/model/auth"
        directory.mkdir(parents=True)
        (directory / "AuthState.kt").write_text(
            "package moe.ouom.neriplayer.data.model.auth\nenum class AuthState { Missing }\n"
        )
        self.assertEqual([], verify(self.root))

    def test_rejects_application_and_data_dependencies_from_core(self):
        self.module(":platform", ":app")
        self.module(":common", ":platform")
        errors = verify(self.root)
        self.assertEqual(2, len(errors))
        self.assertTrue(all("forbidden domain dependency" in error for error in errors))

    def test_rejects_cycle(self):
        self.module(":platform", ":local")
        self.module(":local", ":platform")
        self.assertTrue(any("Dependency cycle" in error for error in verify(self.root)))

    def test_rejects_app_import_and_oversized_source(self):
        self.module(":platform", source=(
            "package sample\nimport moe.ouom.neriplayer.core.di.AppContainer\n" + "\n" * 1998
        ))
        errors = verify(self.root)
        self.assertTrue(any("application dependency" in error for error in errors))
        self.assertTrue(any("2000 lines" in error for error in errors))

    def test_rejects_undeclared_dependency(self):
        self.module(":model", ":missing")
        self.assertTrue(any("undeclared dependency" in error for error in verify(self.root)))

    def test_rejects_missing_coverage_registration(self):
        self.module(":model")
        self.app.write_text('val ownedLibraryPaths = listOf()\n')
        self.assertTrue(any("coverage registry" in error for error in verify(self.root)))

    def test_rejects_library_outside_modules_directory(self):
        self.module(":model")
        misplaced = self.root / "outside/model"
        misplaced.parent.mkdir()
        (self.root / "modules/model").rename(misplaced)
        self.assertTrue(any("missing build.gradle.kts" in error for error in verify(self.root)))

    def test_rejects_unregistered_library(self):
        self.module(":model")
        extra = self.root / "modules/missing"
        extra.mkdir(parents=True)
        (extra / "build.gradle.kts").write_text("")
        self.assertTrue(any("unregistered library" in error for error in verify(self.root)))

    def test_rejects_package_directory_mismatch(self):
        self.module(":model", source="package different\nclass Sample\n")
        self.assertTrue(any("package directory mismatch" in error for error in verify(self.root)))

    def test_accepts_indented_package_declaration(self):
        self.module(":model", source=" package sample\nclass Sample\n")
        self.assertEqual([], verify(self.root))

    def test_rejects_test_package_directory_mismatch(self):
        self.module(":model")
        directory = self.root / "modules/model/src/test/java/sample"
        directory.mkdir(parents=True)
        (directory / "SampleTest.kt").write_text("package wrong\nclass SampleTest\n")
        self.assertTrue(any("package directory mismatch" in error for error in verify(self.root)))

    def test_rejects_crowded_library_directory(self):
        self.module(":model")
        directory = self.root / "modules/model/src/main/java/sample"
        for index in range(16):
            (directory / f"Extra{index}.kt").write_text(f"package sample\nclass Extra{index}\n")
        self.assertTrue(any("17 source files" in error for error in verify(self.root)))

    def test_rejects_crowded_app_family(self):
        self.module(":model")
        directory = self.root / "app/src/main/java/moe/ouom/neriplayer/core/player/download"
        directory.mkdir(parents=True)
        for index in range(17):
            (directory / f"Extra{index}.kt").write_text(
                f"package moe.ouom.neriplayer.core.player.download\nclass Extra{index}\n"
            )
        self.assertTrue(any("17 source files" in error for error in verify(self.root)))

    def test_rejects_production_sources_in_migrated_app_packages(self):
        self.module(":model")
        for language in ("java", "kotlin"):
            for family in ("core/api/search", "core/api/lyrics", "lyrics/parser", "data/sync/merge"):
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
        self.module(":model")
        directory = self.root / "app/src/androidTest/java/moe/ouom/neriplayer/core/api/lyrics"
        directory.mkdir(parents=True)
        (directory / "HostTest.kt").write_text(
            "package moe.ouom.neriplayer.core.api.lyrics\nclass HostTest\n"
        )
        self.assertEqual([], verify(self.root))

    def test_rejects_database_infrastructure_left_in_app(self):
        self.module(":database")
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
        self.module(":database")
        self.module(":platform")
        directory = self.root / "modules/platform/src/main/java/moe/ouom/neriplayer/data/local/database/dao"
        directory.mkdir(parents=True)
        (directory / "MisplacedDao.kt").write_text(
            "package moe.ouom.neriplayer.data.local.database.dao\ninterface MisplacedDao\n"
        )
        self.assertTrue(any("package belongs to :database" in error for error in verify(self.root)))

    def test_rejects_database_stores_left_in_app(self):
        self.module(":database")
        directory = self.root / "app/src/main/java/moe/ouom/neriplayer/data/local/database/store"
        directory.mkdir(parents=True)
        (directory / "HostStore.kt").write_text(
            "package moe.ouom.neriplayer.data.local.database.store\nclass HostStore\n"
        )
        self.assertTrue(any("belongs in a library module" in error for error in verify(self.root)))

    def test_all_data_implementations_must_stay_outside_app(self):
        self.module(":local")
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
        self.module(":local")
        self.module(":platform")
        for family in ("netease/auth", "bilibili/auth", "youtube/auth"):
            package = "moe/ouom/neriplayer/platform/" + family
            directory = self.root / "modules/local/src/main/java" / package
            directory.mkdir(parents=True)
            (directory / "MisplacedAuth.kt").write_text(
                "package " + package.replace("/", ".") + "\nclass MisplacedAuth\n"
            )
        errors = verify(self.root)
        self.assertEqual(3, len(errors))
        self.assertTrue(all("package belongs to :platform" in error for error in errors))


    def test_listen_together_runtime_package_requires_its_own_module(self):
        self.module(":listentogether")
        self.module(":common")
        directory = self.root / "modules/common/src/main/java/moe/ouom/neriplayer/data/ltw/session"
        directory.mkdir(parents=True)
        (directory / "Session.kt").write_text(
            "package moe.ouom.neriplayer.data.ltw.session\nclass Session\n"
        )
        self.assertTrue(any("package belongs to :listentogether" in error for error in verify(self.root)))

    def test_listen_together_runtime_cannot_return_to_app(self):
        self.module(":listentogether")
        directory = self.root / "app/src/main/java/moe/ouom/neriplayer/data/ltw/session"
        directory.mkdir(parents=True)
        (directory / "Session.kt").write_text(
            "package moe.ouom.neriplayer.data.ltw.session\nclass Session\n"
        )
        self.assertTrue(any("belongs in a library module" in error for error in verify(self.root)))

    def test_entire_legacy_listen_together_tree_must_stay_outside_app(self):
        self.module(":listentogether")
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


    def test_sync_domain_sources_cannot_return_to_app(self):
        self.module(":sync")
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
        self.module(":sync")
        self.module(":common")
        directory = self.root / "modules/common/src/main/java/moe/ouom/neriplayer/data/sync/runtime"
        directory.mkdir(parents=True)
        (directory / "Misplaced.kt").write_text(
            "package moe.ouom.neriplayer.data.sync.runtime\nclass Misplaced\n"
        )
        self.assertTrue(any("package belongs to :sync" in error for error in verify(self.root)))

    def test_entire_sync_implementation_must_stay_outside_app(self):
        self.module(":sync")
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
        self.module(":local")
        for family, owner in (("api/sync/github", ":sync"),
                              ("data/sync/store/state", ":sync")):
            package = "moe/ouom/neriplayer/" + family
            directory = self.root / "modules/local/src/main/java" / package
            directory.mkdir(parents=True)
            (directory / "Misplaced.kt").write_text(
                "package " + package.replace("/", ".") + "\nclass Misplaced\n"
            )
            self.assertTrue(any(f"package belongs to {owner}" in error for error in verify(self.root)))

    def test_application_integration_can_remain_in_app(self):
        self.module(":sync")
        directory = self.root / "app/src/main/java/moe/ouom/neriplayer/core/integration/sync"
        directory.mkdir(parents=True)
        (directory / "Host.kt").write_text("package moe.ouom.neriplayer.core.integration.sync\nclass Host\n")
        self.assertEqual([], verify(self.root))

    def test_registry_registers_top_level_and_nested_domains(self):
        self.module(":model")
        self.module(":lyrics", ":model")
        self.module(":platform", ":lyrics")
        self.module(":playback:logic", ":model")
        self.settings.write_text(
            'include(":app")\n'
            'val ownedLibraryPaths = file("gradle/owned-modules.txt").readLines().filter { it.isNotBlank() }\n'
            'ownedLibraryPaths.forEach(::includeOwnedLibrary)\n'
        )
        self.app.write_text(
            'val ownedLibraryPaths = rootProject.file("gradle/owned-modules.txt").readLines().filter { it.isNotBlank() }\n'
        )
        self.assertEqual([], verify(self.root))

    def test_app_registry_reader_accepts_multiline_gradle_chain(self):
        self.module(":model")
        self.app.write_text(
            'val ownedLibraryPaths = rootProject.file("gradle/owned-modules.txt")\n'
            '    .readLines().filter { it.isNotBlank() }\n'
        )
        self.assertEqual([], verify(self.root))

    def canonical_dynamic_registry(self):
        self.module(":model")
        self.module(":lyrics")
        self.settings.write_text(
            'include(":app")\n'
            'val ownedLibraryPaths = file("gradle/owned-modules.txt").readLines().filter { it.isNotBlank() }\n'
            'ownedLibraryPaths.forEach(::includeOwnedLibrary)\n'
        )
        self.app.write_text(
            'val ownedLibraryPaths = rootProject.file("gradle/owned-modules.txt")\n'
            '    .readLines().filter { it.isNotBlank() }\n'
        )

    def test_app_registry_rejects_filtered_readers(self):
        self.canonical_dynamic_registry()
        original = self.app.read_text()
        expressions = (
            original.replace('it.isNotBlank()', 'it.isNotBlank() && it != ":lyrics"'),
            original.rstrip() + '.filter { it != ":lyrics" }\n',
            original + '    .filter { it != ":lyrics" }\n',
            original + '    .drop(1)\n',
            original + '    .map { it.substringBeforeLast(\':\') }\n',
        )
        for expression in expressions:
            with self.subTest(expression=expression):
                self.app.write_text(expression)
                errors = verify(self.root)
                self.assertTrue(any("unsupported owned module registry expression" in error for error in errors))
                self.assertTrue(any("App coverage registry differs" in error for error in errors))

    def test_settings_registry_rejects_filtered_readers(self):
        self.canonical_dynamic_registry()
        original = self.settings.read_text()
        expressions = (
            original.replace('it.isNotBlank()', 'it.isNotBlank() && it != ":lyrics"'),
            original.replace(' }\nownedLibraryPaths', ' }.filter { it != ":lyrics" }\nownedLibraryPaths'),
            original.replace(' }\nownedLibraryPaths', ' }\n    .filter { it != ":lyrics" }\nownedLibraryPaths'),
            original.replace(' }\nownedLibraryPaths', ' }\n    .drop(1)\nownedLibraryPaths'),
        )
        for expression in expressions:
            with self.subTest(expression=expression):
                self.settings.write_text(expression)
                errors = verify(self.root)
                self.assertTrue(any("unsupported owned module registry expression" in error for error in errors))
                self.assertTrue(any(":lyrics: must register" in error for error in errors))

    def test_settings_registry_rejects_filtered_registration_chain(self):
        self.canonical_dynamic_registry()
        original = self.settings.read_text()
        for chain in ('ownedLibraryPaths.filter { it != ":lyrics" }.forEach(::includeOwnedLibrary)',
                      'ownedLibraryPaths\n    .drop(1).forEach(::includeOwnedLibrary)',
                      'ownedLibraryPaths.forEach(::includeOwnedLibrary)\n    .drop(1)'):
            with self.subTest(chain=chain):
                self.settings.write_text(original.replace('ownedLibraryPaths.forEach(::includeOwnedLibrary)', chain))
                self.assertTrue(any("unsupported owned module registration chain" in error
                                    for error in verify(self.root)))

    def test_canonical_dynamic_registry_keeps_parent_directory_mapping(self):
        self.canonical_dynamic_registry()
        self.settings.write_text(self.settings.read_text() + (
            "ownedLibraryPaths.filter { it.count { character -> character == ':' } > 1 }\n"
            "    .map { it.substringBeforeLast(':') }.distinct().forEach { parent ->\n"
            "        project(parent).projectDir = file(\"modules/${parent.drop(1).replace(':', '/')}\")\n"
            "    }\n"
        ))
        self.assertEqual([], verify(self.root))

    def test_literal_app_registry_rejects_trailing_filter(self):
        self.module(":model")
        self.app.write_text('val ownedLibraryPaths = listOf(":model")\n    .drop(1)\n')
        errors = verify(self.root)
        self.assertTrue(any("unsupported owned module registry expression" in error for error in errors))
        self.assertTrue(any("App coverage registry differs" in error for error in errors))

    def test_local_keeps_its_settings_ksp_processor_dependency(self):
        self.module(":local")
        self.settings.write_text(self.settings.read_text() + 'include(":ksp-processor")\n')
        script = self.root / "modules/local/build.gradle.kts"
        script.write_text(script.read_text() + 'dependencies { ksp(project(":ksp-processor")) }\n')
        self.assertEqual([], verify(self.root))

    def test_download_runtime_keeps_its_sync_regression_test_dependency(self):
        self.module(":sync")
        self.module(":download:runtime")
        script = self.root / "modules/download/runtime/build.gradle.kts"
        script.write_text(script.read_text() + 'dependencies { testImplementation(project(":sync")) }\n')
        self.assertEqual([], verify(self.root))

    def test_download_test_dependency_does_not_authorize_sync_in_production(self):
        self.module(":sync")
        self.module(":download:runtime")
        script = self.root / "modules/download/runtime/build.gradle.kts"
        script.write_text(script.read_text() + (
            'dependencies { testImplementation(project(":sync"))\n'
            'implementation(project(":sync")) }\n'
        ))
        self.assertTrue(any("forbidden domain dependency :sync" in error for error in verify(self.root)))

    def test_missing_registry_fails_closed(self):
        self.module(":model")
        self.registry.unlink()
        self.assertTrue(any("missing owned module registry" in error for error in verify(self.root)))

    def test_duplicate_registry_entry_is_rejected(self):
        self.module(":model")
        self.registry.write_text(":model\n:model\n")
        self.assertTrue(any("duplicate owned module" in error for error in verify(self.root)))

    def test_invalid_registry_path_is_rejected(self):
        self.module(":model")
        for name in ("model", ":../model", ":lyrics:runtime:extra"):
            with self.subTest(name=name):
                self.registry.write_text(":model\n" + name + "\n")
                self.assertTrue(any("invalid owned module path" in error for error in verify(self.root)))

    def test_declared_owned_library_missing_from_registry_is_rejected(self):
        self.module(":model")
        self.module(":network")
        self.registry.write_text(":model\n")
        self.assertTrue(any(":network: unregistered library" in error for error in verify(self.root)))

    def test_unregistered_nested_build_node_is_rejected(self):
        self.module(":model")
        extra = self.root / "modules/lyrics/future"
        extra.mkdir(parents=True)
        (extra / "build.gradle.kts").write_text("")
        self.assertTrue(any(":lyrics:future: unregistered library" in error for error in verify(self.root)))

    def test_generated_build_scripts_in_gradle_caches_are_ignored(self):
        self.module(":model")
        self.module(":lyrics", ":model")
        self.module(":playback:logic", ":model")
        for module in ("model", "lyrics", "playback/logic", "playback"):
            for cache in ("build", ".gradle"):
                directory = self.root / "modules" / module / cache / "tmp/test-kit/fixture"
                directory.mkdir(parents=True)
                (directory / "build.gradle.kts").write_text('include(":fixture")\n')
        self.assertEqual([], verify(self.root))

    def test_unregistered_third_level_build_node_is_rejected(self):
        self.module(":model")
        extra = self.root / "modules/lyrics/future/implementation"
        extra.mkdir(parents=True)
        (extra / "build.gradle.kts").write_text("")
        self.assertTrue(any(":lyrics:future:implementation: unregistered library" in error
                            for error in verify(self.root)))

    def test_registered_library_named_build_still_requires_domain_policy(self):
        self.module(":build")
        errors = verify(self.root)
        self.assertTrue(any(":build: no explicit domain dependency policy" in error for error in errors))
        self.assertFalse(any("unregistered library" in error for error in errors))

    def test_unregistered_top_level_library_named_build_is_rejected(self):
        self.module(":model")
        extra = self.root / "modules/build"
        extra.mkdir(parents=True)
        (extra / "build.gradle.kts").write_text("")
        self.assertTrue(any(":build: unregistered library" in error for error in verify(self.root)))

    def test_unregistered_nested_library_named_build_is_rejected(self):
        self.module(":playback:logic")
        extra = self.root / "modules/playback/build"
        extra.mkdir(parents=True)
        (extra / "build.gradle.kts").write_text("")
        self.assertTrue(any(":playback:build: unregistered library" in error for error in verify(self.root)))

    def test_cache_under_unknown_project_is_not_ignored(self):
        self.module(":model")
        extra = self.root / "modules/unknown/build/tmp/fixture"
        extra.mkdir(parents=True)
        (extra / "build.gradle.kts").write_text("")
        self.assertTrue(any(":unknown:build:tmp:fixture: unregistered library" in error
                            for error in verify(self.root)))

    def test_api_client_requires_platform_module(self):
        self.module(":local")
        directory = self.root / "modules/local/src/main/java/moe/ouom/neriplayer/platform/bilibili/api/client"
        directory.mkdir(parents=True)
        (directory / "BiliClient.kt").write_text(
            "package moe.ouom.neriplayer.platform.bilibili.api.client\nclass BiliClient\n"
        )
        self.assertTrue(any("package belongs to :platform" in error for error in verify(self.root)))

    def test_sync_and_listen_together_keep_their_transport_packages(self):
        for module, family in ((":sync", "api/sync/github"), (":listentogether", "api/ltw/http")):
            self.module(module)
            directory = self.root / "modules" / module[1:] / "src/main/java/moe/ouom/neriplayer" / family
            directory.mkdir(parents=True)
            (directory / "Transport.kt").write_text(
                "package moe.ouom.neriplayer." + family.replace("/", ".") + "\nclass Transport\n"
            )
        self.assertEqual([], verify(self.root))

    def test_lyrics_cannot_depend_on_platform(self):
        self.module(":platform")
        self.module(":lyrics", ":platform")
        self.assertTrue(any("forbidden domain dependency :platform" in error for error in verify(self.root)))

    def test_metadata_can_depend_on_lyrics_with_source_rules(self):
        self.module(":lyrics")
        self.module(":platform", ":lyrics")
        self.assertEqual([], verify(self.root))

    def test_lyric_default_offset_requires_parser_module(self):
        self.module(":local")
        self.module(":lyrics")
        package = "moe/ouom/neriplayer/lyrics/offset"
        directory = self.root / "modules/local/src/main/java" / package
        directory.mkdir(parents=True)
        source = directory / "LyricDefaultOffset.kt"
        source.write_text(
            "package moe.ouom.neriplayer.lyrics.offset\nfun defaultOffset() = 0\n"
        )
        self.assertTrue(any("package belongs to :lyrics" in error for error in verify(self.root)))
        target = self.root / "modules/lyrics/src/main/java" / package
        target.mkdir(parents=True)
        source.rename(target / source.name)
        self.assertEqual([], verify(self.root))

    def test_other_lyric_settings_remain_local(self):
        self.module(":local")
        self.module(":lyrics")
        package = "moe/ouom/neriplayer/data/settings/lyrics"
        directory = self.root / "modules/local/src/main/java" / package
        directory.mkdir(parents=True)
        source = directory / "LyricDisplaySettings.kt"
        source.write_text(
            "package moe.ouom.neriplayer.data.settings.lyrics\nclass LyricDisplaySettings\n"
        )
        self.assertEqual([], verify(self.root))
        target = self.root / "modules/lyrics/src/main/java" / package
        target.mkdir(parents=True)
        source.rename(target / source.name)
        self.assertTrue(any("package belongs to :local" in error for error in verify(self.root)))

    def platform_source(self, family, reference, module=":platform"):
        self.module(module)
        directory = (self.root / "modules" / module[1:].replace(":", "/") /
                     "src/main/java/moe/ouom/neriplayer" / family)
        directory.mkdir(parents=True)
        (directory / "Future.kt").write_text(
            "package moe.ouom.neriplayer." + family.replace("/", ".") + "\n" + reference + "\n"
        )

    def test_merged_clients_cannot_import_account_repository_or_other_platform(self):
        self.module(":platform")
        for family, reference in (
            ("platform/bilibili/api/client", "platform.bilibili.auth.BiliCookieRepository"),
            ("platform/netease/api/client", "platform.netease.NeteasePlaylistCacheRepository"),
            ("platform/youtube/api/client", "platform.bilibili.api.client.BiliClient"),
        ):
            directory = self.root / "modules/platform/src/main/java/moe/ouom/neriplayer" / family
            directory.mkdir(parents=True)
            (directory / "Future.kt").write_text(
                "package moe.ouom.neriplayer." + family.replace("/", ".") + "\n"
                "import moe.ouom.neriplayer." + reference + "\nclass Future\n"
            )
        errors = verify(self.root)
        self.assertEqual(3, sum("forbidden package dependency" in error for error in errors), errors)

    def test_merged_client_fully_qualified_reference_cannot_bypass_import_check(self):
        self.platform_source("platform/bilibili/api/client", (
            "class Future(val cache: moe.ouom.neriplayer.platform.bilibili.BiliPlaylistRepository)"
        ))
        self.assertTrue(any("forbidden package dependency" in error for error in verify(self.root)))

    def test_metadata_client_cannot_reach_remote_repository_or_lyric_output(self):
        self.module(":platform")
        for index, reference in enumerate((
            "platform.lyrics.repository.EditableLyricsMatcher", "lyrics.lyricon.LyriconManager",
            "lyrics.output.LyriconPlaybackOutput", "data.local.database.NeriUserDataDatabase",
        )):
            directory = self.root / "modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/api/client"
            directory.mkdir(parents=True, exist_ok=True)
            (directory / f"Future{index}.kt").write_text(
                "package moe.ouom.neriplayer.platform.lyrics.api.client\n"
                "import moe.ouom.neriplayer." + reference + f"\nclass Future{index}\n"
            )
        errors = verify(self.root)
        self.assertEqual(4, sum("forbidden package dependency" in error for error in errors), errors)

    def test_platform_repositories_cannot_cross_another_platform_repository(self):
        self.platform_source("platform/bilibili", (
            "import moe.ouom.neriplayer.platform.youtube.playlist.YouTubeMusicRepository\nclass Future"
        ))
        self.assertTrue(any("forbidden package dependency" in error for error in verify(self.root)))

    def test_lyrics_output_cannot_reach_provider_matching(self):
        self.platform_source("lyrics/output", (
            "import moe.ouom.neriplayer.platform.lyrics.repository.EditableLyricsMatcher\nclass Future"
        ), module=":lyrics")
        self.assertTrue(any("forbidden package dependency" in error for error in verify(self.root)))

    def test_provider_matching_cannot_reach_lyricon_or_superlyric_sdk(self):
        self.module(":platform")
        for index, reference in enumerate((
            "moe.ouom.neriplayer.lyrics.output.LyriconPlaybackOutput",
            "io.github.proify.lyricon.provider.LyriconProvider", "com.hchen.superlyricapi.SuperLyricHelper",
        )):
            directory = self.root / "modules/platform/src/main/java/moe/ouom/neriplayer/platform/lyrics/matching"
            directory.mkdir(parents=True, exist_ok=True)
            (directory / f"Future{index}.kt").write_text(
                "package moe.ouom.neriplayer.platform.lyrics.matching\n"
                f"import {reference}\nclass Future{index}\n"
            )
        errors = verify(self.root)
        self.assertEqual(3, sum("forbidden package dependency" in error for error in errors), errors)

    def test_bytecode_domains_protect_future_clients_and_repositories(self):
        root = Path(__file__).resolve().parents[2]
        names = (
            "platform-bilibili-client", "platform-netease-client", "platform-youtube-client",
            "platform-lyrics-client", "platform-search-client", "platform-bilibili-repository",
            "platform-bilibili-auth", "platform-netease-repository", "platform-netease-auth",
            "platform-youtube-repository", "platform-comments", "platform-lyrics-provider",
            "lyrics-output", "lyrics-lyricon",
        )
        domains = [domain for domain in load_domains(root / "config/quality/domain-dependencies.json")
                   if domain["name"] in names]
        self.assertEqual(set(names), {domain["name"] for domain in domains})
        classes = {domain["package"] + "nested.Future" for domain in domains}
        selected = select_domains(classes, domains)
        output = "\n".join(
            f"   {owner} -> moe.ouom.neriplayer.core.player.PlayerManager module"
            for owner in sorted(classes)
        )
        _, errors = check_class_dependencies(output, selected)
        self.assertEqual(len(names), len(errors), errors)

    def test_bytecode_metadata_and_provider_cannot_reach_lyrics_output(self):
        root = Path(__file__).resolve().parents[2]
        names = ("platform-lyrics-client", "platform-search-client", "platform-lyrics-provider")
        domains = [domain for domain in load_domains(root / "config/quality/domain-dependencies.json")
                   if domain["name"] in names]
        self.assertEqual(len(names), len(domains))
        classes = {domain["package"] + "nested.Future" for domain in domains}
        selected = select_domains(classes, domains)
        for target in ("moe.ouom.neriplayer.lyrics.output.LyriconPlaybackOutput",
                       "moe.ouom.neriplayer.lyrics.lyricon.LyriconManager"):
            with self.subTest(target=target):
                _, errors = check_class_dependencies(
                    "\n".join(f"   {owner} -> {target} module" for owner in sorted(classes)), selected
                )
                self.assertEqual(len(names), len(errors), errors)

    def test_bytecode_platform_clients_preserve_original_shared_models(self):
        root = Path(__file__).resolve().parents[2]
        names = ("platform-bilibili-client", "platform-netease-client", "platform-youtube-client")
        domains = [domain for domain in load_domains(root / "config/quality/domain-dependencies.json")
                   if domain["name"] in names]
        self.assertEqual(len(names), len(domains))
        classes = {domain["package"] + "nested.Future" for domain in domains}
        selected = select_domains(classes, domains)
        output = "\n".join(
            f"   {owner} -> {dependency} module"
            for owner, domain in selected.items()
            for dependency in domain["allowed_classes"]
            if dependency.startswith("moe.ouom.neriplayer.data.model.")
        )
        _, errors = check_class_dependencies(output, selected)
        self.assertEqual([], errors)

    def test_bytecode_clients_reject_same_platform_state_and_foreign_clients(self):
        root = Path(__file__).resolve().parents[2]
        domains = {domain["name"]: domain for domain in load_domains(root / "config/quality/domain-dependencies.json")}
        for name, dependency in (
            ("platform-bilibili-client", "platform.bilibili.auth.BiliCookieRepository"),
            ("platform-netease-client", "platform.netease.NeteasePlaylistCacheRepository"),
            ("platform-youtube-client", "platform.bilibili.api.client.BiliClient"),
            ("platform-bilibili-repository", "platform.youtube.playlist.YouTubeMusicRepository"),
            ("platform-netease-repository", "platform.bilibili.auth.BiliCookieRepository"),
            ("platform-youtube-repository", "platform.netease.NeteasePlaylistCacheRepository"),
        ):
            with self.subTest(name=name, dependency=dependency):
                domain = domains[name]
                owner = domain["package"] + "nested.Future"
                selected = select_domains({owner}, [domain])
                _, errors = check_class_dependencies(
                    f"   {owner} -> moe.ouom.neriplayer.{dependency} module", selected
                )
                self.assertEqual(1, len(errors), errors)

    def test_parser_and_shared_offset_cannot_reference_lyric_output_sdk(self):
        self.module(":lyrics")
        for family, name in (("lyrics/parser", "FutureParser"), ("lyrics/offset", "LyricDefaultOffset")):
            directory = self.root / "modules/lyrics/src/main/java/moe/ouom/neriplayer" / family
            directory.mkdir(parents=True)
            (directory / f"{name}.kt").write_text(
                "package moe.ouom.neriplayer." + family.replace("/", ".") + "\n"
                "import io.github.proify.lyricon.provider.LyriconProvider\n"
                "import moe.ouom.neriplayer.lyrics.lyricon.LyriconManager\n"
                f"class {name}\n"
            )
        errors = verify(self.root)
        self.assertEqual(4, sum("forbidden package dependency" in error for error in errors), errors)

    def test_parser_bytecode_cannot_reference_output_or_provider(self):
        root = Path(__file__).resolve().parents[2]
        domains = [domain for domain in load_domains(root / "config/quality/domain-dependencies.json")
                   if domain["name"] == "lyrics-parser"]
        self.assertEqual(1, len(domains))
        owner = domains[0]["package"] + "nested.Future"
        selected = select_domains({owner}, domains)
        for dependency in ("moe.ouom.neriplayer.platform.lyrics.repository.EditableLyricsMatcher",
                           "moe.ouom.neriplayer.lyrics.lyricon.LyriconManager",
                           "io.github.proify.lyricon.provider.LyriconProvider"):
            with self.subTest(dependency=dependency):
                _, errors = check_class_dependencies(f"   {owner} -> {dependency} module", selected)
                self.assertEqual(1, len(errors), errors)

    def test_metadata_parser_helpers_remain_accessible_without_output(self):
        self.platform_source("platform/search/api/client", (
            "import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient\n"
            "import moe.ouom.neriplayer.lyrics.parser.normalizeLegacyLrcTimestamps\n"
            "import moe.ouom.neriplayer.data.model.music.SongDetails\nclass Future"
        ))
        self.assertEqual([], verify(self.root))

    def test_source_guard_ignores_comments_but_keeps_qualified_code_after_url(self):
        self.platform_source("platform/bilibili/api/client", (
            "// old location: moe.ouom.neriplayer.core.player.PlayerManager\n"
            "/* old repository: moe.ouom.neriplayer.platform.bilibili.auth.BiliCookieRepository */\n"
            'val url = "https://example.com"\nclass Future'
        ))
        self.assertEqual([], verify(self.root))
        source = self.root / "modules/platform/src/main/java/moe/ouom/neriplayer/platform/bilibili/api/client/Future.kt"
        source.write_text(source.read_text() + (
            "\nfun load() = moe.ouom.neriplayer.platform.bilibili.BiliPlaylistRepository()\n"
        ))
        self.assertTrue(any("forbidden package dependency" in error for error in verify(self.root)))

    def test_bili_room_store_keeps_platform_boundary_after_file_rename(self):
        self.platform_source("platform/bilibili/skip/storage", (
            "import moe.ouom.neriplayer.platform.netease.NeteasePlaylistCacheRepository\n"
            "class BiliVideoSkipRoomStore"
        ))
        self.assertTrue(any("forbidden package dependency" in error for error in verify(self.root)))


if __name__ == "__main__":
    unittest.main()
