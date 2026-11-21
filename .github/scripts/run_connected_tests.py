"""Run a complete emulator lane, recovering once from proven window-focus failures.

A small, configurable number of other failed cases may each be retried once in isolation;
every retried case must pass and is reported as flaky.
"""

import copy
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

# 测试仓库与主仓库共用脚本，未显式开启时保持严格模式
FLAKY_RETRY_LIMIT_ENV = "INSTRUMENTATION_FLAKY_RETRY_LIMIT"
LANE_STATUS = "build/reports/emulator-host/connected-lane-status.txt"
LOG_ONLY = "-Pandroid.testInstrumentationRunnerArguments.log=true"
TEST_CLASS = re.compile(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*")
TEST_METHOD = re.compile(r"[A-Za-z_$][\w$]*")


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


def archive_reports(root: Path, modules: list[str], name: str = "focus-recovery-attempt-1.zip",
                    prefix: str = "", mode: str = "w") -> Path:
    archive = root / "build/reports/emulator-host" / name
    archive.parent.mkdir(parents=True, exist_ok=True)
    # 首轮 XML 保存在压缩包内，避免最终路由校验重复统计同一用例
    with zipfile.ZipFile(archive, mode, compression=zipfile.ZIP_DEFLATED) as evidence:
        for directory in report_directories(root, modules):
            for path in sorted(directory.rglob("*")):
                if path.is_file():
                    evidence.write(path, prefix + path.relative_to(root).as_posix())
    print(f"Archived failure evidence: {archive.relative_to(root)}", flush=True)
    return archive


def failed_cases(cases: dict) -> list[tuple]:
    return [identity for identity, (_, case) in cases.items()
            if case.find("error") is not None
            or any(not is_assumption_failure(failure) for failure in case.findall("failure"))]


def device_online(root: Path, env: dict[str, str], serial: str) -> bool:
    try:
        state = subprocess.run(["adb", "-s", serial, "get-state"], cwd=root, env=env,
                               capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return False
    return state.returncode == 0 and (state.stdout or "").strip() == "device"


def confirm_test_failure(root: Path):
    # 报告已证明测试本身失败；之后即使模拟器掉线也不能再升级为整道重跑
    status = root / LANE_STATUS
    status.parent.mkdir(parents=True, exist_ok=True)
    status.write_text("failed\n", encoding="utf-8")


def recovery_commands(serial: str) -> list[list[str]]:
    return [
        ["adb", "-s", serial, "shell", "am", "force-stop", "moe.ouom.neriplayer.debug"],
        ["adb", "-s", serial, "shell", "am", "start", "-W", "-a", "android.settings.SETTINGS"],
    ]


def merge_retried_cases(original: dict, retried: dict):
    by_report = {}
    for identity, replacement in retried.items():
        by_report.setdefault(original[identity][0], {})[identity[1:]] = replacement
    for report, replacements in by_report.items():
        tree = ET.parse(report)
        for suite in [tree.getroot(), *tree.getroot().iter("testsuite")]:
            for index, case in enumerate(list(suite)):
                key = case.get("classname"), case.get("name")
                if case.tag != "testcase" or key not in replacements:
                    continue
                counter = "errors" if case.find("error") is not None else "failures"
                if (suite.get(counter) or "").isdigit():
                    suite.set(counter, str(max(0, int(suite.get(counter)) - 1)))
                suite[index] = replacements.pop(key)
        if replacements:
            raise ValueError(f"Retried testcase missing from original report: {report}")
        tree.write(report, encoding="utf-8", xml_declaration=True)


def report_flaky_cases(env: dict[str, str], retried: dict):
    names = [f"{class_name}#{name}" for _, class_name, name in retried]
    for name in names:
        print(f"::warning title=Flaky instrumented test::{name} failed once and passed on an isolated retry",
              flush=True)
    summary = env.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as output:
            output.write("### Flaky instrumented tests\n\nFailed once, passed on an isolated retry:\n\n")
            output.writelines(f"- `{name}`\n" for name in names)


def app_inventory(root: Path, app_arguments: list[str], command) -> set[tuple]:
    """List every app test the lane selects without running them."""
    clear_reports(root, ["app"])
    result = command(["./gradlew", ":app:connectedDebugAndroidTest", *app_arguments, LOG_ONLY, *GRADLE_FLAGS])
    archive_reports(root, ["app"], "flaky-retry-inventory.zip")
    if result:
        raise ValueError("Could not list the complete app test inventory of the lane")
    return set(read_cases(root, ["app"]))


def retry_failed_cases(root: Path, env: dict[str, str], modules: list[str], original: dict,
                       failed: list[tuple], app_arguments: list[str], command) -> int:
    first_attempt = archive_reports(root, modules, "flaky-retry-attempt-1.zip")
    print(f"Retrying {len(failed)} failed testcase(s) once in isolation", flush=True)
    for arguments in recovery_commands(env["ANDROID_SERIAL"]):
        recovery_result = command(arguments)
        if recovery_result:
            return recovery_result
    retried = {}
    try:
        # 插桩进程崩溃会截断首轮，未执行的用例不会出现在报告里，必须先证明首轮覆盖了整道清单
        if app_inventory(root, app_arguments, command) != {identity for identity in original if identity[0] == "app"}:
            confirm_test_failure(root)
            raise ValueError("First attempt did not run the complete app test inventory of the lane")
        for index, identity in enumerate(failed, start=1):
            _, class_name, name = identity
            clear_reports(root, ["app"])
            result = command(["./gradlew", ":app:connectedDebugAndroidTest",
                              f"-Pandroid.testInstrumentationRunnerArguments.class={class_name}#{name}",
                              *GRADLE_FLAGS])
            archive_reports(root, ["app"], "flaky-retry-attempt-2.zip", f"case-{index}/",
                            "w" if index == 1 else "a")
            cases = read_cases(root, ["app"])
            if set(cases) != {identity}:
                raise ValueError(f"Isolated retry did not run exactly {class_name}#{name}")
            case = cases[identity][1]
            if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                confirm_test_failure(root)
            case_is_skipped(case, allow_skipped=False)
            if result:
                return result
            retried[identity] = copy.deepcopy(cases[identity][1])
    finally:
        # 无论重试结果如何都恢复首轮完整报告，失败时上传的证据仍包含原始断言
        clear_reports(root, ["app"])
        with zipfile.ZipFile(first_attempt) as evidence:
            evidence.extractall(root)
    merge_retried_cases(original, retried)
    merged = read_cases(root, modules)
    if merged.keys() != original.keys():
        raise ValueError("Merged lane changed its complete testcase identity set")
    for _, case in merged.values():
        case_is_skipped(case, allow_skipped=True)
    report_flaky_cases(env, retried)
    return 0


def run_connected_tests(root: Path, env: dict[str, str]) -> int:
    try:
        serial = env.get("ANDROID_SERIAL", "")
        if not re.fullmatch(r"emulator-\d+", serial):
            raise ValueError("ANDROID_SERIAL must select an emulator")
        module_tasks = shlex.split(env.get("INSTRUMENTATION_MODULE_TASKS", ""))
        if len(module_tasks) != len(set(module_tasks)) or any(task not in MODULE_TASKS for task in module_tasks):
            raise ValueError("Unexpected or duplicate instrumentation module task")
        app_arguments = shlex.split(env.get("INSTRUMENTATION_APP_ARGUMENTS", ""))
        flaky_retry_limit = int(env.get(FLAKY_RETRY_LIMIT_ENV) or 0)
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
            failed = failed_cases(original)
            if not failed:
                return result
            if not device_online(root, env, serial):
                print("Emulator is no longer online; isolated retry is not possible", flush=True)
                return result
            if len(failed) > flaky_retry_limit or not all(
                    TEST_CLASS.fullmatch(class_name) and TEST_METHOD.fullmatch(name)
                    for _, class_name, name in failed):
                confirm_test_failure(root)
                return result
            return retry_failed_cases(root, env, modules, original, failed, app_arguments, command)

        archive_reports(root, modules)
        print("Recovering emulator foreground once; replaying the complete unchanged test lane", flush=True)
        for arguments in recovery_commands(serial):
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
    except (OSError, ValueError, ET.ParseError, zipfile.BadZipFile) as failure:
        print(f"Connected test lane failed: {failure}", flush=True)
        return 1


def main(root: Path, env: dict[str, str]) -> int:
    """Run the lane and record whether a failure came from the tests or from losing the emulator."""
    status = root / LANE_STATUS
    status.parent.mkdir(parents=True, exist_ok=True)
    status.write_text("running\n", encoding="utf-8")
    result = run_connected_tests(root, env)
    serial = env.get("ANDROID_SERIAL", "")
    if not result:
        verdict = "passed"
    elif status.read_text(encoding="utf-8").strip() == "failed":
        verdict = "failed"
    elif re.fullmatch(r"emulator-\d+", serial) and not device_online(root, env, serial):
        # 工作流只对模拟器丢失的情况整道重跑，测试断言失败保持红灯
        verdict = "device-lost"
    else:
        verdict = "failed"
    status.write_text(verdict + "\n", encoding="utf-8")
    print(f"Connected test lane status: {verdict}", flush=True)
    return result


if __name__ == "__main__":
    sys.exit(main(Path.cwd(), dict(os.environ)))
