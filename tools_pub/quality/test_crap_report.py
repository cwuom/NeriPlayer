import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("crap_report.py")


class CrapReportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sources = self.root / "src"
        self.sources.mkdir()
        (self.sources / "Changed.kt").write_text("package example\nclass Changed\n")
        (self.sources / "Legacy.kt").write_text("package example\nclass Legacy\n")
        self.scope = self.root / "scope.json"
        self.scope.write_text(json.dumps({"source_patterns": ["Changed.kt"]}))
        self.xml = self.root / "coverage.xml"
        self.output = self.root / "reports"

    def run_report(self, changed, legacy="", report_only=False):
        self.xml.write_text(
            '<report name="unit"><package name="example">'
            '<class name="example/Changed" sourcefilename="Changed.kt">'
            + changed + '</class>'
            '<class name="example/Legacy" sourcefilename="Legacy.kt">'
            + legacy + '</class></package></report>'
        )
        return self.invoke(report_only)

    def invoke(self, report_only=False):
        args = [sys.executable, str(SCRIPT), "--xml", str(self.xml),
                "--source-root", str(self.sources), "--scope", str(self.scope),
                "--output", str(self.output)]
        if report_only:
            args.append("--report-only")
        return subprocess.run(args, text=True, capture_output=True)

    def test_reports_every_method_and_lists_strictly_above_eight(self):
        result = self.run_report(
            method("eight", 8, 8) + method("nine", 9, 9),
            method("legacyRisk", 10, 0),
        )
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads((self.output / "methods.json").read_text())
        self.assertEqual(3, len(report))
        self.assertEqual([110.0, 9.0, 8.0], [row["crap"] for row in report])
        listed = (self.output / "above-8.md").read_text()
        self.assertIn("legacyRisk", listed)
        self.assertIn("nine", listed)
        self.assertNotIn("#eight", listed)

    def test_fails_above_nine_using_unrounded_score(self):
        result = self.run_report(method("tinyMiss", 9, 8))
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("tinyMiss", result.stdout)
        rows = json.loads((self.output / "methods.json").read_text())
        self.assertAlmostEqual(9.111111111, rows[0]["crap"])

    def test_uses_covered_complexity_instead_of_line_coverage(self):
        result = self.run_report(method("branching", 4, 2))
        self.assertEqual(0, result.returncode, result.stderr)
        row = json.loads((self.output / "methods.json").read_text())[0]
        self.assertEqual(6.0, row["crap"])
        self.assertEqual(0.5, row["coverage"])

    def test_reports_coroutine_and_lambda_methods_in_scope(self):
        self.xml.write_text(
            '<report><package name="example">'
            '<class name="example/Changed$scan$1" sourcefilename="Changed.kt">'
            + method("invokeSuspend", 10, 10)
            + '</class></package></report>'
        )
        result = self.invoke()
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("invokeSuspend", result.stdout)

    def test_keeps_overloads_distinct(self):
        result = self.run_report(
            method("scan", 1, 1, "()V") + method("scan", 2, 2, "(I)V")
        )
        self.assertEqual(0, result.returncode, result.stderr)
        rows = json.loads((self.output / "methods.json").read_text())
        self.assertEqual({"()V", "(I)V"}, {row["descriptor"] for row in rows})

    def test_report_only_preserves_failing_scope_in_report(self):
        result = self.run_report(method("risk", 10, 0), report_only=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("risk", (self.output / "scope.md").read_text())

    def test_method_scope_gates_changed_method_in_legacy_source(self):
        self.scope.write_text(json.dumps({
            "source_patterns": ["Changed.kt"],
            "method_scopes": [{"source": "Legacy.kt", "class": "example.Legacy",
                               "methods": ["changed"]}],
        }))
        result = self.run_report(method("stable", 1, 1),
                                 method("changed", 10, 0) + method("untouched", 10, 0))
        self.assertEqual(1, result.returncode, result.stderr)
        rows = json.loads((self.output / "methods.json").read_text())
        self.assertTrue(next(row for row in rows if row["method"] == "changed")["in_scope"])
        self.assertFalse(next(row for row in rows if row["method"] == "untouched")["in_scope"])
        self.assertIn("untouched", (self.output / "above-8.md").read_text())

    def test_unmatched_method_scope_is_an_error(self):
        self.scope.write_text(json.dumps({
            "source_patterns": ["Changed.kt"],
            "method_scopes": [{"source": "Legacy.kt", "class": "example.Legacy",
                               "methods": ["missing"]}],
        }))
        result = self.run_report(method("stable", 1, 1), method("other", 1, 1))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("missing", result.stderr)

    def test_missing_xml_is_an_error(self):
        result = self.invoke()
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("coverage.xml", result.stderr)

    def test_empty_scope_is_an_error(self):
        self.scope.write_text('{"source_patterns": []}')
        result = self.run_report(method("scan", 1, 1))
        self.assertEqual(2, result.returncode, result.stderr)

    def test_unmatched_scope_pattern_is_an_error(self):
        self.scope.write_text('{"source_patterns": ["Missing.kt"]}')
        result = self.run_report(method("scan", 1, 1))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("Missing.kt", result.stderr)

    def test_missing_scoped_source_in_xml_is_an_error(self):
        self.xml.write_text('<report><package name="example"><class '
                            'name="example/Legacy" sourcefilename="Legacy.kt">'
                            + method("legacy", 1, 1) + '</class></package></report>')
        result = self.invoke()
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("Changed.kt", result.stderr)

    def test_missing_complexity_counter_is_an_error(self):
        result = self.run_report('<method name="scan" desc="()V" line="2"/>')
        self.assertEqual(2, result.returncode, result.stderr)

    def test_negative_counter_is_an_error(self):
        result = self.run_report(method("scan", 1, 2))
        self.assertEqual(2, result.returncode, result.stderr)


def method(name, complexity, covered, descriptor="()V"):
    return (f'<method name="{name}" desc="{descriptor}" line="2">'
            f'<counter type="COMPLEXITY" missed="{complexity - covered}" '
            f'covered="{covered}"/>'
            '<counter type="LINE" missed="0" covered="100"/></method>')


if __name__ == "__main__":
    unittest.main()
