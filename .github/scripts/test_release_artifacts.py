import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid
import zipfile


SCRIPT = Path(__file__).with_name("release_artifacts.py")
BUILD_UUID = "0723ec81-0174-4b3c-afb1-e4f8803f86e4"
BUILD_TIMESTAMP = "1792585200000"


class ReleaseArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.environment = {
            **os.environ,
            "NERI_BUILD_UUID": BUILD_UUID,
            "NERI_BUILD_TIMESTAMP": BUILD_TIMESTAMP,
            "NERI_BUILD_VERSION_TIMESTAMP": "10212020",
            "NERI_SIGNING_MODE": "unsigned",
            "GITHUB_REPOSITORY": "kanospace/NeriPlayer-dev-test",
            "GITHUB_RUN_ID": "1234",
            "GITHUB_RUN_ATTEMPT": "2",
        }
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(
            ["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
             "commit", "--allow-empty", "-qm", "fixture"], cwd=self.root, check=True,
        )
        self.build_config = self.root / "BuildConfig.java"
        self.build_config.write_text(
            f'public static final String BUILD_UUID = "{BUILD_UUID}";\n'
            f'public static final long BUILD_TIMESTAMP = {BUILD_TIMESTAMP}L;\n'
            'public static final String VERSION_NAME = "abcdef12.10212020";\n',
            encoding="utf-8",
        )
        self.apk_dir = self.root / "apks"
        self.apk_dir.mkdir()
        self.apk = self.apk_dir / "NeriPlayer-abcdef12.10212020-arm64-v8a.apk"
        self.write_apk(BUILD_UUID)

    def write_apk(self, build_uuid, abis=("arm64-v8a",)):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("classes.dex", b"dex\n035\x00" + build_uuid.encode() + b"\x00")
            for abi in abis:
                archive.writestr(f"lib/{abi}/libneriplayer.so", b"native fixture")

    def run_script(self, *arguments, environment=None):
        return subprocess.run(
            [sys.executable, "-B", str(SCRIPT), *arguments], cwd=self.root,
            env=environment or self.environment, text=True, capture_output=True,
        )

    def stage(self, *arguments, environment=None):
        return self.run_script(
            "stage", "--apk-dir", str(self.apk_dir), "--output-dir", "release-assets",
            "--build-config", str(self.build_config), *arguments, environment=environment,
        )

    def test_prepare_produces_shared_values_for_gradle_and_workflow(self):
        output = self.root / "outputs"
        result = self.run_script("prepare", "--github-output", str(output))
        self.assertEqual(result.returncode, 0, result.stderr)
        values = dict(line.split("=", 1) for line in output.read_text().splitlines())
        self.assertEqual(str(uuid.UUID(values["build_uuid"])), values["build_uuid"])
        self.assertGreater(int(values["build_timestamp"]), 0)
        self.assertRegex(values["build_version_timestamp"], r"^[0-9]{8}$")

    def test_stage_records_apk_metadata_and_verifiable_checksums(self):
        result = self.stage("--expected-abis", "arm64-v8a")
        self.assertEqual(result.returncode, 0, result.stderr)
        destination = self.root / "release-assets"
        metadata = json.loads((destination / "build-metadata.json").read_text())
        self.assertEqual(metadata["build_uuid"], BUILD_UUID)
        self.assertEqual(metadata["build_timestamp"], int(BUILD_TIMESTAMP))
        self.assertEqual(metadata["build_version"], "abcdef12.10212020")
        self.assertEqual(metadata["build_version_timestamp"], "10212020")
        self.assertEqual(metadata["workflow_run"], "https://github.com/kanospace/NeriPlayer-dev-test/actions/runs/1234/attempts/2")
        self.assertEqual(metadata["signing_mode"], "unsigned")
        self.assertEqual(metadata["artifacts"][0]["name"], self.apk.name)
        entries = (destination / "SHA256SUMS.txt").read_text().splitlines()
        self.assertEqual(len(entries), 3)
        for entry in entries:
            digest, name = entry.split("  ", 1)
            self.assertEqual(hashlib.sha256((destination / name).read_bytes()).hexdigest(), digest)

    def test_stage_rejects_unknown_or_missing_uuid_before_creating_assets(self):
        for value in ("unknown", "1-1-1-1-1", ""):
            with self.subTest(value=value):
                result = self.stage(environment={**self.environment, "NERI_BUILD_UUID": value})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("NERI_BUILD_UUID", result.stderr)
                self.assertFalse((self.root / "release-assets").exists())

    def test_stage_rejects_build_config_that_ignored_uuid_injection(self):
        self.build_config.write_text(self.build_config.read_text().replace(BUILD_UUID, "11111111-1111-4111-8111-111111111111"))
        result = self.stage()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("BuildConfig", result.stderr)
        self.assertFalse((self.root / "release-assets").exists())

    def test_stage_rejects_apk_with_different_uuid(self):
        self.write_apk("11111111-1111-4111-8111-111111111111")
        result = self.stage()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("UUID", result.stderr)
        self.assertFalse((self.root / "release-assets").exists())

    def test_stage_rejects_build_config_with_different_timestamp(self):
        self.build_config.write_text(self.build_config.read_text().replace(BUILD_TIMESTAMP, "1792585200001"))
        result = self.stage()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("BuildConfig", result.stderr)

    def test_stage_rejects_version_timestamp_that_differs_from_build_config(self):
        result = self.stage(environment={**self.environment, "NERI_BUILD_VERSION_TIMESTAMP": "10212021"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("NERI_BUILD_VERSION_TIMESTAMP", result.stderr)

    def test_stage_rejects_missing_abi(self):
        result = self.stage("--expected-abis", "arm64-v8a,x86_64")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("x86_64", result.stderr)

    def test_stage_identifies_single_abi_without_a_filename_suffix(self):
        self.apk = self.apk.rename(self.apk_dir / "NeriPlayer-abcdef12.10212020.apk")
        result = self.stage()
        self.assertEqual(result.returncode, 0, result.stderr)
        metadata = json.loads((self.root / "release-assets/build-metadata.json").read_text())
        self.assertEqual(metadata["artifacts"][0]["abi"], "arm64-v8a")
        self.assertEqual(metadata["artifacts"][0]["abis"], ["arm64-v8a"])

    def test_stage_rejects_filename_abi_that_differs_from_native_libraries(self):
        self.write_apk(BUILD_UUID, ("x86_64",))
        result = self.stage()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ABI", result.stderr)
        self.assertFalse((self.root / "release-assets").exists())

    def test_stage_rejects_split_filename_with_multiple_native_abis(self):
        self.write_apk(BUILD_UUID, ("arm64-v8a", "x86_64"))
        result = self.stage()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ABI", result.stderr)

    def test_stage_marks_only_multiple_native_abis_as_universal(self):
        self.apk = self.apk.rename(self.apk_dir / "NeriPlayer-abcdef12.10212020.apk")
        self.write_apk(BUILD_UUID, ("arm64-v8a", "x86_64"))
        result = self.stage()
        self.assertEqual(result.returncode, 0, result.stderr)
        metadata = json.loads((self.root / "release-assets/build-metadata.json").read_text())
        self.assertEqual(metadata["artifacts"][0]["abi"], "universal")
        self.assertEqual(metadata["artifacts"][0]["abis"], ["arm64-v8a", "x86_64"])

    def test_stage_rejects_missing_or_unknown_native_abis(self):
        for abis in ((), ("unknown",)):
            with self.subTest(abis=abis):
                self.write_apk(BUILD_UUID, abis)
                result = self.stage()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("ABI", result.stderr)
                self.assertFalse((self.root / "release-assets").exists())

    def test_stage_accepts_exactly_four_single_abi_splits(self):
        for abi in ("armeabi-v7a", "x86", "x86_64"):
            self.apk = self.apk_dir / f"NeriPlayer-abcdef12.10212020-{abi}.apk"
            self.write_apk(BUILD_UUID, (abi,))
        result = self.stage("--expected-abis", "arm64-v8a,armeabi-v7a,x86,x86_64")
        self.assertEqual(result.returncode, 0, result.stderr)
        metadata = json.loads((self.root / "release-assets/build-metadata.json").read_text())
        self.assertEqual(len(metadata["artifacts"]), 4)
        self.assertTrue(all(artifact["abis"] == [artifact["abi"]] for artifact in metadata["artifacts"]))

    def test_notes_preserves_changes_and_identifies_unsigned_test_artifacts(self):
        result = self.stage()
        self.assertEqual(result.returncode, 0, result.stderr)
        notes = self.root / "release-notes.md"
        notes.write_text("## Changes\n- Example fix\n")
        result = self.run_script("notes", "--notes-file", str(notes), "--metadata-file", "release-assets/build-metadata.json")
        self.assertEqual(result.returncode, 0, result.stderr)
        content = notes.read_text()
        self.assertIn("Example fix", content)
        self.assertIn(BUILD_UUID, content)
        self.assertIn("/attempts/2", content)
        self.assertIn("Unsigned test", content)
        self.assertIn("sha256sum -c SHA256SUMS.txt", content)


if __name__ == "__main__":
    unittest.main()
