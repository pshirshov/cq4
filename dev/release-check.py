"""Behavioral Active Effectual / controlled filesystem: release provenance admission."""
import json
from pathlib import Path
import runpy
import shutil
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.package = runpy.run_path(str(ROOT / "dev/package"))
        self.temporary = tempfile.TemporaryDirectory(prefix="cq-release-check-")
        self.directory = Path(self.temporary.name)
        self.native = self.directory / "native"
        self.native.mkdir()
        shutil.copy2(Path(sys.executable).resolve(), self.native / "cq")
        (self.native / "cq").chmod(0o700)
        self.sources = self.package["runtime_sources"](ROOT)
        self.write(self.native / "result.json", {"check": "native", "status": "passed"})
        self.write(self.native / "source-sha256.json", self.sources)
        self.write(self.native / "native-artifact.json", {"sha256": self.package["digest"](self.native / "cq")})
        self.release = self.directory / "release"
        (self.release / "bin").mkdir(parents=True)
        for name in ["cq", "cq-guardian"]:
            shutil.copy2(self.native / "cq", self.release / "bin" / name)
        for name in ["runtime.nar", "runtime-paths.txt"]:
            (self.release / name).write_text("controlled fixture; not an installable distribution\n")
        self.manifest = {"modelVersion": "0.1.0", "platform": "x86_64-linux", "runtimeSourceSha256": self.sources,
                         "filesSha256": {str(p.relative_to(self.release)): self.package["digest"](p)
                                         for p in self.release.rglob("*") if p.is_file()}}
        self.write(self.release / "manifest.json", self.manifest)

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, path, value):
        path.write_text(json.dumps(value))

    def test_native_admission_rejects_failed_gate_changed_binary_and_changed_sources(self):
        self.package["verified_native"](self.native)
        self.write(self.native / "result.json", {"check": "native", "status": "failed"})
        with self.assertRaisesRegex(AssertionError, "must pass"):
            self.package["verified_native"](self.native)
        self.write(self.native / "result.json", {"check": "native", "status": "passed"})
        for name in ["models/missing.baboon", "project/Build.scala"]:
            with self.subTest(name=name):
                self.write(self.native / "source-sha256.json", {**self.sources, name: "unobserved"})
                with self.assertRaisesRegex(AssertionError, "inputs differ"):
                    self.package["verified_native"](self.native)
        self.write(self.native / "source-sha256.json", self.sources)
        with (self.native / "cq").open("ab") as stream:
            stream.write(b"changed")
        with self.assertRaisesRegex(AssertionError, "executable changed"):
            self.package["verified_native"](self.native)

    def test_relocation_preserves_identity_and_missing_or_changed_files_fail(self):
        _, _, before = self.package["load_release"](self.release)
        moved = self.directory / "relocated"
        self.release.rename(moved)
        command, guardian, after = self.package["load_release"](moved)
        self.assertEqual(command, [str(moved / "bin/cq")])
        self.assertEqual(guardian, moved / "bin/cq-guardian")
        self.assertEqual({k: v for k, v in before.items() if k != "directory"}, {k: v for k, v in after.items() if k != "directory"})
        (moved / "runtime.nar").write_text("changed")
        with self.assertRaisesRegex(AssertionError, "file changed"):
            self.package["load_release"](moved)
        (moved / "runtime.nar").unlink()
        with self.assertRaises(FileNotFoundError):
            self.package["load_release"](moved)

    def test_consumer_uses_explicit_artifact_without_building_and_enforces_prior_identity(self):
        class NoBuild:
            def generate(self):
                raise AssertionError("Unexpected build")

        runtime = runpy.run_path(str(ROOT / "dev/consumer-eval"))["runtime"]
        command, guardian, identity = runtime(NoBuild(), self.release, None)
        self.assertEqual(command, [str(self.release / "bin/cq")])
        self.assertEqual(guardian, self.release / "bin/cq-guardian")
        self.assertEqual(runtime(NoBuild(), self.release, identity), (command, guardian, identity))
        with self.assertRaisesRegex(AssertionError, "differs from the retained"):
            runtime(NoBuild(), self.release, {**identity, "guardianSha256": "different"})
        with self.assertRaisesRegex(AssertionError, "explicit --release"):
            runtime(NoBuild(), None, identity)

    def test_restored_assessment_can_relocate_the_same_artifact(self):
        evaluation = runpy.run_path(str(ROOT / "dev/consumer-eval"))
        _, _, original = self.package["load_release"](self.release)
        relocated = {**original, "directory": str(self.directory / "relocated")}
        self.assertTrue(evaluation["same_runtime"](original, relocated))
        self.assertFalse(evaluation["same_runtime"](original, {**relocated, "guardianSha256": "changed"}))
        self.assertFalse(evaluation["same_runtime"](original, None))
        self.assertTrue(evaluation["same_runtime"](None, None))


if __name__ == "__main__":
    unittest.main()
