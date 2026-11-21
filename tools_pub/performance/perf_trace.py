#!/usr/bin/env python3
"""Capture bounded Android Perfetto evidence and analyze it with a local processor."""

import argparse
from collections import defaultdict
import csv
from datetime import datetime, timezone
import hashlib
import io
from itertools import accumulate
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid


BIN_NS = 100_000_000
PACKAGE = re.compile(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n")


def parse_csv(value):
    return [
        {key: None if cell == "[NULL]" else cell for key, cell in row.items()}
        for row in csv.DictReader(io.StringIO(value))
    ]


def cpu_health_reasons(rows, metadata_rows=()):
    reasons = []
    for row in rows:
        if not float(row["value"]):
            continue
        name, severity = row["name"], row.get("severity")
        # this counter is service-wide and cannot attribute loss to this session
        if name == "traced_chunks_discarded":
            continue
        lost = severity == "data_loss" or re.search(
            r"ftrace.*(overrun|lost|missing|dropped)|traced_buf.*(discard|overwrite|overwritten|violation)",
            name,
        ) or name == "traced_buf_patches_failed"
        parse_error = severity == "error" and re.search(
            r"ftrace|sched|clock|tokeniz|parse|^(packet_|proto_|trace_)", name
        )
        if lost or parse_error:
            reasons.append(f"trace health: {name}={row['value']}")
    for row in metadata_rows:
        if row["name"] == "ftrace_setup_errors" and re.search(
            r"\bsched(?:/|:)sched_switch\b|\bsched_switch\b", row.get("str_value") or ""
        ):
            reasons.append("ftrace setup detail reports required sched_switch unavailable")
    return reasons


def cpu_summary(rows, start, end, reasons=()):
    if end <= start:
        raise ValueError("analysis window must have positive duration")
    invalid = list(reasons)
    if not rows:
        invalid.append("no target scheduling slices; missing evidence is not zero load")
    total = 0
    full_bins = (end - start) // BIN_NS
    bin_changes = defaultdict(int)
    for row in rows:
        ts, dur = int(row["ts"]), int(row["dur"])
        if dur < 0:
            if ts < end:
                invalid.append("open target scheduling slice intersects analysis window")
            continue
        left, right = max(start, ts), min(end, ts + dur)
        if right <= left:
            continue
        total += right - left
        bin_right = min(right, start + full_bins * BIN_NS)
        if bin_right <= left:
            continue
        first = (left - start) // BIN_NS
        last = (bin_right - 1 - start) // BIN_NS
        first_value = min(bin_right, start + (first + 1) * BIN_NS) - left
        bin_changes[first] += first_value
        bin_changes[first + 1] -= first_value
        if last > first:
            last_value = bin_right - (start + last * BIN_NS)
            bin_changes[last] += last_value
            bin_changes[last + 1] -= last_value
            # full interior bins share one range update regardless of trace length
            bin_changes[first + 1] += BIN_NS
            bin_changes[last] -= BIN_NS
    current = peak = 0
    for index in sorted(bin_changes):
        current += bin_changes[index]
        if index < full_bins:
            peak = max(peak, current)
    valid = not invalid
    result = {
        "status": "observed" if valid else "unknown",
        "reasons": sorted(set(invalid)),
        "cpu_time_ms": total / 1e6 if valid else None,
        "average_cores": total / (end - start) if valid else None,
        "average_percent_of_one_core": 100 * total / (end - start) if valid else None,
        "peak_100ms_cores": peak / BIN_NS if valid and full_bins else None,
        "peak_100ms_percent_of_one_core": 100 * peak / BIN_NS if valid and full_bins else None,
        "bin_ms": 100,
        "complete_bin_count": full_bins,
        "excluded_tail_ms": ((end - start) % BIN_NS) / 1e6,
        "scope": "sum of target package and colon subprocess thread CPU time; one busy core = 1.0 cores or 100%",
    }
    return result, (
        {"start_ns": start + index * BIN_NS, "duration_ns": BIN_NS,
         "cpu_time_ns": value if valid else None, "average_cores": value / BIN_NS if valid else None,
         "status": result["status"]}
        for index, value in enumerate(accumulate(bin_changes.get(index, 0) for index in range(full_bins)))
    )


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    left = math.floor(position)
    return ordered[left] + (ordered[math.ceil(position)] - ordered[left]) * (position - left)


def frame_summary(rows, start, end):
    grouped = defaultdict(list)
    unkeyed = 0
    for row in rows:
        token = int(row["surface_frame_token"] or 0)
        if token <= 0:
            unkeyed += 1
        else:
            grouped[(row["upid"], token)].append(row)
    jank = clean = unknown = unknown_jank_reason = 0
    durations = []
    for group in grouped.values():
        closed = all(int(row["dur"]) >= 0 for row in group)
        left = min(int(row["ts"]) for row in group)
        right = max(int(row["ts"]) + max(0, int(row["dur"])) for row in group)
        contained = closed and left >= start and right <= end
        labels = [row.get("jank_type") for row in group]
        known_jank = any(label and label != "None" for label in labels)
        all_clean = all(label == "None" for label in labels)
        if not contained or not (known_jank or all_clean):
            unknown += 1
        elif known_jank:
            jank += 1
            unknown_jank_reason += any(label and "unknown" in label.lower() for label in labels)
        else:
            clean += 1
        if contained:
            durations.append((right - left) / 1e6)
    return {
        "status": ("partial" if unknown or unkeyed else "observed") if grouped else "unknown",
        "actual_layer_slice_count": len(rows),
        "unique_app_frame_token_count": len(grouped),
        "unkeyed_layer_slice_count": unkeyed,
        "jank_token_count": jank,
        "non_jank_token_count": clean,
        "classification_unknown_count": unknown,
        "unknown_jank_reason_count": unknown_jank_reason,
        "classified_jank_fraction": jank / (jank + clean) if jank + clean else None,
        "duration_p50_ms": percentile(durations, 0.5),
        "duration_p95_ms": percentile(durations, 0.95),
        "duration_max_ms": max(durations, default=None),
        "scope": "actual app tokens deduplicated by (upid, surface_frame_token); duration is layer envelope, not input latency, displayed FPS, or presentation time",
    }


def counter_summary(rows):
    tracks = defaultdict(list)
    for row in rows:
        tracks[(row["track_id"], row["name"], row.get("unit"))].append(float(row["value"]))
    return {
        "status": "observed" if rows else "unknown",
        "reasons": [] if rows else ["no matching counter samples"],
        "tracks": [
            {"track_id": key[0], "name": key[1], "unit": key[2],
             "sample_count": len(values), "sample_min": min(values), "sample_max": max(values)}
            for key, values in sorted(tracks.items(), key=lambda item: str(item[0]))
        ],
        "gpu_utilization": {"status": "unknown", "reason": "GPU frequency is not utilization; vendor counter meaning and attribution require separate validation"},
        "device_power_watts": {"status": "unknown", "reason": "battery current includes charging; rail coverage, units and attribution are not assumed"},
        "scope": "raw samples and sample extrema only; hardware update rate and absolute sensor accuracy unknown",
    }


def analysis_queries(package, start, end):
    if not PACKAGE.fullmatch(package):
        raise ValueError("invalid Android package")
    app = f"""WITH app AS (
        SELECT upid, pid, name, cmdline FROM process
        WHERE name = '{package}' OR name GLOB '{package}:*'
           OR cmdline = '{package}' OR cmdline GLOB '{package}:*'
    ) """
    return {
        "health": "SELECT name,value,severity,source FROM stats WHERE value != 0 ORDER BY name;",
        "metadata": "SELECT name,str_value,int_value FROM metadata ORDER BY name;",
        "processes": app + "SELECT * FROM app ORDER BY upid;",
        "cpu-slices": app + f"""SELECT s.id,s.ts,s.dur,s.cpu,t.utid,t.tid,t.name AS thread_name,p.upid,p.pid
            FROM sched s JOIN thread t USING(utid) JOIN app p USING(upid)
            WHERE s.ts < {end} AND (s.dur < 0 OR s.ts+s.dur > {start})
            ORDER BY s.ts,s.id;""",
        "actual-frames": app + f""", window_layers AS (
            SELECT a.id,a.upid,a.surface_frame_token
            FROM actual_frame_timeline_slice a JOIN app USING(upid)
            WHERE a.ts < {end} AND (a.dur < 0 OR a.ts+a.dur > {start})
            ) SELECT a.id,a.ts,a.dur,a.upid,a.surface_frame_token,
            a.display_frame_token,a.layer_name,a.jank_type,a.on_time_finish,a.present_type
            FROM actual_frame_timeline_slice a JOIN app USING(upid)
            WHERE a.id IN (SELECT id FROM window_layers) OR (
                a.surface_frame_token > 0 AND (a.upid,a.surface_frame_token) IN (
                    SELECT upid,surface_frame_token FROM window_layers))
            ORDER BY a.ts,a.id;""",
        "counters": f"""SELECT c.ts,c.track_id,t.name,t.unit,t.type,c.value
            FROM counter c JOIN counter_track t ON t.id=c.track_id
            WHERE c.ts >= {start} AND c.ts < {end} AND (
                t.type GLOB 'gpu*' OR lower(t.name) GLOB '*gpu*' OR lower(t.name) GLOB '*gpufreq*'
                OR lower(t.name) GLOB '*cpufreq*' OR lower(t.name) GLOB 'batt.*'
                OR lower(t.name) GLOB '*power*' OR lower(t.name) GLOB '*rail*')
            ORDER BY c.track_id,c.ts;""",
        "startups": f"""INCLUDE PERFETTO MODULE android.startup.startups;
            SELECT * FROM android_startups WHERE package='{package}'
            AND ts < {end} AND ts_end > {start} ORDER BY ts;""",
    }


def trace_config(package, seconds, gpu_source=None, counter_ids=()):
    events = ["sched/sched_switch", "sched/sched_waking", "sched/sched_process_exit",
              "sched/sched_process_free", "task/task_newtask", "task/task_rename",
              "power/cpu_frequency", "power/cpu_idle", "power/gpu_frequency"]
    ftrace = "\n".join(f'      ftrace_events: "{event}"' for event in events)
    categories = "\n".join(f'      atrace_categories: "{name}"' for name in ("am", "wm", "gfx", "view", "input", "dalvik"))
    config = f"""buffers {{ size_kb: 65536 fill_policy: RING_BUFFER }}
duration_ms: {seconds * 1000}
write_into_file: true
file_write_period_ms: 1000
max_file_size_bytes: 268435456
flush_period_ms: 5000
data_sources {{ config {{ name: "linux.ftrace" ftrace_config {{
{ftrace}
{categories}
      atrace_apps: "{package}"
      compact_sched {{ enabled: true }}
}} }} }}
data_sources {{ config {{ name: "linux.process_stats" process_stats_config {{
    scan_all_processes_on_start: true
    record_thread_names: true
    proc_stats_poll_ms: 1000
}} }} }}
data_sources {{ config {{ name: "android.surfaceflinger.frametimeline" }} }}
data_sources {{ config {{ name: "android.power" android_power_config {{
    battery_poll_ms: 250
    battery_counters: BATTERY_COUNTER_CAPACITY_PERCENT
    battery_counters: BATTERY_COUNTER_CHARGE
    battery_counters: BATTERY_COUNTER_CURRENT
    battery_counters: BATTERY_COUNTER_VOLTAGE
    collect_power_rails: true
}} }} }}
"""
    if gpu_source:
        ids = "\n".join(f"    counter_ids: {value}" for value in counter_ids)
        config += f"""data_sources {{ config {{ name: "{gpu_source}" gpu_counter_config {{
    counter_period_ns: 1000000
{ids}
}} }} }}
"""
    return config


def adb_call(args, command, **kwargs):
    return subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True,
                          text=True, timeout=kwargs.pop("timeout", 20), **kwargs)


