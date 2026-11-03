import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("report_candidate", REPO / ".github/scripts/android-report-candidate.py")
candidate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(candidate)
EVIDENCE = Path(os.environ.get("FINEXY_REPORT_CANDIDATE_TEST_EVIDENCE",
                             str(REPO / "artifacts/android-report-host-runner-20261030/unit-fixtures"))).resolve()
if not EVIDENCE.is_relative_to((REPO / "artifacts").resolve()):
    raise ValueError("Synthetic candidate test evidence must remain within workspace artifacts")
EVIDENCE.mkdir(parents=True, exist_ok=True)


class SourceAndApkBindingTest(unittest.TestCase):
    def setUp(self):
        # Retain these tiny owned fixtures as evidence; do not delete other artifacts.
        self.root = Path(tempfile.mkdtemp(prefix="synthetic-", dir=EVIDENCE)).resolve()
        self.assertTrue(self.root.is_relative_to(EVIDENCE.resolve()))
        self.archive = self.root / "artifacts/candidate"
        self.archive.mkdir(parents=True)
        self.source = self.root / "android/app/src/main/Synthetic.kt"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("synthetic compiled version", encoding="utf-8")
        entries = {"android/app/src/main/Synthetic.kt": candidate.digest(self.source)}
        self.manifest = {"files": entries, "sha256": candidate.manifest_digest(entries)}
        for name in ("source-before.json", "source-after.json"):
            (self.archive / name).write_text(json.dumps(self.manifest), encoding="utf-8")

    def test_current_view_requires_exact_compiled_bytes(self):
        proof = candidate.verify_source_view(self.root, self.archive, self.manifest, False)
        self.assertTrue(proof["currentSourceVerified"])
        self.source.write_text("later uncompiled editing", encoding="utf-8")
        with self.assertRaises(candidate.CandidateError):
            candidate.verify_source_view(self.root, self.archive, self.manifest, False)

    def test_extra_android_file_cannot_masquerade_as_compiled(self):
        (self.source.parent / "Later.kt").write_text("new source", encoding="utf-8")
        with self.assertRaises(candidate.CandidateError):
            candidate.verify_source_view(self.root, self.archive, self.manifest, False)

    def test_manifest_alone_cannot_authorize_frozen_mode(self):
        with self.assertRaises(candidate.CandidateError):
            candidate.verify_source_view(self.root, self.archive, self.manifest, True)

    def test_frozen_copy_is_exact_and_does_not_claim_current_source(self):
        snapshot = self.archive / "source-snapshot"
        shutil.copytree(self.root / "android", snapshot / "android")
        self.source.write_text("later working tree change", encoding="utf-8")
        proof = candidate.verify_source_view(self.root, self.archive, self.manifest, True)
        self.assertTrue(proof["sourceViewVerified"])
        self.assertFalse(proof["currentSourceVerified"])
        (snapshot / "extra.txt").write_text("not in manifest", encoding="utf-8")
        with self.assertRaises(candidate.CandidateError):
            candidate.verify_source_view(self.root, self.archive, self.manifest, True)

    def test_changed_snapshot_content_or_missing_file_is_rejected(self):
        snapshot = self.archive / "source-snapshot"
        shutil.copytree(self.root / "android", snapshot / "android")
        (snapshot / "android/app/src/main/Synthetic.kt").write_text("modified archive", encoding="utf-8")
        with self.assertRaises(candidate.CandidateError):
            candidate.verify_source_view(self.root, self.archive, self.manifest, True)

    def test_gate_before_after_and_digest_must_match(self):
        self.assertEqual(self.manifest, candidate.checked_manifest(self.archive))
        changed = {**self.manifest, "sha256": "0" * 64}
        (self.archive / "source-after.json").write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaises(candidate.CandidateError):
            candidate.checked_manifest(self.archive)
        (self.archive / "source-before.json").write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaises(candidate.CandidateError):
            candidate.checked_manifest(self.archive)

    def test_candidate_directory_cannot_escape_artifacts(self):
        with self.assertRaises(candidate.CandidateError):
            candidate.artifact_path(self.root, "android")

    def test_main_and_test_must_share_gate_hashes_and_directory(self):
        app, test = self.archive / "app-qa.apk", self.archive / "app-qa-androidTest.apk"
        app.write_bytes(b"synthetic main archive")
        test.write_bytes(b"synthetic test archive")
        gate = {"sourceSHA256": "a" * 64, "apks": {path.name: {"sha256": candidate.digest(path)} for path in (app, test)}}
        proof = {"state": "passed", "sourceSHA256": gate["sourceSHA256"], "packages": {
            app.name: {"sha256": candidate.digest(app), "package": "com.finexy.mobile.qa"},
            test.name: {"sha256": candidate.digest(test), "package": "com.finexy.mobile.qa.test"}},
            "standardExceptionCollectorPreserved": True, "qaTargetFrameworkNotDuplicatedInTestApk": True,
            "qaOnlyTestFramework": True}
        candidate.verify_pair(self.archive, gate, proof, app, test)
        other = self.root / "artifacts/other"
        other.mkdir()
        different_test = other / test.name
        different_test.write_bytes(test.read_bytes())
        with self.assertRaises(candidate.CandidateError):
            candidate.verify_pair(self.archive, gate, proof, app, different_test)
        proof["packages"][test.name]["sha256"] = hashlib.sha256(b"other compiled candidate").hexdigest()
        with self.assertRaises(candidate.CandidateError):
            candidate.verify_pair(self.archive, gate, proof, app, test)

    def copy_source_review_registry(self):
        path = self.root / candidate.SOURCE_REVISIONS
        path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(REPO / candidate.SOURCE_REVISIONS, path)
        return path

    def test_complete_candidate_source_review_needs_no_historical_apk_metadata(self):
        self.copy_source_review_registry()
        source_sha = "79a17ba719cf99f5810467e0afa13a8a015d1fd174d28917e7ecc71bac7e3b31"
        reviewed = candidate.reviewed_test_metadata(self.root, source_sha)
        self.assertEqual(source_sha, reviewed["sourceSHA256"])
        self.assertEqual(candidate.EXPECTED_TEST_METHODS, set(reviewed["testMethodsInDex"]))
        self.assertFalse((self.root / candidate.METADATA).exists())

    def test_unreviewed_report_source_cannot_reuse_a_three_method_registry(self):
        self.copy_source_review_registry()
        with self.assertRaises(candidate.CandidateError):
            candidate.reviewed_test_metadata(self.root, "f" * 64)

    def test_same_count_different_method_or_source_scope_is_rejected(self):
        path = self.copy_source_review_registry()
        original = candidate.read_json(path)
        source_sha = next(iter(original["revisions"]))
        for field, value in [("testSource", "android/AnotherTest.kt"),
                             ("testClass", "Lcom/finexy/mobile/AnotherTest;"),
                             ("testMethods", ["unrelatedMethod", *original["testMethods"][1:]])]:
            with self.subTest(field=field):
                path.write_text(json.dumps({**original, field: value}), encoding="utf-8")
                with self.assertRaises(candidate.CandidateError):
                    candidate.reviewed_test_metadata(self.root, source_sha)

    def test_sdk_tool_selection_uses_runner_sdk_and_rejects_missing_tool(self):
        sdk = self.root / "runner-sdk"
        tool = sdk / "build-tools/35.0.0" / ("aapt2.exe" if os.name == "nt" else "aapt2")
        tool.parent.mkdir(parents=True)
        tool.write_bytes(b"synthetic aapt path fixture; never executed")
        with patch.dict(os.environ, {"ANDROID_SDK_ROOT": str(sdk), "FINEXY_ANDROID_AAPT2": ""}):
            self.assertEqual(tool, candidate.find_aapt2())
        with patch.dict(os.environ, {"ANDROID_SDK_ROOT": str(self.root / "missing-sdk"), "FINEXY_ANDROID_AAPT2": ""}):
            with self.assertRaises(candidate.CandidateError):
                candidate.find_aapt2()


if __name__ == "__main__":
    unittest.main()
