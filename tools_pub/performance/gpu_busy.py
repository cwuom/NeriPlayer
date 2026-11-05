#!/usr/bin/env python3
"""Read one KGSL node without root; preserve raw values and diagnostic estimates.

The reference Qualcomm driver publishes a cached window of about one second.
It does not provide the window timestamp or sequence number. This tool cannot
verify a vendor ROM's implementation and never reports measured GPU load.
"""

import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import time


NODE = "/sys/class/kgsl/kgsl-3d0/gpubusy"
REFERENCE_UPDATE_US = 1_000_000
REFERENCE_BASE = (
    "https://android.googlesource.com/kernel/msm-modules/graphics/+/"
    "8cd3d33e505fc96ee73a87d75bc06c14d2d66161/"
)
SEMANTICS_REASONS = [
    "target_rom_driver_semantics_unverified",
    "cached_windows_have_no_timestamp_or_sequence_number",
    "identical_pairs_can_be_stale_or_new_windows_with_identical_values",
    "first_pair_excluded_but_first_and_last_window_alignment_unknown",
    "other_readers_and_gpu_power_state_can_clear_reference_cache",
]


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def sample_record(index, start_ns, end_ns, stdout, stderr, returncode, error=None):
    record = {
        "index": index,
        "host_start_monotonic_ns": start_ns,
        "host_end_monotonic_ns": end_ns,
        "host_midpoint_monotonic_ns": (start_ns + end_ns) // 2,
        "host_completed_utc": utc_now(),
        "stdout": stdout,
        "stderr": stderr,
        "returncode": returncode,
        "error": error,
        "busy_raw": None,
        "total_raw": None,
        "status": "unknown",
        "reasons": [],
    }
    if returncode != 0 or error:
        record["reasons"].append("read_failed")
    match = re.fullmatch(r"\s*([0-9]+)\s+([0-9]+)\s*", stdout)
    if match:
        busy, total = (int(value) for value in match.groups())
        record.update(busy_raw=busy, total_raw=total)
        if total == 0:
            record["reasons"].append("zero_total_has_no_utilization_denominator")
        elif busy > total:
            record["reasons"].append("busy_exceeds_total")
    else:
        record["reasons"].append("expected_exactly_two_nonnegative_integers")
    if end_ns < start_ns:
        record["reasons"].append("invalid_host_timing")
    if not record["reasons"]:
        record["status"] = "raw_pair_observed"
    return record


def summarize(records):
    windows = []
    reasons = list(SEMANTICS_REASONS)
    previous_pair = None
    previous_record = None
    distinct_busy = distinct_total = distinct_count = duplicate_count = invalid_count = 0
    raw_valid_count = 0
    for record in records:
        midpoint = record["host_midpoint_monotonic_ns"]
        row = {"sample_index": record["index"], "reasons": list(record["reasons"])}
        if previous_record is not None:
            gap_us = (midpoint - previous_record["host_midpoint_monotonic_ns"]) / 1000
            row["host_poll_gap_us"] = gap_us
            if gap_us <= 0:
                row["reasons"].append("nonincreasing_host_sample_time")
            elif gap_us > REFERENCE_UPDATE_US:
                row["reasons"].append("poll_gap_may_hide_cache_updates")
        previous_record = record
        if record["status"] != "raw_pair_observed":
            invalid_count += 1
            row["classification"] = "invalid_read"
            row["reasons"].append("invalid_read")
        else:
            raw_valid_count += 1
            pair = (record["busy_raw"], record["total_raw"])
            row["raw_ratio_pct"] = pair[0] / pair[1] * 100
            if pair[1] < REFERENCE_UPDATE_US:
                row["reasons"].append("total_below_reference_update_threshold")
            if record["index"] == 0:
                row["classification"] = "first_sample_excluded"
            elif previous_pair is None:
                row["classification"] = "first_valid_pair_after_missing_baseline_excluded"
                row["reasons"].append("missing_valid_initial_baseline")
            elif pair == previous_pair:
                duplicate_count += 1
                row["classification"] = "duplicate_pair_excluded"
            else:
                distinct_count += 1
                distinct_busy += pair[0]
                distinct_total += pair[1]
                row["classification"] = "changed_pair_diagnostic_only"
            previous_pair = pair
        windows.append(row)
        for reason in row["reasons"]:
            if reason not in reasons:
                reasons.append(reason)
    host_span_us = None
    if len(records) >= 2:
        span_ns = (records[-1]["host_midpoint_monotonic_ns"] -
                   records[0]["host_midpoint_monotonic_ns"])
        if span_ns > 0:
            host_span_us = span_ns / 1000
    max_total_us = max((record["total_raw"] for record in records
                        if record["status"] == "raw_pair_observed"), default=0)
    if host_span_us and distinct_total > host_span_us + max_total_us:
        reasons.append("distinct_totals_exceed_host_span_plus_one_reported_window")
    return {
        "status": "diagnostic_only" if raw_valid_count else "unknown",
        "scope": "whole_device_gpu_not_process_attributable",
        "gpu_utilization_pct": None,
        "sample_count": len(records),
        "raw_valid_sample_count": raw_valid_count,
        "invalid_sample_count": invalid_count,
        "first_sample_excluded": bool(records),
        "duplicate_pair_count": duplicate_count,
        "diagnostic_distinct_pair_estimate": {
            "status": "diagnostic_estimate" if distinct_total else "unknown",
            "method": "sum_busy_over_sum_total_of_observed_changed_pairs_after_first_pair",
            "pair_count": distinct_count,
            "busy_us_assuming_reference_semantics": distinct_busy,
            "total_us_assuming_reference_semantics": distinct_total,
            "weighted_ratio_pct": distinct_busy / distinct_total * 100 if distinct_total else None,
            "limitation": "subset_of_observed_distinct_pairs_not_a_complete_measurement_window",
        },
        "continuity": {
            "status": "unknown",
            "host_sample_midpoint_span_us": host_span_us,
            "distinct_total_to_host_span_ratio": distinct_total / host_span_us if host_span_us else None,
            "max_reported_total_us_assuming_reference_semantics": max_total_us or None,
            "coverage_ratio_is_proof": False,
            "reasons": reasons,
        },
        "windows": windows,
    }