def device_snapshot(args):
    commands = {
        "hardware_serial": ["getprop", "ro.serialno"],
        "model": ["getprop", "ro.product.model"],
        "fingerprint": ["getprop", "ro.build.fingerprint"],
        "sdk": ["getprop", "ro.build.version.sdk"],
        "soc_manufacturer": ["getprop", "ro.soc.manufacturer"],
        "soc_model": ["getprop", "ro.soc.model"],
        "hardware": ["getprop", "ro.hardware"],
        "board_platform": ["getprop", "ro.board.platform"],
        "online_cpu_count": ["getconf", "_NPROCESSORS_ONLN"],
        "battery": ["dumpsys", "battery"],
        "thermal": ["dumpsys", "thermalservice"],
        "window_animation_scale": ["settings", "get", "global", "window_animation_scale"],
        "transition_animation_scale": ["settings", "get", "global", "transition_animation_scale"],
        "animator_duration_scale": ["settings", "get", "global", "animator_duration_scale"],
        "brightness": ["settings", "get", "system", "screen_brightness"],
        "peak_refresh_rate": ["settings", "get", "system", "peak_refresh_rate"],
        "min_refresh_rate": ["settings", "get", "system", "min_refresh_rate"],
    }
    result = {}
    for name, command in commands.items():
        try:
            process = adb_call(args, ["shell", *command])
            stdout = process.stdout.strip()
            result[name] = {
                "returncode": process.returncode, "stdout": stdout, "stderr": process.stderr.strip(),
                "status": "observed" if process.returncode == 0 and stdout and stdout != "null" else "unknown",
            }
            if name == "online_cpu_count":
                count = int(stdout) if process.returncode == 0 and stdout.isdigit() else 0
                result[name]["value"] = count if count > 0 else None
                result[name]["status"] = "observed" if count > 0 else "unknown"
        except subprocess.TimeoutExpired:
            result[name] = {"status": "unknown", "reason": "snapshot timed out"}
    package = adb_call(args, ["shell", "dumpsys", "package", args.package])
    version_lines = [line.strip() for line in package.stdout.splitlines()
                     if re.match(r"\s*(versionCode=|versionName=|firstInstallTime=|lastUpdateTime=)", line)]
    result["package_version"] = {
        "returncode": package.returncode,
        "status": "observed" if package.returncode == 0 and version_lines else "unknown",
        "lines": version_lines, "stderr": package.stderr.strip(),
    }
    return result


