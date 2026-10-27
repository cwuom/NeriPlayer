import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location(
    "verify_instrumentation", Path(__file__).with_name("verify_instrumentation.py")
)
verification = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verification)


class InstrumentationReportsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for shard in range(4):
            self.write(shard, "app", [("ExampleTest", f"case{shard}")])
        for module in verification.LIBRARY_MODULES:
            self.write(0, module, [(f"{module}.Test", "libraryCase")])
        self.write(4, "app", [(verification.LYRICS_CLASS, name) for name in verification.LYRICS_CASES])
        self.write(5, "app", [(verification.ROTATION_CLASS, name) for name in verification.ROTATION_CASES])

    def write(self, shard, module, cases, skipped=False, result_tag=None, result_text=None, result_type=None):
        output = self.root / f"android-instrumented-test-reports-{shard}" / module
        output /= "build/outputs/androidTest-results/connected/debug"
        output.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite")
        for class_name, name in cases:
            case = ET.SubElement(suite, "testcase", classname=class_name, name=name)
            if skipped:
                ET.SubElement(case, "skipped")
            if result_tag:
                result = ET.SubElement(case, result_tag)
                if result_type is not None:
                    result.set("type", result_type)
                result.text = result_text
        ET.ElementTree(suite).write(output / "TEST-result.xml")

    def test_accepts_complete_disjoint_test_routes(self):
        inventory = verification.validate_reports(self.root)
        self.assertEqual(len(inventory["app"]), 9)
        self.assertEqual(len(inventory["libraries"]), 3)

    def test_counts_agp_assumption_failures_without_type_as_skipped(self):
        self.write(0, "app", [("ExampleTest", "case0")], result_tag="failure",
                   result_text="org.junit.AssumptionViolatedException: 需要至少 600dp 可用宽度的平板宿主\n\tat org.junit.Assume.assumeTrue(Assume.java:68)")
        self.write(0, "modules/local", [("LocalTest", "case")], result_tag="failure",
                   result_text="org.junit.internal.AssumptionViolatedException: got: <false>, expected: is <true>\n\tat org.junit.Assume.assumeTrue(Assume.java:50)")
        inventory = verification.validate_reports(self.root)
        self.assertEqual(inventory["skipped"], {"ExampleTest#case0", "modules/local:LocalTest#case"})
        self.assertEqual(len(inventory["app"]), 9)
        self.assertEqual(len(inventory["libraries"]), 3)

    def test_explicit_assumption_type_is_authoritative(self):
        self.write(0, "app", [("ExampleTest", "case0")], result_tag="failure",
                   result_type="org.junit.AssumptionViolatedException", result_text="condition not met")
        inventory = verification.validate_reports(self.root)
        self.assertEqual(inventory["skipped"], {"ExampleTest#case0"})

    def test_rejects_unknown_failures_and_assertions_mentioning_assumptions(self):
        for explicit_type, trace in [
            (None, "java.lang.AssertionError: org.junit.AssumptionViolatedException: not a skip"),
            ("java.lang.AssertionError", "org.junit.AssumptionViolatedException: misleading trace"),
            ("unknown.Error", "org.junit.AssumptionViolatedException: misleading trace"),
            (None, "unknown.Error: failed"),
            (None, "org.junit.AssumptionViolatedExceptionExtra: failed"),
            (None, ""),
        ]:
            with self.subTest(explicit_type=explicit_type, trace=trace):
                self.write(0, "app", [("ExampleTest", "case0")], result_tag="failure",
                           result_type=explicit_type, result_text=trace)
                with self.assertRaisesRegex(ValueError, "did not pass"):
                    verification.validate_reports(self.root)

    def test_rejects_errors_even_with_assumption_type(self):
        self.write(0, "app", [("ExampleTest", "case0")], result_tag="error",
                   result_type="org.junit.AssumptionViolatedException", result_text="condition not met")
        with self.assertRaisesRegex(ValueError, "did not pass"):
            verification.validate_reports(self.root)

    def test_both_dedicated_hosts_must_execute_without_assumption_skips(self):
        for shard, class_name, names in [
            (4, verification.LYRICS_CLASS, verification.LYRICS_CASES),
            (5, verification.ROTATION_CLASS, verification.ROTATION_CASES),
        ]:
            with self.subTest(shard=shard):
                self.write(shard, "app", [(class_name, name) for name in names], result_tag="failure",
                           result_text="org.junit.AssumptionViolatedException: missing host")
                with self.assertRaisesRegex(ValueError, "did not pass"):
                    verification.validate_reports(self.root)
                self.write(shard, "app", [(class_name, name) for name in names])

    def test_rejects_library_failures_and_errors(self):
        for result_tag in ["failure", "error"]:
            with self.subTest(result_tag=result_tag):
                self.write(0, "modules/local", [("LocalTest", "case")], result_tag=result_tag,
                           result_text="java.lang.AssertionError: data mismatch")
                with self.assertRaisesRegex(ValueError, "did not pass"):
                    verification.validate_reports(self.root)

    def test_counts_explicit_shared_and_library_skips(self):
        self.write(1, "app", [("ExampleTest", "case1")], skipped=True)
        self.write(0, "modules/local", [("LocalTest", "case")], skipped=True)
        inventory = verification.validate_reports(self.root)
        self.assertEqual(inventory["skipped"], {"ExampleTest#case1", "modules/local:LocalTest#case"})

    def test_rejects_duplicate_shared_cases(self):
        self.write(1, "app", [("ExampleTest", "case0")])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            verification.validate_reports(self.root)

    def test_rejects_missing_lane(self):
        path = self.root / "android-instrumented-test-reports-3"
        (path / "app/build/outputs/androidTest-results/connected/debug/TEST-result.xml").unlink()
        with self.assertRaisesRegex(ValueError, "no app test"):
            verification.validate_reports(self.root)

    def test_rejects_host_class_in_shared_shard(self):
        self.write(1, "app", [(verification.LYRICS_CLASS, "unexpected")])
        with self.assertRaisesRegex(ValueError, "dedicated host"):
            verification.validate_reports(self.root)

    def test_rejects_skipped_host_case(self):
        self.write(5, "app", [(verification.ROTATION_CLASS, name) for name in verification.ROTATION_CASES], skipped=True)
        with self.assertRaisesRegex(ValueError, "did not pass"):
            verification.validate_reports(self.root)

    def test_rejects_library_tests_outside_first_shard(self):
        self.write(2, "modules/database", [("DatabaseTest", "case")])
        with self.assertRaisesRegex(ValueError, "library tests"):
            verification.validate_reports(self.root)


if __name__ == "__main__":
    unittest.main()
