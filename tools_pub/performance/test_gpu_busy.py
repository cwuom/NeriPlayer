import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch


class GpuBusyTest(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).with_name("gpu_busy.py")
        self.assertTrue(path.is_file(), "GPU raw sampler is not implemented")
        spec = importlib.util.spec_from_file_location("gpu_busy", path)
        self.tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.tool)

    def record(self, index, ms, raw, returncode=0):
        return self.tool.sample_record(index, ms * 1_000_000,
                                       (ms + 10) * 1_000_000,
                                       raw, "", returncode)

    def test_distinct_window_estimate_is_time_weighted_and_first_pair_is_excluded(self):
        records = [self.record(0, 0, "800000 1000000"),
                   self.record(1, 250, "800000 1000000"),
                   self.record(2, 1000, "200000 1000000"),
                   self.record(3, 1250, "200000 1000000"),
                   self.record(4, 3000, "800000 2000000")]
        result = self.tool.summarize(records)
        self.assertEqual(result["status"], "diagnostic_only")
        self.assertIsNone(result["gpu_utilization_pct"])
        estimate = result["diagnostic_distinct_pair_estimate"]
        self.assertEqual(estimate["pair_count"], 2)
        self.assertEqual(estimate["busy_us_assuming_reference_semantics"], 1_000_000)
        self.assertEqual(estimate["total_us_assuming_reference_semantics"], 3_000_000)
        self.assertAlmostEqual(estimate["weighted_ratio_pct"], 100 / 3)
        self.assertEqual(result["duplicate_pair_count"], 2)
        self.assertAlmostEqual(result["continuity"]["distinct_total_to_host_span_ratio"], 1)

    def test_zero_invalid_and_failed_reads_are_unknown_not_zero_utilization(self):
        raws = ["0 0", "20 10", "-1 10", "1", "1 2 3", "cat: denied"]
        records = [self.record(index, index * 250, raw)
                   for index, raw in enumerate(raws)]
        records.append(self.record(6, 1500, "1 1000000", returncode=1))
        result = self.tool.summarize(records)
        self.assertEqual(result["status"], "unknown")
        self.assertEqual(result["invalid_sample_count"], len(records))
        self.assertIsNone(result["diagnostic_distinct_pair_estimate"]["weighted_ratio_pct"])
        self.assertIsNone(result["gpu_utilization_pct"])
        self.assertTrue(all(row["reasons"] for row in result["windows"]))

    def test_poll_gap_and_unmatched_total_are_kept_as_continuity_diagnostics(self):
        result = self.tool.summarize([
            self.record(0, 0, "100000 1000000"),
            self.record(1, 2500, "500000 1000000"),
            self.record(2, 2750, "1 100"),
        ])
        reasons = " ".join(result["continuity"]["reasons"])
        self.assertIn("poll_gap_may_hide_cache_updates", reasons)
        self.assertIn("total_below_reference_update_threshold", reasons)
        self.assertEqual(result["continuity"]["status"], "unknown")
        self.assertIsNone(result["gpu_utilization_pct"])

    def test_same_pair_after_read_failure_is_not_counted_as_a_new_window(self):
        result = self.tool.summarize([
            self.record(0, 0, "100000 1000000"),
            self.record(1, 250, "", returncode=1),
            self.record(2, 500, "100000 1000000"),
            self.record(3, 1000, "300000 1000000"),
        ])
        self.assertEqual(result["diagnostic_distinct_pair_estimate"]["pair_count"], 1)
        self.assertEqual(result["invalid_sample_count"], 1)
        self.assertIn("invalid_read", " ".join(result["continuity"]["reasons"]))

    def test_reported_totals_cannot_silently_exceed_the_host_span(self):
        result = self.tool.summarize([
            self.record(0, 0, "1 1000000"),
            self.record(1, 100, "2 1000000"),
            self.record(2, 200, "3 1000000"),
        ])
        self.assertIn("distinct_totals_exceed_host_span_plus_one_reported_window",
                      result["continuity"]["reasons"])
        self.assertIsNone(result["gpu_utilization_pct"])

    def test_timeout_keeps_partial_bytes_as_raw_text_and_marks_sample_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.tool.make_parser().parse_args([
                "--serial", "fixture-device", "--seconds", "0.1", "--interval-ms", "100",
                "--out", str(Path(directory) / "result"),
            ])
            clock = [0]

            def fake_adb(_args, command):
                if command == ["shell", "getprop", "ro.serialno"]:
                    return subprocess.CompletedProcess(command, 0, "fixture-device\n", "")
                clock[0] += 200_000_000
                raise subprocess.TimeoutExpired(command, 5, output=b"123 1000000\n", stderr=b"partial")

            with patch.object(self.tool, "adb_call", side_effect=fake_adb), \
                 patch.object(self.tool.time, "monotonic_ns", side_effect=lambda: clock[0]):
                self.tool.capture(args)
            row = json.loads((args.out / "samples.jsonl").read_text().splitlines()[0])
            self.assertEqual(row["stdout"], "123 1000000\n")
            self.assertEqual(row["stderr"], "partial")
            self.assertEqual(row["status"], "unknown")
            self.assertIn("TimeoutExpired", row["error"])

    def test_identity_mismatch_stops_before_reading_gpu_node_and_keeps_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.tool.make_parser().parse_args([
                "--serial", "tcp-device", "--expected-serial", "expected-device",
                "--seconds", "1", "--out", str(Path(directory) / "result"),
            ])
            with patch.object(self.tool, "adb_call", return_value=
                              subprocess.CompletedProcess([], 0, "other-device\n", "")) as adb:
                with self.assertRaisesRegex(RuntimeError, "hardware serial mismatch"):
                    self.tool.capture(args)
            self.assertEqual(adb.call_args.args[1], ["shell", "getprop", "ro.serialno"])
            self.assertEqual(adb.call_count, 1)
            metadata = json.loads((args.out / "capture.json").read_text())
            self.assertEqual(metadata["status"], "failed")
            self.assertEqual(metadata["identity"]["hardware_serial"], "other-device")
            self.assertFalse((args.out / "samples.jsonl").exists())

    def test_capture_preserves_raw_output_and_only_uses_read_only_commands(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.tool.make_parser().parse_args([
                "--serial", "fixture-device", "--seconds", "0.5", "--interval-ms", "250",
                "--out", str(Path(directory) / "result"),
            ])
            clock = [0]
            commands = []

            def fake_adb(_args, command):
                commands.append(command)
                if command == ["shell", "getprop", "ro.serialno"]:
                    return subprocess.CompletedProcess(command, 0, "fixture-device\n", "")
                self.assertEqual(command, ["shell", "cat", self.tool.NODE])
                clock[0] += 10_000_000
                return subprocess.CompletedProcess(command, 0, "123 1000000\n", "")

            with patch.object(self.tool, "adb_call", side_effect=fake_adb), \
                 patch.object(self.tool.time, "monotonic_ns", side_effect=lambda: clock[0]), \
                 patch.object(self.tool.time, "sleep", side_effect=
                              lambda seconds: clock.__setitem__(0, clock[0] + int(seconds * 1e9))):
                self.tool.capture(args)
            rows = [json.loads(line) for line in (args.out / "samples.jsonl").read_text().splitlines()]
            self.assertGreaterEqual(len(rows), 2)
            self.assertEqual(rows[0]["stdout"], "123 1000000\n")
            self.assertEqual(rows[0]["host_end_monotonic_ns"] -
                             rows[0]["host_start_monotonic_ns"], 10_000_000)
            metadata = json.loads((args.out / "capture.json").read_text())
            self.assertEqual(metadata["status"], "captured")
            self.assertEqual(metadata["scope"], "whole_device_gpu_not_process_attributable")

    def test_cli_requires_serial_duration_and_output_and_rejects_invalid_duration(self):
        for argv in ([], ["--serial", "x", "--out", "unused"],
                     ["--serial", "x", "--seconds", "nan", "--out", "unused"],
                     ["--serial", "x", "--seconds", "-1", "--out", "unused"]):
            with self.subTest(argv=argv), redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    self.tool.make_parser().parse_args(argv)


if __name__ == "__main__":
    unittest.main()