def capture(args):
    if not re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial):
        raise ValueError("serial must be an explicit ADB serial or host:port")
    if not PACKAGE.fullmatch(args.package):
        raise ValueError("invalid Android package")
    if not 30 <= args.seconds <= 60:
        raise ValueError("capture duration must be between 30 and 60 seconds")
    if bool(args.gpu_source) != bool(args.gpu_counter_id):
        raise ValueError("GPU counters require both --gpu-source and documented --gpu-counter-id")
    if args.gpu_source and not re.fullmatch(r"gpu\.counters(?:\.[A-Za-z0-9_]+)?", args.gpu_source):
        raise ValueError("invalid GPU producer name")
    if any(value < 0 for value in args.gpu_counter_id):
        raise ValueError("GPU counter IDs must be nonnegative")
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    remote = f"/data/misc/perfetto-traces/neriplayer-perf-{uuid.uuid4().hex}.pftrace"
    config = trace_config(args.package, args.seconds, args.gpu_source, args.gpu_counter_id)
    (out / "config.pbtxt").write_text(config)
    metadata = {
        "schema_version": 1, "status": "preparing", "serial": args.serial,
        "package": args.package, "theme": args.theme, "scenario": args.scenario,
        "build_label": args.build_label, "notes": args.notes,
        "requested_duration_ms": args.seconds * 1000,
        "started_utc": datetime.now(timezone.utc).isoformat(), "remote_trace": remote,
        "config_sha256": sha256(out / "config.pbtxt"),
        "theme_and_scenario_validation": "caller declared; UI and action semantics require separate evidence",
    }
    write_json(out / "capture.json", metadata)
    try:
        state = adb_call(args, ["get-state"])
        if state.returncode or state.stdout.strip() != "device":
            raise RuntimeError("selected device is not connected and authorized")
        metadata["before"] = device_snapshot(args)
        metadata["status"] = "starting"
        write_json(out / "capture.json", metadata)
        command = ["shell", "perfetto", "--background-wait", "--txt", "-c", "-", "-o", remote]
        process = adb_call(args, command, input=config, timeout=40)
        metadata["perfetto_start"] = {"returncode": process.returncode, "stdout": process.stdout, "stderr": process.stderr}
        if process.returncode:
            raise RuntimeError("Perfetto failed to start; see capture.json")
        pid_text = process.stdout.strip()
        if not re.fullmatch(r"[0-9]+", pid_text):
            raise RuntimeError("Perfetto did not return an unambiguous owned process ID; trace expires automatically")
        metadata["perfetto_pid"] = int(pid_text)
        metadata["status"] = "recording"
        metadata["ready_utc"] = datetime.now(timezone.utc).isoformat()
        write_json(out / "capture.json", metadata)
        print(json.dumps({"status": "ready", "out": str(out), "message": "data sources started; perform the declared scenario now"}), flush=True)
        earliest_finish = time.monotonic() + args.seconds
        deadline = earliest_finish + 30
        while True:
            alive = adb_call(args, ["shell", "ps", "-p", pid_text, "-o", "PID,NAME"])
            if alive.returncode not in (0, 1) or "PID" not in alive.stdout or alive.stderr.strip():
                raise RuntimeError("cannot verify owned Perfetto process state; remote trace preserved")
            running = any(line.split()[:1] == [pid_text] for line in alive.stdout.splitlines())
            if not running and time.monotonic() >= earliest_finish:
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("owned Perfetto process did not finish within bounded wait; remote trace preserved")
            time.sleep(1)
        pull = adb_call(args, ["pull", remote, str(out / "trace.pftrace")], timeout=60)
        metadata["pull"] = {"returncode": pull.returncode, "stdout": pull.stdout, "stderr": pull.stderr}
        if pull.returncode or not (out / "trace.pftrace").stat().st_size:
            raise RuntimeError("trace pull failed or produced an empty file; remote evidence preserved")
        metadata["trace_sha256"] = sha256(out / "trace.pftrace")
        metadata["after"] = device_snapshot(args)
        try:
            cleanup = adb_call(args, ["shell", "rm", remote])
            metadata["remote_cleanup_returncode"] = cleanup.returncode
            metadata["remote_cleanup_stderr"] = cleanup.stderr
        except (OSError, subprocess.SubprocessError) as error:
            metadata["remote_cleanup_returncode"] = None
            metadata["remote_cleanup_stderr"] = str(error)
        if metadata["remote_cleanup_returncode"] != 0:
            print(f"warning: trace captured but remote cleanup failed; manually remove {remote}",
                  file=sys.stderr, flush=True)
        metadata["status"] = "captured"
        print(json.dumps({"status": "captured", "out": str(out), "trace_sha256": metadata["trace_sha256"]}), flush=True)
    except (Exception, KeyboardInterrupt) as error:
        metadata["status"] = "failed"
        metadata["error"] = str(error) or type(error).__name__
        raise
    finally:
        metadata["finished_utc"] = datetime.now(timezone.utc).isoformat()
        write_json(out / "capture.json", metadata)


