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
        self.settings.write_text(self.settings.read_text() + f'include("{name}")\n')
        self.modules.append(name)
        self.app.write_text('val ownedLibraryPaths = listOf(' +
                            ', '.join(f'"{module}"' for module in self.modules) + ')\n')
        directory = self.root / name.lstrip(":").replace(":", "/")
        sources = directory / "src/main/java"
        sources.mkdir(parents=True)
        (sources / "Sample.kt").write_text(source)
        script = 'plugins { id("build-logic.android.feature-library") }\n'
        if dependency:
            script += f'dependencies {{ implementation(project("{dependency}")) }}\n'
        (directory / "build.gradle.kts").write_text(script)

    def test_accepts_downward_dependencies(self):
        self.module(":core:model")
        self.module(":data:music", ":core:model")
        self.assertEqual([], verify(self.root))

    def test_rejects_application_and_data_dependencies_from_core(self):
        self.module(":data:music", ":app")
        self.module(":core:model", ":data:music")
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
        self.module(":core:model", ":missing")
        self.assertTrue(any("undeclared dependency" in error for error in verify(self.root)))

    def test_rejects_missing_coverage_registration(self):
        self.module(":core:model")
        self.app.write_text('val ownedLibraryPaths = listOf()\n')
        self.assertTrue(any("coverage registry" in error for error in verify(self.root)))


if __name__ == "__main__":
    unittest.main()
