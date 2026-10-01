from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest

from module_boundaries import verify


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

DOWNLOAD_RUNTIME_RULE_FAMILIES = (
    "core/download/host",
    "core/integration/download",
    "core/download/generation",
    "core/download/execution/notification/refresh",
    "core/download/execution/scheduling/deadline",
    "core/download/execution/scheduling/ids",
    "core/download/execution/host/isolation",
    "core/download/integration/legacy",
    "core/download/presentation/progress",
    "core/download/storage/migration/access",
)


class DownloadModuleBoundariesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        modules = (":model", ":download:logic", ":common", ":download:runtime")
        (self.root / "settings.gradle.kts").write_text(
            'include(":app")\n'
            + "".join(f'includeOwnedLibrary("{name}")\n' for name in modules)
        )
        registry = self.root / "gradle/owned-modules.txt"
        registry.parent.mkdir()
        registry.write_text("\n".join(modules) + "\n")
        app = self.root / "app"
        app.mkdir()
        (app / "build.gradle.kts").write_text(
            "val ownedLibraryPaths = listOf("
            + ", ".join(f'"{name}"' for name in modules) + ")\n"
        )
        for name in modules:
            module = self.root / "modules" / name.lstrip(":").replace(":", "/")
            self.source(module, "sample", "Sample")
            (module / "build.gradle.kts").write_text(
                'plugins { id("build-logic.android.feature-library") }\n'
            )

    def source(self, module, family, name, language="java"):
        package = "moe/ouom/neriplayer/" + family if family != "sample" else family
        directory = module / "src/main" / language / package
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f"{name}.kt").write_text(
            "package " + package.replace("/", ".") + f"\nclass {name}\n"
        )

    def test_rejects_download_rules_reintroduced_in_app(self):
        for language in ("java", "kotlin"):
            for family in DOWNLOAD_RULE_FAMILIES:
                self.source(self.root / "app", family + "/nested", "Stranded", language)
        errors = verify(self.root)
        self.assertEqual(2 * len(DOWNLOAD_RULE_FAMILIES), len(errors))
        self.assertTrue(all("belongs in a library module" in error for error in errors))

    def test_rejects_download_rules_in_another_library(self):
        for family in DOWNLOAD_RULE_FAMILIES:
            self.source(self.root / "modules/common", family + "/nested", "Misplaced")
        errors = verify(self.root)
        self.assertEqual(len(DOWNLOAD_RULE_FAMILIES), len(errors))
        self.assertTrue(all("package belongs to :download:logic" in error for error in errors))

    def test_accepts_download_rules_in_their_library(self):
        for family in DOWNLOAD_RULE_FAMILIES:
            self.source(self.root / "modules/download/logic", family + "/nested", "Owned")
        self.assertEqual([], verify(self.root))

    def test_rejects_runtime_and_storage_reintroduced_in_app(self):
        for family in ("core/download/storage", "core/download/execution/persistence"):
            self.source(self.root / "app", family, "HostAdapter")
        errors = verify(self.root)
        self.assertEqual(2, len(errors))
        self.assertTrue(all("belongs in a library module" in error for error in errors))

    def test_accepts_download_runtime_in_its_feature(self):
        for family in ("core/download/execution/persistence", "core/player/download/runtime"):
            self.source(self.root / "modules/download/runtime", family, "Runtime")
        self.assertEqual([], verify(self.root))

    def test_rejects_runtime_in_other_features(self):
        self.source(self.root / "modules/common", "core/player/download/runtime", "Misplaced")
        errors = verify(self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("package belongs to :download:runtime", errors[0])

    def test_new_nested_download_file_automatically_fails_above_crap_threshold(self):
        repository = Path(__file__).resolve().parents[2]
        config = json.loads((repository / "config/quality/crap-scope.json").read_text())
        families = (*DOWNLOAD_RULE_FAMILIES, *DOWNLOAD_RUNTIME_RULE_FAMILIES, "data/model/download/execution")
        patterns = [f"moe/ouom/neriplayer/{family}/**/*.kt" for family in families]
        self.assertTrue(set(patterns).issubset(config["source_patterns"]))
        scope = self.root / "scope.json"
        scope.write_text(json.dumps({"source_patterns": patterns}))
        source_root = self.root / "future-sources"
        packages = []
        for family in families:
            package = f"moe/ouom/neriplayer/{family}/future"
            path = source_root / package / "NewRule.kt"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + package.replace("/", ".") + "\nclass NewRule\n")
            complexity = 10 if family == "core/download/network" else 1
            packages.append(
                f'<package name="{package}"><class name="{package}/NewRule" sourcefilename="NewRule.kt">'
                '<method name="newRule" desc="()V" line="2">'
                f'<counter type="COMPLEXITY" missed="0" covered="{complexity}"/>'
                '</method></class></package>'
            )
        xml = self.root / "coverage.xml"
        xml.write_text('<report name="future">' + "".join(packages) + '</report>')
        result = subprocess.run([
            sys.executable, "-B", str(repository / "tools_pub/quality/crap_report.py"),
            "--xml", str(xml), "--source-root", str(source_root), "--scope", str(scope),
            "--output", str(self.root / "reports")
        ], capture_output=True, text=True, check=False)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("newRule", result.stdout)


if __name__ == "__main__":
    unittest.main()