def query(processor, trace, out, name, sql):
    (out / f"{name}.sql").write_text(sql + "\n")
    try:
        result = subprocess.run([str(processor), "query", str(trace)], input=sql,
                                capture_output=True, text=True, timeout=180)
        (out / f"{name}.stderr.txt").write_text(result.stderr)
        (out / f"{name}.csv").write_text(result.stdout)
        return {"returncode": result.returncode, "rows": parse_csv(result.stdout) if result.returncode == 0 else [], "stderr": result.stderr}
    except subprocess.TimeoutExpired:
        (out / f"{name}.stderr.txt").write_text("query timed out\n")
        (out / f"{name}.csv").write_text("")
        return {"returncode": None, "rows": [], "stderr": "query timed out"}


def analyze(args):
    trace, processor = args.trace.resolve(strict=True), args.processor.resolve(strict=True)
    if not PACKAGE.fullmatch(args.package):
        raise ValueError("invalid Android package")
    if not math.isfinite(args.start_ms) or args.start_ms < 0 or (
        args.duration_ms is not None and (not math.isfinite(args.duration_ms) or args.duration_ms <= 0)
    ):
        raise ValueError("start must be nonnegative and duration must be positive")
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    original_hash = sha256(trace)
    bounds = query(processor, trace, out, "bounds", "SELECT start_ts,end_ts FROM trace_bounds;")
    if bounds["returncode"] or not bounds["rows"]:
        raise RuntimeError("trace bounds unavailable; inspect bounds stderr and CSV")
    trace_start, trace_end = (int(bounds["rows"][0][name]) for name in ("start_ts", "end_ts"))
    start = trace_start + round(args.start_ms * 1e6)
    end = trace_end if args.duration_ms is None else start + round(args.duration_ms * 1e6)
    if end <= start or end > trace_end:
        raise ValueError("analysis window is empty or extends beyond captured trace bounds")
    queries = {name: query(processor, trace, out, name, sql)
               for name, sql in analysis_queries(args.package, start, end).items()}
    reasons = cpu_health_reasons(queries["health"]["rows"], queries["metadata"]["rows"])
    for name in ("health", "metadata", "cpu-slices", "processes"):
        if queries[name]["returncode"] != 0:
            reasons.append(f"required {name} query failed")
    diagnostics = bounds["stderr"] + "\n" + "\n".join(value["stderr"] for value in queries.values())
    if re.search(r"unexpected.*(eof|end)|truncat|failed to parse|trace.*corrupt", diagnostics, re.I):
        reasons.append("processor reported truncated or corrupt trace")
    capture_metadata = None
    completeness_reasons = []
    if args.capture_metadata:
        capture_metadata = json.loads(
            args.capture_metadata.read_text(), parse_constant=str,
            parse_float=lambda value: float(value) if math.isfinite(float(value)) else value,
        )
        if not isinstance(capture_metadata, dict):
            raise ValueError("capture metadata must be a JSON object")
        if capture_metadata.get("package") != args.package:
            reasons.append("capture metadata package differs from analysis package")
        if capture_metadata.get("trace_sha256") != original_hash:
            reasons.append("capture metadata trace hash missing or different")
        if capture_metadata.get("status") != "captured":
            reasons.append("capture did not finish successfully")
        expected = capture_metadata.get("requested_duration_ms")
        valid_duration = type(expected) in (int, float) and expected > 0 and (
            isinstance(expected, int) or math.isfinite(expected)
        )
        if not valid_duration:
            completeness_reasons.append("capture requested_duration_ms is missing or not a finite positive number")
        elif (trace_end - trace_start) / 1e6 < expected - 1000:
            reasons.append("trace shorter than requested capture by more than 1 second")
    else:
        completeness_reasons.append("no requested-duration and capture-hash metadata")
    if sha256(trace) != original_hash:
        reasons.append("trace changed during analysis")
    completeness_reasons.extend(reasons)
    cpu, bins = cpu_summary(queries["cpu-slices"]["rows"], start, end, reasons)
    with (out / "cpu-100ms.csv").open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=["start_ns", "duration_ns", "cpu_time_ns", "average_cores", "status"])
        writer.writeheader()
        writer.writerows(bins)
    frames = frame_summary(queries["actual-frames"]["rows"], start, end)
    if reasons or queries["actual-frames"]["returncode"] != 0:
        frames["status"] = "unknown"
        for name in ("classified_jank_fraction", "duration_p50_ms", "duration_p95_ms", "duration_max_ms"):
            frames[name] = None
        frames["reasons"] = reasons or ["FrameTimeline query failed"]
    counters = counter_summary(queries["counters"]["rows"])
    if queries["counters"]["returncode"] != 0:
        counters["status"] = "unknown"
        counters["reasons"] = ["counters query failed"]
    version = subprocess.run([str(processor), "--version"], capture_output=True, text=True, timeout=10)
    report = {
        "schema_version": 1, "trace": str(trace), "trace_sha256": original_hash,
        "processor": str(processor), "processor_sha256": sha256(processor),
        "processor_version": (version.stdout + version.stderr).strip(),
        "package": args.package, "capture_metadata": capture_metadata,
        "window": {"start_ns": start, "end_ns": end, "duration_ms": (end - start) / 1e6,
                   "offset_from_trace_start_ms": args.start_ms, "peak_bin_alignment": "analysis window start"},
        "cpu": cpu, "frames": frames,
        "trace_health": {
            "nonzero_stats": queries["health"]["rows"],
            "ftrace_setup_details": [row for row in queries["metadata"]["rows"] if row["name"] == "ftrace_setup_errors"],
            "scope": "optional setup notice counts and informational readaheads are not by themselves scheduler data loss; absent setup details remain unknown",
        },
        "counters": counters,
        "startups": {"status": "observed" if queries["startups"]["rows"] and not reasons else "unknown",
                     "rows": queries["startups"]["rows"],
                     "scope": "Perfetto inferred startup type and duration; scenario label alone does not establish a cold start or full interactivity"},
        "queries": {name: {"returncode": result["returncode"], "row_count": len(result["rows"])}
                    for name, result in queries.items()},
        "completeness": {
            "status": "observed_checks_passed" if not completeness_reasons else "unknown",
            "reasons": completeness_reasons,
            "scope": "duration/hash and observed trace health checks; inspect saved health and processor diagnostics",
        },
    }
    write_json(out / "summary.json", report)
    print(json.dumps({"summary": str(out / "summary.json"), "cpu": cpu, "frames": frames}, ensure_ascii=False))