def adb_call(args, command):
    return subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True,
                          text=True, timeout=5)


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n",
                    encoding="utf-8")


def capture(args):
    args.out.mkdir(parents=True, exist_ok=False)
    metadata = {
        "schema_version": 1,
        "status": "starting",
        "started_utc": utc_now(),
        "scope": "whole_device_gpu_not_process_attributable",
        "identity": {"adb_serial": args.serial, "hardware_serial": None,
                     "expected_hardware_serial": args.expected_serial},
        "node": NODE,
        "requested_seconds": args.seconds,
        "requested_interval_ms": args.interval_ms,
        "target_rom_semantics": "unverified",
        "reference_semantics": {
            "unit": "microseconds",
            "publication_threshold_us": REFERENCE_UPDATE_US,
            "behavior": "cached_busy_old_total_old_published_by_devfreq_not_reset_on_each_read",
            "power_off_behavior": "read_clears_cached_pair_if_AXI_is_off",
            "sources": [REFERENCE_BASE + name for name in
                        ("kgsl_pwrctrl.c", "kgsl_pwrscale.c", "adreno_gen7.c")],
            "applicability_to_target_rom": "unverified",
        },
    }
    records = []
    try:
        identity = adb_call(args, ["shell", "getprop", "ro.serialno"])
        hardware_serial = identity.stdout.strip()
        metadata["identity"].update(hardware_serial=hardware_serial or None,
                                    returncode=identity.returncode,
                                    stderr=identity.stderr)
        if identity.returncode != 0 or not hardware_serial:
            raise RuntimeError("cannot verify ro.serialno before reading GPU node")
        if args.expected_serial and hardware_serial != args.expected_serial:
            raise RuntimeError("hardware serial mismatch")
        metadata["status"] = "capturing"
        write_json(args.out / "capture.json", metadata)
        started_ns = time.monotonic_ns()
        deadline_ns = started_ns + int(args.seconds * 1_000_000_000)
        interval_ns = args.interval_ms * 1_000_000
        scheduled_ns = started_ns
        with (args.out / "samples.jsonl").open("x", encoding="utf-8") as raw:
            while time.monotonic_ns() < deadline_ns:
                remaining_ns = scheduled_ns - time.monotonic_ns()
                if remaining_ns > 0:
                    time.sleep(remaining_ns / 1_000_000_000)
                if time.monotonic_ns() >= deadline_ns:
                    break
                start_ns = time.monotonic_ns()
                try:
                    result = adb_call(args, ["shell", "cat", NODE])
                    stdout, stderr, returncode, error = result.stdout, result.stderr, result.returncode, None
                except subprocess.TimeoutExpired as exc:
                    stdout = exc.stdout or ""
                    stderr = exc.stderr or ""
                    if isinstance(stdout, bytes):
                        stdout = stdout.decode("utf-8", errors="replace")
                    if isinstance(stderr, bytes):
                        stderr = stderr.decode("utf-8", errors="replace")
                    returncode, error = None, f"TimeoutExpired: {exc}"
                except OSError as exc:
                    stdout, stderr, returncode, error = "", "", None, f"{type(exc).__name__}: {exc}"
                record = sample_record(len(records), start_ns, time.monotonic_ns(),
                                       stdout, stderr, returncode, error)
                records.append(record)
                raw.write(json.dumps(record, ensure_ascii=False, allow_nan=False) + "\n")
                raw.flush()
                scheduled_ns += interval_ns
                if scheduled_ns < time.monotonic_ns():
                    scheduled_ns = time.monotonic_ns()
        metadata["status"] = "captured"
    except (Exception, KeyboardInterrupt) as exc:
        metadata.update(status="failed", error=f"{type(exc).__name__}: {exc}")
        raise
    finally:
        metadata["completed_utc"] = utc_now()
        metadata["sample_count"] = len(records)
        write_json(args.out / "capture.json", metadata)
        write_json(args.out / "summary.json", summarize(records))
    return metadata


def positive_seconds(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError("seconds must be finite and greater than zero")
    return number


def make_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="explicit ADB transport serial")
    parser.add_argument("--expected-serial", help="optional required ro.serialno value, useful for TCP ADB")
    parser.add_argument("--seconds", required=True, type=positive_seconds)
    parser.add_argument("--interval-ms", type=int, choices=(100, 250), default=250)
    parser.add_argument("--out", required=True, type=Path, help="new local output directory")
    parser.add_argument("--adb", default="adb")
    return parser


def main():
    args = make_parser().parse_args()
    try:
        result = capture(args)
    except (OSError, RuntimeError, subprocess.SubprocessError) as exc:
        print(f"GPU raw capture failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps({"status": result["status"], "out": str(args.out),
                      "gpu_utilization": "unknown; see raw values and diagnostic estimate"}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
