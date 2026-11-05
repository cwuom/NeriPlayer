"""Run a complete emulator lane, recovering once from proven window-focus failures."""

import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

from verify_instrumentation import case_is_skipped, is_assumption_failure


MODULE_TASKS = {
    ":database:connectedDebugAndroidTest": "modules/database",
    ":local:connectedDebugAndroidTest": "modules/local",
    ":playback:runtime:connectedDebugAndroidTest": "modules/playback/runtime",
}
GRADLE_FLAGS = [
    "-Pandroid.testInstrumentationRunnerArguments.notAnnotation=moe.ouom.neriplayer.testing.ManualInstrumentedTest",
    "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process", "--warning-mode", "all", "--stacktrace",
]
ROOT_FOCUS_TYPE = "androidx.test.espresso.base.RootViewPicker$RootViewWithoutFocusException"
OVERLAY_CLASS = "moe.ouom.neriplayer.ui.component.overlay.OverlayWindowNavigationBarPolicyTest"
COMPOSE_TIMEOUT_TYPE = "androidx.compose.ui.test.ComposeTimeoutException"
NATIVE_FOCUS_MESSAGES = {
    "java.lang.AssertionError: 原生触摸目标窗口未就绪: attached=true, shown=true, focused=false",
    "java.lang.AssertionError: 原生返回目标窗口未就绪: attached=true, shown=true, focused=false",
}
OVERLAY_SNAPSHOT = re.compile(
    r"(?P<stage>[^: ]+) failed hidden=(?P<hidden>true|false) behavior=(?P<expected>\d+): "
    r"window=\d+ attached=(?P<attached>true|false) focused=(?P<focused>true|false) "
    r"navigationVisible=(?P<navigation>true|false) statusVisible=(?P<status>true|false) "
    r"navigationBottom=(?P<bottom>\d+) imeVisible=(?P<ime>true|false) "
    r"navigationAnimations=(?P<animations>\d+) behavior=(?P<behavior>\d+)"
)


def report_directories(root: Path, modules: list[str]) -> list[Path]:
    return [root / module / suffix for module in modules for suffix in (
        "build/outputs/androidTest-results/connected", "build/reports/androidTests/connected",
    )]


def clear_reports(root: Path, modules: list[str]):
    for directory in report_directories(root, modules):
        if directory.exists():
            shutil.rmtree(directory)


def read_cases(root: Path, modules: list[str]) -> dict:
    cases = {}
    for module in modules:
        output = root / module / "build/outputs/androidTest-results/connected"
        count = 0
        for report in sorted(output.rglob("TEST-*.xml")):
            for case in ET.parse(report).iter("testcase"):
                identity = module, case.get("classname"), case.get("name")
                if not all(identity) or identity in cases:
                    raise ValueError(f"Missing or duplicate testcase identity: {identity}")
                cases[identity] = report, case
                count += 1
        if not count:
            raise ValueError(f"Missing testcase reports: {module}")
    return cases


def overlay_focus_only(report: Path, case: ET.Element, first_line: str) -> bool:
    if case.get("classname") != OVERLAY_CLASS:
        return False
    timeout = re.fullmatch(
        re.escape(COMPOSE_TIMEOUT_TYPE) + r": Condition \(([^:()]+): .+\) still not satisfied after \d+ ms",
        first_line,
    )
    if not timeout:
        return False
    filename = f"logcat-{OVERLAY_CLASS}-{case.get('name')}.txt"
    logs = [path for path in report.parent.rglob("logcat-*.txt") if path.name == filename]
    if len(logs) != 1:
        return False
    snapshots = [line.partition("OverlayWindowNavigation: ")[2]
                 for line in logs[0].read_text(encoding="utf-8").splitlines()
                 if "OverlayWindowNavigation: " in line and " failed " in line]
    if len(snapshots) != 1:
        return False
    snapshot = OVERLAY_SNAPSHOT.fullmatch(snapshots[0])
    if not snapshot:
        return False
    values = snapshot.groupdict()
    return (
        values["stage"] == timeout.group(1) and values["attached"] == "true"
        and values["focused"] == "false" and values["navigation"] != values["hidden"]
        and values["status"] == "true" and values["ime"] == "false"
        and values["animations"] == "0" and values["expected"] == values["behavior"] == "1"
        and (values["hidden"] == "false" or values["bottom"] == "0")
    )


