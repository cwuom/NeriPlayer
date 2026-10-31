import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile


SCRIPT = Path(__file__).with_name("run_connected_tests.py")
runner = None
if SCRIPT.exists():
    spec = importlib.util.spec_from_file_location("run_connected_tests", SCRIPT)
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)

ROOT_FOCUS = "androidx.test.espresso.base.RootViewPicker$RootViewWithoutFocusException: missing focus"
NATIVE_FOCUS = "java.lang.AssertionError: 原生触摸目标窗口未就绪: attached=true, shown=true, focused=false"
OVERLAY_CLASS = "moe.ouom.neriplayer.ui.component.overlay.OverlayWindowNavigationBarPolicyTest"
OVERLAY_NAME = "immersiveSheetKeepsItsOwnNavigationHiddenThroughFocusAndReopen"
OVERLAY_TIMEOUT = (
    "androidx.compose.ui.test.ComposeTimeoutException: Condition "
    "(sheet-initial: 当前真实 Window 的导航栏应 隐藏且窗口策略完成更新) still not satisfied after 15000 ms"
)
OVERLAY_SNAPSHOT = (
    "10-30 18:04:59.311 3988 3988 E OverlayWindowNavigation: sheet-initial failed hidden=true behavior=1: "
    "window=71688369 attached=true focused=false navigationVisible=false statusVisible=true "
    "navigationBottom=0 imeVisible=false navigationAnimations=0 behavior=1\n"
)


class ConnectedTestsRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(runner, "the connected test recovery implementation is missing")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = dict(os.environ, ANDROID_SERIAL="emulator-5554", INSTRUMENTATION_MODULE_TASKS="",
                        INSTRUMENTATION_APP_ARGUMENTS="-Pandroid.testInstrumentationRunnerArguments.numShards=4 "
                        "-Pandroid.testInstrumentationRunnerArguments.shardIndex=2")
        self.attempts = []
        self.commands = []
        self.app_runs = 0
        self.module_runs = 0
        self.module_result = 0
        self.recovery_result = 0

    def output(self, module="app"):
        return self.root / module / "build/outputs/androidTest-results/connected/debug"

    def write_report(self, cases, module="app"):
        output = self.output(module)
        output.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite", tests=str(len(cases)))
        for class_name, name, failure in cases:
            attributes = {key: value for key, value in {"classname": class_name, "name": name}.items()
                          if value is not None}
            case = ET.SubElement(suite, "testcase", **attributes)
            if failure is not None:
                tag, attributes, message = failure
                ET.SubElement(case, tag, **attributes).text = message
        ET.ElementTree(suite).write(output / "TEST-result.xml", encoding="utf-8")

    def case(self, failure=None, name="focusCase", class_name="ExampleTest"):
        return class_name, name, None if failure is None else ("failure", {}, failure)

    def execute(self, command, **kwargs):
        self.commands.append(command)
        self.assertEqual(kwargs["env"]["ANDROID_SERIAL"], "emulator-5554")
        if command[0] == "adb":
            return subprocess.CompletedProcess(command, self.recovery_result)
        if ":app:connectedDebugAndroidTest" not in command:
            self.module_runs += 1
            self.write_report([self.case(name="libraryCase")], "modules/database")
            return subprocess.CompletedProcess(command, self.module_result)
        result, cases, customize = self.attempts[self.app_runs]
        self.app_runs += 1
        self.write_report(cases)
        if customize:
            customize()
        return subprocess.CompletedProcess(command, result)

    def run_fixture(self):
        with patch.object(runner.subprocess, "run", side_effect=self.execute), patch("sys.stdout", new=io.StringIO()):
            return runner.run_connected_tests(self.root, self.env)

    def two_attempts(self, failure=NATIVE_FOCUS):
        self.attempts = [(1, [self.case(failure), self.case(name="other")], None),
                         (0, [self.case(), self.case(name="other")], None)]

    def test_focus_failure_replays_the_entire_lane_and_archives_original_evidence(self):
        self.env["INSTRUMENTATION_MODULE_TASKS"] = ":database:connectedDebugAndroidTest"
        self.two_attempts()
        source = self.root / "app/src/fixture.kt"
        source.parent.mkdir(parents=True)
        source.write_text("preserve source")

        def evidence_files():
            html = self.root / "app/build/reports/androidTests/connected/index.html"
            html.parent.mkdir(parents=True)
            html.write_text("first failure report")
            (self.output() / "logcat-first-case.txt").write_text("first failure log")

        self.attempts[0] = (1, self.attempts[0][1], evidence_files)
        self.assertEqual(self.run_fixture(), 0)
        self.assertEqual((self.module_runs, self.app_runs), (2, 2))
        gradle = [command for command in self.commands if command[0] == "./gradlew"]
        self.assertEqual(gradle[:2], gradle[2:])
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.notAnnotation=moe.ouom.neriplayer.testing.ManualInstrumentedTest", gradle[1])
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.shardIndex=2", gradle[1])
        self.assertIn("--no-daemon", gradle[1])
        self.assertIn("-Pkotlin.compiler.execution.strategy=in-process", gradle[1])
        self.assertEqual([c for c in self.commands if c[0] == "adb"], [
            ["adb", "-s", "emulator-5554", "shell", "am", "force-stop", "moe.ouom.neriplayer.debug"],
            ["adb", "-s", "emulator-5554", "shell", "am", "start", "-W", "-a", "android.settings.SETTINGS"],
        ])
        archive = self.root / "build/reports/emulator-host/focus-recovery-attempt-1.zip"
        with zipfile.ZipFile(archive) as evidence:
            first_report = ET.fromstring(evidence.read("app/build/outputs/androidTest-results/connected/debug/TEST-result.xml"))
            self.assertEqual(first_report.find("testcase/failure").text, NATIVE_FOCUS)
            self.assertIn("modules/database/build/outputs/androidTest-results/connected/debug/TEST-result.xml", evidence.namelist())
            self.assertEqual(evidence.read("app/build/reports/androidTests/connected/index.html"), b"first failure report")
            self.assertEqual(evidence.read("app/build/outputs/androidTest-results/connected/debug/logcat-first-case.txt"), b"first failure log")
        self.assertEqual(len(list(self.root.rglob("TEST-*.xml"))), 2)
        self.assertEqual(source.read_text(), "preserve source")

    def test_only_exact_focus_exceptions_allow_recovery(self):
        for message in [ROOT_FOCUS, NATIVE_FOCUS,
                        NATIVE_FOCUS.replace("原生触摸", "原生返回")]:
            with self.subTest(message=message):
                self.app_runs = 0
                self.two_attempts(message)
                self.assertEqual(self.run_fixture(), 0)
                self.assertEqual(self.app_runs, 2)

    def test_business_assertions_unknown_failures_and_generic_timeouts_never_retry(self):
        for message in ["java.lang.AssertionError: expected:<MAIN> but was:<EDIT_INFO>",
                        "unknown.Error: missing focus", "androidx.compose.ui.test.ComposeTimeoutException: timeout",
                        "java.lang.AssertionError: " + ROOT_FOCUS, "", NATIVE_FOCUS + ", extra=true",
                        NATIVE_FOCUS.replace("attached=true", "attached=false")]:
            with self.subTest(message=message):
                self.app_runs = 0
                self.two_attempts(message)
                self.assertNotEqual(self.run_fixture(), 0)
                self.assertEqual(self.app_runs, 1)

    def test_mixed_focus_and_business_failures_do_not_retry(self):
        self.attempts = [(1, [self.case(ROOT_FOCUS), self.case("java.lang.AssertionError: wrong state", "business")], None)]
        self.assertNotEqual(self.run_fixture(), 0)
        self.assertEqual(self.app_runs, 1)

    def test_explicit_exception_type_cannot_disguise_an_assertion_or_error(self):
        for tag, attributes in [("failure", {"type": "java.lang.AssertionError"}), ("error", {})]:
            with self.subTest(tag=tag):
                self.app_runs = 0
                self.attempts = [(1, [("ExampleTest", "focusCase", (tag, attributes, ROOT_FOCUS))], None)]
                self.assertNotEqual(self.run_fixture(), 0)
                self.assertEqual(self.app_runs, 1)

    def test_missing_malformed_duplicate_or_incomplete_reports_do_not_retry(self):
        for customize in [lambda: (self.output() / "TEST-result.xml").unlink(),
                          lambda: (self.output() / "TEST-result.xml").write_text("<testsuite>"),
                          lambda: self.write_report([self.case(ROOT_FOCUS), self.case(ROOT_FOCUS)]),
                          lambda: self.write_report([(None, "focusCase", ("failure", {}, ROOT_FOCUS))])]:
            with self.subTest(customize=customize):
                self.app_runs = 0
                self.attempts = [(1, [self.case(ROOT_FOCUS)], customize)]
                self.assertNotEqual(self.run_fixture(), 0)
                self.assertEqual(self.app_runs, 1)

    def test_nonzero_gradle_without_a_real_focus_failure_does_not_retry(self):
        for cases in [[self.case()], [self.case("org.junit.AssumptionViolatedException: unsupported host")]]:
            with self.subTest(cases=cases):
                self.app_runs = 0
                self.attempts = [(1, cases, None)]
                self.assertNotEqual(self.run_fixture(), 0)
                self.assertEqual(self.app_runs, 1)

    def test_failed_module_does_not_run_app_or_attempt_recovery(self):
        self.env["INSTRUMENTATION_MODULE_TASKS"] = ":database:connectedDebugAndroidTest"
        self.module_result = 7
        self.assertEqual(self.run_fixture(), 7)
        self.assertEqual((self.module_runs, self.app_runs), (1, 0))

    def test_second_failure_is_returned_without_a_third_attempt(self):
        self.two_attempts(ROOT_FOCUS)
        self.attempts[1] = (9, [self.case(ROOT_FOCUS), self.case(name="other")], None)
        self.assertEqual(self.run_fixture(), 9)
        self.assertEqual(self.app_runs, 2)

    def test_recovery_command_failure_prevents_replay(self):
        self.two_attempts()
        self.recovery_result = 4
        self.assertEqual(self.run_fixture(), 4)
        self.assertEqual(self.app_runs, 1)

    def test_successful_replay_must_preserve_every_identity_without_duplicates(self):
        for cases in [[self.case()], [self.case(), self.case()],
                      [self.case(), self.case(name="different")]]:
            with self.subTest(cases=cases):
                self.app_runs = 0
                self.two_attempts()
                self.attempts[1] = (0, cases, None)
                self.assertNotEqual(self.run_fixture(), 0)
                self.assertEqual(self.app_runs, 2)

    def test_replay_cannot_replace_a_focus_failure_with_an_assumption_skip(self):
        self.two_attempts()
        self.attempts[1] = (0, [self.case("org.junit.AssumptionViolatedException: unsupported host"),
                                self.case(name="other")], None)
        self.assertNotEqual(self.run_fixture(), 0)
        self.assertEqual(self.app_runs, 2)

    def test_replay_preserves_library_case_identities_and_rejects_missing_library_xml(self):
        self.env["INSTRUMENTATION_MODULE_TASKS"] = ":database:connectedDebugAndroidTest"
        for customize in [lambda: self.write_report([self.case(name="changed")], "modules/database"),
                          lambda: (self.output("modules/database") / "TEST-result.xml").unlink(),
                          lambda: self.write_report([self.case(), self.case()], "modules/database")]:
            with self.subTest(customize=customize):
                self.app_runs = 0
                self.two_attempts()
                self.attempts[1] = (0, [self.case(), self.case(name="other")], customize)
                self.assertNotEqual(self.run_fixture(), 0)
                self.assertEqual(self.app_runs, 2)

    def test_unchanged_assumptions_remain_allowed_during_focus_recovery(self):
        self.two_attempts()
        assumption = self.case("org.junit.internal.AssumptionViolatedException: tablet only", "tabletCase")
        for _, cases, _ in self.attempts:
            cases.append(assumption)
        self.assertEqual(self.run_fixture(), 0)
        self.assertEqual(self.app_runs, 2)

    def test_zero_exit_with_failed_xml_is_not_success(self):
        self.attempts = [(0, [self.case("java.lang.AssertionError: failed")], None)]
        self.assertNotEqual(self.run_fixture(), 0)
        self.assertEqual(self.app_runs, 1)

    def test_physical_or_missing_serial_is_rejected_before_any_command(self):
        for serial in ["PLK110", "", "emulator-5554; echo unsafe"]:
            with self.subTest(serial=serial):
                self.env["ANDROID_SERIAL"] = serial
                self.assertNotEqual(self.run_fixture(), 0)
        self.assertEqual(self.commands, [])

    def write_overlay_log(self, snapshot=OVERLAY_SNAPSHOT, name=OVERLAY_NAME):
        output = self.output() / "test(AVD) - 15"
        output.mkdir(parents=True, exist_ok=True)
        (output / f"logcat-{OVERLAY_CLASS}-{name}.txt").write_text(snapshot)

    def overlay_attempts(self, snapshot=OVERLAY_SNAPSHOT, name=OVERLAY_NAME):
        self.attempts = [(1, [self.case(OVERLAY_TIMEOUT, OVERLAY_NAME, OVERLAY_CLASS)],
                          lambda: self.write_overlay_log(snapshot, name)),
                         (0, [self.case(name=OVERLAY_NAME, class_name=OVERLAY_CLASS)], None)]

    def test_overlay_timeout_retries_only_when_bound_snapshot_proves_focus_is_the_only_failed_condition(self):
        self.overlay_attempts()
        self.assertEqual(self.run_fixture(), 0)
        self.assertEqual(self.app_runs, 2)

    def test_overlay_policy_mismatch_or_unbound_log_never_retries(self):
        for before, after in [("navigationVisible=false", "navigationVisible=true"),
                              ("statusVisible=true", "statusVisible=false"),
                              ("navigationBottom=0", "navigationBottom=24"),
                              ("navigationAnimations=0", "navigationAnimations=1"),
                              ("imeVisible=false", "imeVisible=true"),
                              ("behavior=1\n", "behavior=2\n"),
                              ("attached=true", "attached=false"),
                              ("focused=false", "focused=true"),
                              ("sheet-initial failed", "different-stage failed")]:
            with self.subTest(before=before):
                self.app_runs = 0
                self.overlay_attempts(OVERLAY_SNAPSHOT.replace(before, after))
                self.assertNotEqual(self.run_fixture(), 0)
                self.assertEqual(self.app_runs, 1)
        for name in ["anotherCase", ""]:
            with self.subTest(log_case=name):
                self.app_runs = 0
                self.overlay_attempts(name=name)
                self.assertNotEqual(self.run_fixture(), 0)
                self.assertEqual(self.app_runs, 1)


if __name__ == "__main__":
    unittest.main()