def make_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    recording = commands.add_parser("capture", help="collect one bounded trace without changing settings or app state")
    recording.add_argument("--serial", required=True)
    recording.add_argument("--adb", default="adb")
    recording.add_argument("--package", required=True)
    recording.add_argument("--theme", choices=("material-you", "clean"), required=True)
    recording.add_argument("--scenario", choices=("cold-start", "interaction", "playback"), required=True)
    recording.add_argument("--build-label", required=True)
    recording.add_argument("--notes", default="")
    recording.add_argument("--seconds", type=int, default=30)
    recording.add_argument("--gpu-source", help="exact producer name from perfetto --query; no guessed counters")
    recording.add_argument("--gpu-counter-id", type=int, action="append", default=[])
    recording.add_argument("--out", type=Path, required=True)
    recording.set_defaults(run=capture)
    analysis = commands.add_parser("analyze", help="analyze a local trace; no ADB access")
    analysis.add_argument("--trace", type=Path, required=True)
    analysis.add_argument("--processor", type=Path, required=True)
    analysis.add_argument("--package", required=True)
    analysis.add_argument("--capture-metadata", type=Path)
    analysis.add_argument("--start-ms", type=float, default=0)
    analysis.add_argument("--duration-ms", type=float)
    analysis.add_argument("--out", type=Path, required=True)
    analysis.set_defaults(run=analyze)
    return parser


def main():
    args = make_parser().parse_args()
    try:
        args.run(args)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