def failures_are_focus_only(cases: dict) -> bool:
    failures = 0
    for report, case in cases.values():
        if case.find("error") is not None:
            return False
        for failure in case.findall("failure"):
            if is_assumption_failure(failure):
                continue
            failures += 1
            lines = (failure.text or "").strip().splitlines()
            first_line = lines[0] if lines else ""
            exception_type = failure.get("type", first_line.partition(":")[0])
            if exception_type == ROOT_FOCUS_TYPE:
                continue
            if exception_type == "java.lang.AssertionError" and first_line in NATIVE_FOCUS_MESSAGES:
                continue
            if exception_type == COMPOSE_TIMEOUT_TYPE and overlay_focus_only(report, case, first_line):
                continue
            return False
    return failures > 0


def archive_reports(root: Path, modules: list[str]):
    archive = root / "build/reports/emulator-host/focus-recovery-attempt-1.zip"
    archive.parent.mkdir(parents=True, exist_ok=True)
    # 首轮 XML 保存在压缩包内，避免最终路由校验重复统计同一用例
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as evidence:
        for directory in report_directories(root, modules):
            for path in sorted(directory.rglob("*")):
                if path.is_file():
                    evidence.write(path, path.relative_to(root).as_posix())
    print(f"Archived first focus failure evidence: {archive.relative_to(root)}", flush=True)


def run_connected_tests(root: Path, env: dict[str, str]) -> int:
    try:
        serial = env.get("ANDROID_SERIAL", "")
        if not re.fullmatch(r"emulator-\d+", serial):
            raise ValueError("ANDROID_SERIAL must select an emulator")
        module_tasks = shlex.split(env.get("INSTRUMENTATION_MODULE_TASKS", ""))
        if len(module_tasks) != len(set(module_tasks)) or any(task not in MODULE_TASKS for task in module_tasks):
            raise ValueError("Unexpected or duplicate instrumentation module task")
        app_arguments = shlex.split(env.get("INSTRUMENTATION_APP_ARGUMENTS", ""))
        modules = ["app", *(MODULE_TASKS[task] for task in module_tasks)]

        def command(arguments):
            return subprocess.run(arguments, cwd=root, env=env).returncode

        def lane():
            if module_tasks:
                result = command(["./gradlew", *module_tasks, *GRADLE_FLAGS])
                if result:
                    return result, False
            return command(["./gradlew", ":app:connectedDebugAndroidTest", *app_arguments, *GRADLE_FLAGS]), True

        clear_reports(root, modules)
        result, app_ran = lane()
        if not app_ran:
            return result
        original = read_cases(root, modules)
        if not result:
            for _, case in original.values():
                case_is_skipped(case, allow_skipped=True)
            return 0
        if not failures_are_focus_only(original):
            print("Lane failure is not exclusively a recognized window-focus readiness failure", flush=True)
            return result

        archive_reports(root, modules)
        print("Recovering emulator foreground once; replaying the complete unchanged test lane", flush=True)
        for arguments in [
            ["adb", "-s", serial, "shell", "am", "force-stop", "moe.ouom.neriplayer.debug"],
            ["adb", "-s", serial, "shell", "am", "start", "-W", "-a", "android.settings.SETTINGS"],
        ]:
            recovery_result = command(arguments)
            if recovery_result:
                return recovery_result
        clear_reports(root, modules)
        result, _ = lane()
        if result:
            return result
        replayed = read_cases(root, modules)
        if original.keys() != replayed.keys():
            raise ValueError("Recovered lane changed its complete testcase identity set")
        original_skipped = {
            identity for identity, (_, case) in original.items()
            if (case.find("skipped") is not None or case.findall("failure"))
            and all(is_assumption_failure(failure) for failure in case.findall("failure"))
        }
        for identity, (_, case) in replayed.items():
            if case_is_skipped(case, allow_skipped=True) and identity not in original_skipped:
                raise ValueError(f"Recovered lane newly skipped a testcase: {identity}")
        return 0
    except (OSError, ValueError, ET.ParseError) as failure:
        print(f"Connected test lane failed: {failure}", flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(run_connected_tests(Path.cwd(), dict(os.environ)))
