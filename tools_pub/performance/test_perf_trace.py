"""Focused fixtures for scheduling arithmetic and missing-evidence handling."""

import argparse
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import tracemalloc
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("perf_trace.py")


def run_fake_processor_analysis(tool, root, requested_duration=300, include_duration=True,
                                fail_metadata=False, fail_counters=False):
    trace = root / "trace.pftrace"
    trace.write_bytes(b"opaque-fixture-trace")
    processor = root / "fake-processor"
    processor.write_text(f"#!{sys.executable}\n" + '''import pathlib
import sys
if "--version" in sys.argv:
    print("fixture processor")
    sys.exit(0)
sql = sys.stdin.read()
if "FROM trace_bounds" in sql:
    print("start_ts,end_ts\\n0,300000000")
elif "FROM stats" in sql:
    print("name,value,severity,source\\nftrace_setup_errors,1,notice,trace")
elif "FROM metadata" in sql:
    if pathlib.Path(__file__).with_name("fail-metadata").exists():
        print("metadata unavailable", file=sys.stderr)
        sys.exit(1)
    print("name,str_value,int_value")
elif "FROM sched s" in sql:
    print("ts,dur\\n0,150000000")
elif "SELECT * FROM app ORDER BY" in sql:
    print("upid,pid,name,cmdline\\n1,100,app.test,app.test")
elif "FROM counter c" in sql:
    if pathlib.Path(__file__).with_name("fail-counters").exists():
        print("counters unavailable", file=sys.stderr)
        sys.exit(1)
    print("ts,track_id,name,unit,type,value\\n0,1,gpufreq,MHz,gpu_counter,500")
''')
    processor.chmod(0o700)
    if fail_metadata:
        (root / "fail-metadata").touch()
    if fail_counters:
        (root / "fail-counters").touch()
    metadata = {"package": "app.test", "status": "captured", "trace_sha256": tool.sha256(trace)}
    if include_duration:
        metadata["requested_duration_ms"] = requested_duration
    metadata_path = root / "capture.json"
    metadata_path.write_text(json.dumps(metadata))
    args = argparse.Namespace(trace=trace, processor=processor, package="app.test",
                              out=root / "analysis", start_ms=0, duration_ms=None,
                              capture_metadata=metadata_path)
    with redirect_stdout(io.StringIO()):
        tool.analyze(args)
    return json.loads((args.out / "summary.json").read_text())


class TraceAnalysisTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not SCRIPT.exists():
            cls.tool = None
            return
        spec = importlib.util.spec_from_file_location("perf_trace", SCRIPT)
        cls.tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.tool)

    def setUp(self):
        self.assertIsNotNone(self.tool, "performance analyzer has not been implemented")

    def test_cpu_clips_crossing_slices_and_sums_parallel_threads(self):
        rows = [
            {"ts": -50_000_000, "dur": 200_000_000},
            {"ts": 50_000_000, "dur": 150_000_000},
            {"ts": 190_000_000, "dur": 60_000_000},
        ]
        summary, bins = self.tool.cpu_summary(rows, 0, 200_000_000)
        self.assertEqual(summary["cpu_time_ms"], 310)
        self.assertEqual(summary["average_cores"], 1.55)
        self.assertEqual(summary["peak_100ms_cores"], 1.6)
        self.assertEqual([row["cpu_time_ns"] for row in bins], [150_000_000, 160_000_000])

    def test_short_tail_cannot_create_a_different_peak_window(self):
        summary, bins = self.tool.cpu_summary(
            [{"ts": 100_000_000, "dur": 20_000_000}], 0, 120_000_000
        )
        self.assertEqual(summary["peak_100ms_cores"], 0)
        self.assertEqual(summary["excluded_tail_ms"], 20)
        self.assertEqual(len(list(bins)), 1)
        self.assertAlmostEqual(summary["average_cores"], 1 / 6)

    def test_long_window_bins_preserve_load_without_retaining_each_row(self):
        end = 3_600_000_000_000
        tracemalloc.start()
        try:
            summary, bins = self.tool.cpu_summary([{"ts": 0, "dur": end}], 0, end)
            first = last = None
            count = 0
            for row in bins:
                if first is None:
                    first = row
                last = row
                count += 1
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        self.assertEqual(summary["average_cores"], 1)
        self.assertEqual(summary["peak_100ms_cores"], 1)
        self.assertEqual(summary["complete_bin_count"], 36_000)
        self.assertEqual(count, 36_000)
        self.assertEqual(first["start_ns"], 0)
        self.assertEqual(last["start_ns"], 3_599_900_000_000)
        self.assertEqual(last["cpu_time_ns"], 100_000_000)
        self.assertLess(peak, 2_000_000, "CPU bin storage must not scale with the analysis window")

    def test_open_target_slice_makes_cpu_unknown(self):
        summary, _ = self.tool.cpu_summary([{"ts": 50, "dur": -1}], 0, 200_000_000)
        self.assertEqual(summary["status"], "unknown")
        self.assertIsNone(summary["cpu_time_ms"])
        self.assertIsNone(summary["peak_100ms_cores"])

    def test_missing_sched_is_not_zero_load(self):
        summary, _ = self.tool.cpu_summary([], 0, 200_000_000)
        self.assertEqual(summary["status"], "unknown")
        self.assertIsNone(summary["average_cores"])

    def test_truncated_or_lost_trace_is_not_a_valid_load(self):
        summary, _ = self.tool.cpu_summary(
            [{"ts": 0, "dur": 100_000_000}], 0, 200_000_000,
            reasons=["trace truncated"],
        )
        self.assertEqual(summary["status"], "unknown")
        self.assertIsNone(summary["average_cores"])

    def test_health_does_not_confuse_service_wide_discard_with_session_loss(self):
        health = [
            {"name": "traced_chunks_discarded", "value": "9", "severity": "data_loss"},
            {"name": "power_rail_empty_packet", "value": "1", "severity": "error"},
        ]
        self.assertEqual(self.tool.cpu_health_reasons(health), [])
        health.append({"name": "ftrace_cpu_overrun_end", "value": "2", "severity": "data_loss"})
        self.assertIn("ftrace_cpu_overrun_end", self.tool.cpu_health_reasons(health)[0])

    def test_optional_setup_notices_and_readahead_info_do_not_prove_cpu_loss(self):
        health = [
            {"name": "ftrace_setup_errors", "value": "37", "severity": "notice"},
            {"name": "traced_buf_readaheads_failed", "value": "43", "severity": "info"},
        ]
        self.assertEqual(self.tool.cpu_health_reasons(health), [])

    def test_failed_metadata_query_cannot_certify_cpu_or_trace_completeness(self):
        with tempfile.TemporaryDirectory() as directory:
            summary = run_fake_processor_analysis(self.tool, Path(directory), fail_metadata=True)
        self.assertEqual(summary["cpu"]["status"], "unknown")
        self.assertEqual(summary["completeness"]["status"], "unknown")
        self.assertIn("required metadata query failed", summary["cpu"]["reasons"])

    def test_query_timeout_persists_diagnostics_and_an_empty_csv(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(self.tool.subprocess, "run", side_effect=subprocess.TimeoutExpired([], 180)):
                result = self.tool.query(Path("processor"), Path("trace"), root, "fixture", "SELECT 1;")
            self.assertIsNone(result["returncode"])
            self.assertEqual(result["rows"], [])
            self.assertIn("query timed out", result["stderr"])
            self.assertTrue((root / "fixture.stderr.txt").exists())
            self.assertIn("query timed out", (root / "fixture.stderr.txt").read_text())
            self.assertEqual((root / "fixture.csv").read_text(), "")
            self.assertEqual((root / "fixture.sql").read_text(), "SELECT 1;\n")

    def test_failed_or_timed_out_counter_query_is_explicitly_unknown(self):
        processor_run = subprocess.run
        for timed_out in (False, True):
            def run_processor(command, **kwargs):
                if timed_out and "FROM counter c" in kwargs.get("input", ""):
                    raise subprocess.TimeoutExpired(command, 180)
                return processor_run(command, **kwargs)

            with self.subTest(timed_out=timed_out), tempfile.TemporaryDirectory() as directory:
                with patch.object(self.tool.subprocess, "run", side_effect=run_processor):
                    summary = run_fake_processor_analysis(self.tool, Path(directory), fail_counters=True)
                self.assertEqual(summary["queries"]["counters"]["returncode"], None if timed_out else 1)
                self.assertEqual(summary["counters"].get("status"), "unknown")
                self.assertEqual(summary["counters"]["tracks"], [])
                self.assertIn("counters query failed", summary["counters"]["reasons"])
                self.assertEqual(summary["cpu"]["status"], "observed")
                diagnostics = Path(directory) / "analysis" / "counters.stderr.txt"
                self.assertTrue(diagnostics.exists())
                self.assertIn("query timed out" if timed_out else "counters unavailable", diagnostics.read_text())
                self.assertEqual((diagnostics.with_name("counters.csv")).read_text(), "")

    def test_successful_counter_query_reports_observed_raw_samples(self):
        with tempfile.TemporaryDirectory() as directory:
            summary = run_fake_processor_analysis(self.tool, Path(directory))
        self.assertEqual(summary["counters"].get("status"), "observed")
        self.assertEqual(summary["counters"]["tracks"][0]["sample_max"], 500)

    def test_missing_requested_duration_cannot_certify_trace_completeness(self):
        with tempfile.TemporaryDirectory() as directory:
            summary = run_fake_processor_analysis(self.tool, Path(directory), include_duration=False)
        self.assertEqual(summary["completeness"]["status"], "unknown")
        self.assertEqual(summary["cpu"]["status"], "observed")

    def test_invalid_requested_durations_are_unknown_without_type_errors(self):
        for value in (None, "300", "NaN", {}, [], True, False, 0, -1, float("nan"), float("inf"), float("-inf")):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory:
                summary = run_fake_processor_analysis(self.tool, Path(directory), requested_duration=value)
                self.assertEqual(summary["completeness"]["status"], "unknown")
                self.assertEqual(summary["cpu"]["status"], "observed")

    def test_valid_requested_duration_and_successful_queries_can_pass_completeness_checks(self):
        with tempfile.TemporaryDirectory() as directory:
            summary = run_fake_processor_analysis(self.tool, Path(directory), requested_duration=300.0)
        self.assertEqual(summary["completeness"]["status"], "observed_checks_passed")
        self.assertEqual(summary["cpu"]["average_cores"], 0.5)

    def test_actual_drops_missing_scheduler_and_parse_errors_stay_invalid(self):
        for name, severity in (
            ("ftrace_cpu_overrun_end", "info"),
            ("ftrace_cpu_dropped_events_delta", "error"),
            ("ftrace_missing_event_id", "info"),
            ("ftrace_bundle_tokenizer_errors", "error"),
            ("sched_switch_parse_errors", "error"),
            ("traced_buf_chunks_overwritten", "info"),
            ("traced_buf_chunks_discarded", "info"),
            ("traced_buf_patches_failed", "info"),
            ("config_write_into_file_discard", "data_loss"),
            ("long_trace_mode_bytes_overwritten", "data_loss"),
        ):
            with self.subTest(name=name):
                rows = [{"name": name, "value": "1", "severity": severity}]
                self.assertTrue(self.tool.cpu_health_reasons(rows))

    def test_explicit_scheduler_setup_failure_is_invalid_even_with_some_sched(self):
        health = [{"name": "ftrace_setup_errors", "value": "1", "severity": "notice"}]
        metadata = [{"name": "ftrace_setup_errors", "str_value": "Failed to enable ftrace event: sched/sched_switch"}]
        self.assertTrue(self.tool.cpu_health_reasons(health, metadata))

    def test_capture_does_not_pull_when_process_visibility_is_denied(self):
        for stdout in ("", "PID NAME\n"):
            with self.subTest(stdout=stdout), tempfile.TemporaryDirectory() as directory:
                args = self.tool.make_parser().parse_args([
                    "capture", "--serial", "fixture-serial", "--package", "app.test",
                    "--theme", "clean", "--scenario", "interaction", "--build-label", "fixture",
                    "--out", str(Path(directory) / "capture"),
                ])
                replies = [
                    subprocess.CompletedProcess([], 0, "device\n", ""),
                    subprocess.CompletedProcess([], 0, "7578\n", ""),
                    subprocess.CompletedProcess([], 1, stdout, "ps: Permission denied\n"),
                ]
                with patch.object(self.tool, "adb_call", side_effect=replies), \
                     patch.object(self.tool, "device_snapshot", return_value={}), \
                     redirect_stdout(io.StringIO()):
                    with self.assertRaisesRegex(RuntimeError, "cannot verify owned Perfetto process state"):
                        self.tool.capture(args)
                metadata = json.loads((args.out / "capture.json").read_text())
                self.assertEqual(metadata["status"], "failed")
                self.assertNotIn("pull", metadata)
                self.assertFalse((args.out / "trace.pftrace").exists())

    def test_capture_waits_the_duration_even_when_process_is_no_longer_visible(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.tool.make_parser().parse_args([
                "capture", "--serial", "fixture-serial", "--package", "app.test",
                "--theme", "clean", "--scenario", "interaction", "--build-label", "fixture",
                "--out", str(Path(directory) / "capture"),
            ])
            clock = [0]

            def fake_adb(_args, command, **_kwargs):
                if command == ["get-state"]:
                    return subprocess.CompletedProcess(command, 0, "device\n", "")
                if command[:2] == ["shell", "perfetto"]:
                    return subprocess.CompletedProcess(command, 0, "7578\n", "")
                if command[:2] == ["shell", "ps"]:
                    return subprocess.CompletedProcess(command, 1, "PID NAME\n", "")
                if command[0] == "pull":
                    self.assertGreaterEqual(clock[0], 30, "pull must wait for the configured trace duration")
                    Path(command[2]).write_bytes(b"fixture-trace")
                    return subprocess.CompletedProcess(command, 0, "", "")
                if command[:2] == ["shell", "rm"]:
                    return subprocess.CompletedProcess(command, 0, "", "")
                raise AssertionError(f"unexpected boundary command: {command}")

            def advance_clock(seconds):
                clock[0] += seconds

            with patch.object(self.tool, "adb_call", side_effect=fake_adb), \
                 patch.object(self.tool, "device_snapshot", return_value={}), \
                 patch.object(self.tool.time, "monotonic", side_effect=lambda: clock[0]), \
                 patch.object(self.tool.time, "sleep", side_effect=advance_clock), \
                 redirect_stdout(io.StringIO()):
                self.tool.capture(args)
            metadata = json.loads((args.out / "capture.json").read_text())
            self.assertEqual(metadata["status"], "captured")
            self.assertEqual((args.out / "trace.pftrace").read_bytes(), b"fixture-trace")

    def test_remote_cleanup_failure_warns_without_losing_successful_capture(self):
        for timed_out in (False, True):
            with self.subTest(timed_out=timed_out), tempfile.TemporaryDirectory() as directory:
                args = self.tool.make_parser().parse_args([
                    "capture", "--serial", "fixture-serial", "--package", "app.test",
                    "--theme", "clean", "--scenario", "interaction", "--build-label", "fixture",
                    "--out", str(Path(directory) / "capture"),
                ])

                def fake_adb(_args, command, **_kwargs):
                    if command == ["get-state"]:
                        return subprocess.CompletedProcess(command, 0, "device\n", "")
                    if command[:2] == ["shell", "perfetto"]:
                        return subprocess.CompletedProcess(command, 0, "7578\n", "")
                    if command[:2] == ["shell", "ps"]:
                        return subprocess.CompletedProcess(command, 1, "PID NAME\n", "")
                    if command[0] == "pull":
                        Path(command[2]).write_bytes(b"fixture-trace")
                        return subprocess.CompletedProcess(command, 0, "", "")
                    if command[:2] == ["shell", "rm"]:
                        if timed_out:
                            raise subprocess.TimeoutExpired(command, 20)
                        return subprocess.CompletedProcess(command, 1, "", "rm: Permission denied\n")
                    raise AssertionError(f"unexpected boundary command: {command}")

                stdout, stderr = io.StringIO(), io.StringIO()
                with patch.object(self.tool, "adb_call", side_effect=fake_adb), \
                     patch.object(self.tool, "device_snapshot", return_value={}), \
                     patch.object(self.tool.time, "monotonic", side_effect=[0, 30]), \
                     redirect_stdout(stdout), redirect_stderr(stderr):
                    try:
                        self.tool.capture(args)
                    except subprocess.TimeoutExpired:
                        self.fail("cleanup timeout must preserve a successful capture")
                metadata = json.loads((args.out / "capture.json").read_text())
                self.assertIn("warning", stderr.getvalue())
                self.assertEqual(metadata["status"], "captured")
                self.assertEqual(metadata["remote_cleanup_returncode"], None if timed_out else 1)
                self.assertTrue(metadata["remote_cleanup_stderr"])
                self.assertIn(metadata["remote_trace"], stderr.getvalue())
                self.assertEqual(json.loads(stdout.getvalue().splitlines()[-1])["status"], "captured")
                self.assertEqual((args.out / "trace.pftrace").read_bytes(), b"fixture-trace")

    def test_actual_frame_tokens_are_deduplicated_across_layers(self):
        rows = [
            {"id": 1, "upid": 2, "surface_frame_token": 10, "ts": 0, "dur": 10_000_000, "jank_type": "None"},
            {"id": 2, "upid": 2, "surface_frame_token": 10, "ts": 0, "dur": 15_000_000, "jank_type": "App Deadline Missed"},
            {"id": 3, "upid": 2, "surface_frame_token": 11, "ts": 20_000_000, "dur": 10_000_000, "jank_type": "None"},
        ]
        result = self.tool.frame_summary(rows, 0, 100_000_000)
        self.assertEqual(result["actual_layer_slice_count"], 3)
        self.assertEqual(result["unique_app_frame_token_count"], 2)
        self.assertEqual(result["classified_jank_fraction"], 0.5)
        self.assertEqual(result["duration_p50_ms"], 12.5)

    def test_unknown_or_unkeyed_frames_do_not_improve_jank_denominator(self):
        rows = [
            {"upid": 2, "surface_frame_token": 1, "ts": 0, "dur": 5, "jank_type": None},
            {"upid": 2, "surface_frame_token": 2, "ts": 10, "dur": -1, "jank_type": "None"},
            {"upid": 2, "surface_frame_token": 0, "ts": 20, "dur": 10, "jank_type": "None"},
        ]
        result = self.tool.frame_summary(rows, 0, 100_000_000)
        self.assertEqual(result["classification_unknown_count"], 2)
        self.assertEqual(result["unkeyed_layer_slice_count"], 1)
        self.assertIsNone(result["classified_jank_fraction"])

    def test_unknown_jank_reason_is_still_a_reported_jank(self):
        result = self.tool.frame_summary([
            {"upid": 2, "surface_frame_token": 1, "ts": 0, "dur": 5, "jank_type": "Unknown"},
        ], 0, 100_000_000)
        self.assertEqual(result["classified_jank_fraction"], 1)
        self.assertEqual(result["unknown_jank_reason_count"], 1)

    def test_real_sql_matches_target_and_colon_subprocess_without_prefix_leak(self):
        db = sqlite3.connect(":memory:")
        db.row_factory = sqlite3.Row
        self.addCleanup(db.close)
        db.executescript("""
            CREATE TABLE process(upid INTEGER, pid INTEGER, name TEXT, cmdline TEXT);
            CREATE TABLE thread(utid INTEGER, upid INTEGER, tid INTEGER, name TEXT);
            CREATE TABLE sched(id INTEGER, ts INTEGER, dur INTEGER, cpu INTEGER, utid INTEGER);
            INSERT INTO process VALUES (1,11,'app.test','app.test'),
                (2,12,'app.test:audio','app.test:audio'),
                (3,13,'app.test.fake','app.test.fake');
            INSERT INTO thread VALUES (1,1,11,'main'),(2,2,12,'audio'),(3,3,13,'main');
            INSERT INTO sched VALUES (1,0,100,0,1),(2,50,100,1,2),(3,0,100,2,3);
        """)
        queries = self.tool.analysis_queries("app.test", 0, 200)
        rows = list(db.execute(queries["cpu-slices"]))
        self.assertEqual([row["pid"] for row in rows], [11, 12])

    def test_real_frame_sql_keeps_complete_token_envelopes_across_window_edges(self):
        db = sqlite3.connect(":memory:")
        db.row_factory = sqlite3.Row
        self.addCleanup(db.close)
        db.executescript("""
            CREATE TABLE process(upid INTEGER, pid INTEGER, name TEXT, cmdline TEXT);
            CREATE TABLE actual_frame_timeline_slice(
                id INTEGER, ts INTEGER, dur INTEGER, upid INTEGER, surface_frame_token INTEGER,
                display_frame_token INTEGER, layer_name TEXT, jank_type TEXT,
                on_time_finish INTEGER, present_type TEXT);
            INSERT INTO process VALUES (1,11,'app.test','app.test'),
                (2,12,'app.test:audio','app.test:audio'),
                (3,13,'app.test.fake','app.test.fake');
            INSERT INTO actual_frame_timeline_slice VALUES
                (1,10000000,10000000,1,10,1,'early','App Deadline Missed',0,'Late Present'),
                (2,120000000,10000000,1,10,1,'inside','None',1,'On-time Present'),
                (3,180000000,10000000,1,11,2,'inside','None',1,'On-time Present'),
                (4,210000000,10000000,1,11,2,'late','App Deadline Missed',0,'Late Present'),
                (5,140000000,10000000,1,12,3,'contained','None',1,'On-time Present'),
                (6,20000000,10000000,1,13,4,'unrelated','None',1,'On-time Present'),
                (7,20000000,10000000,2,12,5,'other-process','None',1,'On-time Present'),
                (8,140000000,10000000,3,10,6,'prefix-leak','None',1,'On-time Present'),
                (9,140000000,10000000,1,0,7,'unkeyed','None',1,'On-time Present'),
                (10,20000000,10000000,1,0,8,'outside-unkeyed','None',1,'On-time Present'),
                (11,150000000,10000000,1,NULL,9,'null-token','None',1,'On-time Present');
        """)
        query = self.tool.analysis_queries("app.test", 100_000_000, 200_000_000)["actual-frames"]
        rows = [dict(row) for row in db.execute(query)]
        self.assertEqual([row["id"] for row in rows], [1, 2, 5, 9, 11, 3, 4])
        result = self.tool.frame_summary(rows, 100_000_000, 200_000_000)
        self.assertEqual(result["unique_app_frame_token_count"], 3)
        self.assertEqual(result["classification_unknown_count"], 2)
        self.assertEqual(result["non_jank_token_count"], 1)
        self.assertEqual(result["jank_token_count"], 0)
        self.assertEqual(result["unkeyed_layer_slice_count"], 2)
        self.assertEqual(result["duration_max_ms"], 10)

    def test_csv_nulls_and_quoted_names_are_preserved(self):
        parsed = self.tool.parse_csv('"name","value"\n"gpu, freq",123\n"[NULL]",[NULL]\n')
        self.assertEqual(parsed[0], {"name": "gpu, freq", "value": "123"})
        self.assertIsNone(parsed[1]["value"])

    def test_capture_requires_explicit_serial_before_any_device_access(self):
        with self.assertRaises(SystemExit), redirect_stderr(io.StringIO()):
            self.tool.make_parser().parse_args([
                "capture", "--package", "app.test", "--theme", "clean",
                "--scenario", "cold-start", "--build-label", "test", "--out", "unused",
            ])

    def test_gpu_frequency_is_never_relabelled_as_utilization_or_watts(self):
        rows = [{"track_id": "1", "name": "gpufreq", "unit": "MHz", "value": "500"}]
        result = self.tool.counter_summary(rows)
        self.assertEqual(result["gpu_utilization"]["status"], "unknown")
        self.assertEqual(result["device_power_watts"]["status"], "unknown")
        self.assertEqual(result["tracks"][0]["sample_max"], 500)
        self.assertEqual(result["tracks"][0]["unit"], "MHz")

    @unittest.skipUnless(os.environ.get("NERIPLAYER_TRACE_PROCESSOR"), "optional local trace processor not specified")
    def test_official_processor_analyzes_hand_calculated_scheduler_trace(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            trace = root / "synthetic.json"
            trace.write_text(json.dumps({
                "traceEvents": [{"ph": "M", "pid": 100, "tid": 100,
                                 "name": "process_name", "args": {"name": "app.test"}}],
                "systemTraceEvents": """# tracer: nop
 app.test-100 ( 100) [000] .... 1.000000: tracing_mark_write: B|100|cpu-window
 <idle>-0 (-----) [000] .... 1.000000: sched_switch: prev_comm=swapper/0 prev_pid=0 prev_prio=120 prev_state=R ==> next_comm=app.test next_pid=100 next_prio=120
 app.test-100 ( 100) [000] .... 1.100000: sched_switch: prev_comm=app.test prev_pid=100 prev_prio=120 prev_state=S ==> next_comm=swapper/0 next_pid=0 next_prio=120
 <idle>-0 (-----) [000] .... 1.200000: sched_switch: prev_comm=swapper/0 prev_pid=0 prev_prio=120 prev_state=R ==> next_comm=app.test next_pid=100 next_prio=120
 app.test-100 ( 100) [000] .... 1.250000: sched_switch: prev_comm=app.test prev_pid=100 prev_prio=120 prev_state=S ==> next_comm=swapper/0 next_pid=0 next_prio=120
 app.test-100 ( 100) [000] .... 1.300000: tracing_mark_write: E|100
""",
            }))
            args = argparse.Namespace(
                trace=trace, processor=Path(os.environ["NERIPLAYER_TRACE_PROCESSOR"]),
                package="app.test", out=root / "analysis", start_ms=0, duration_ms=300,
                capture_metadata=None,
            )
            with redirect_stdout(io.StringIO()):
                self.tool.analyze(args)
            summary = json.loads((args.out / "summary.json").read_text())
            self.assertEqual(summary["cpu"]["status"], "observed")
            self.assertEqual(summary["cpu"]["cpu_time_ms"], 150)
            self.assertEqual(summary["cpu"]["average_cores"], 0.5)
            self.assertEqual(summary["cpu"]["peak_100ms_cores"], 1)
            metadata = root / "capture.json"
            metadata.write_text(json.dumps({
                "package": "app.test", "status": "captured",
                "trace_sha256": self.tool.sha256(trace), "requested_duration_ms": 30000,
            }))
            args.capture_metadata = metadata
            args.out = root / "truncated-analysis"
            with redirect_stdout(io.StringIO()):
                self.tool.analyze(args)
            truncated = json.loads((args.out / "summary.json").read_text())
            self.assertEqual(truncated["cpu"]["status"], "unknown")
            self.assertIsNone(truncated["cpu"]["average_cores"])
            self.assertTrue(any("shorter than requested" in reason for reason in truncated["cpu"]["reasons"]))


if __name__ == "__main__":
    unittest.main()
