"""Check that all emulator lanes report complete, disjoint test routes."""

import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


LYRICS_CLASS = "moe.ouom.neriplayer.ui.screen.tab.settings.component.floating.SettingsExternalLyricsPageTouchTest"
ROTATION_CLASS = "moe.ouom.neriplayer.activity.MainActivityRotationContinuityTest"
LYRICS_CASES = {
    "cleanExternalLyricsPageBottomRowsReceiveNativeTouchesWithMiniPlayer",
    "cleanExternalLyricsPageBottomRowsReceiveNativeTouchesAtExpandedUiDensity",
    "cleanExternalLyricsPageAllControlsPersistNativeTouchesAtExpandedUiDensity",
}
ROTATION_CASES = {
    "cleanPlayerRotationPreservesSettingsDraftScrollAndBackDepth",
    "materialPlayerRotationPreservesSettingsDraftScrollAndBackDepth",
}
LIBRARY_MODULES = {"modules/database", "modules/local", "modules/playback/runtime"}
ASSUMPTION_TYPES = {
    "org.junit.AssumptionViolatedException",
    "org.junit.internal.AssumptionViolatedException",
}


def is_assumption_failure(failure: ET.Element) -> bool:
    exception_type = failure.get("type")
    if exception_type is None:
        # AGP 的 XML 可能把合法 assumption 写成无 type 的 failure，只识别堆栈首行的异常类型
        lines = (failure.text or "").strip().splitlines()
        exception_type = lines[0].partition(":")[0] if lines else ""
    return exception_type in ASSUMPTION_TYPES


def case_is_skipped(case: ET.Element, allow_skipped: bool) -> bool:
    failures = case.findall("failure")
    skipped = case.find("skipped") is not None or bool(failures)
    if case.find("error") is not None or any(not is_assumption_failure(item) for item in failures) or (
        skipped and not allow_skipped
    ):
        raise ValueError(f"Test did not pass: {case.get('classname')}#{case.get('name')}")
    return skipped


def validate_reports(root: Path, shared_shards: int = 4) -> dict[str, set[str]]:
    inventory = {"app": set(), "libraries": set(), "skipped": set()}
    for shard in range(shared_shards + 2):
        lane = root / f"android-instrumented-test-reports-{shard}"
        app_cases = []
        library_modules = set()
        for report in lane.rglob("TEST-*.xml"):
            relative = report.relative_to(lane).as_posix()
            marker = "/build/outputs/androidTest-results/connected/"
            if marker not in relative:
                continue
            module = relative.split(marker, 1)[0]
            cases = list(ET.parse(report).iter("testcase"))
            if module == "app":
                app_cases.extend(cases)
            elif cases:
                if shard != 0 or module not in LIBRARY_MODULES:
                    raise ValueError(f"Unexpected library tests in shard {shard}: {module}")
                library_modules.add(module)
                for case in cases:
                    key = f"{module}:{case.get('classname')}#{case.get('name')}"
                    if key in inventory["libraries"]:
                        raise ValueError(f"duplicate library test: {key}")
                    if case_is_skipped(case, allow_skipped=True):
                        inventory["skipped"].add(key)
                    inventory["libraries"].add(key)
        if not app_cases:
            raise ValueError(f"Shard {shard} has no app test results")
        if shard == 0 and library_modules != LIBRARY_MODULES:
            raise ValueError(f"Missing library tests: {LIBRARY_MODULES - library_modules}")
        if shard >= shared_shards:
            expected_class, expected_names = (
                (LYRICS_CLASS, LYRICS_CASES)
                if shard == shared_shards else (ROTATION_CLASS, ROTATION_CASES)
            )
            if len(app_cases) != len(expected_names) or {
                case.get("name") for case in app_cases
            } != expected_names or any(case.get("classname") != expected_class for case in app_cases):
                raise ValueError(f"Shard {shard} has incomplete dedicated host tests")
        app_skipped = 0
        for case in app_cases:
            class_name, name = case.get("classname"), case.get("name")
            if not class_name or not name:
                raise ValueError(f"Incomplete testcase identity in shard {shard}")
            if shard < shared_shards and class_name in {LYRICS_CLASS, ROTATION_CLASS}:
                raise ValueError(f"Unexpected dedicated host test in shard {shard}")
            key = f"{class_name}#{name}"
            if key in inventory["app"]:
                raise ValueError(f"duplicate app test: {key}")
            if case_is_skipped(case, allow_skipped=shard < shared_shards):
                inventory["skipped"].add(key)
                app_skipped += 1
            inventory["app"].add(key)
        print(f"Shard {shard}: {len(app_cases)} app test cases ({app_skipped} skipped)")
    return inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports_dir", type=Path)
    parser.add_argument("--shared-shards", type=int, default=4)
    parser.add_argument("--inventory-output", type=Path)
    args = parser.parse_args()
    inventory = validate_reports(args.reports_dir, args.shared_shards)
    if args.inventory_output:
        args.inventory_output.parent.mkdir(parents=True, exist_ok=True)
        args.inventory_output.write_text(
            json.dumps({key: sorted(value) for key, value in inventory.items()}, indent=2) + "\n",
            encoding="utf-8",
        )
    total = len(inventory["app"]) + len(inventory["libraries"])
    skipped = len(inventory["skipped"])
    print(f"Validated {len(inventory['app'])} app and {len(inventory['libraries'])} library test cases: "
          f"{total - skipped} passed, {skipped} skipped")


if __name__ == "__main__":
    main()
