"""Offline APK/source evidence checks; no Docker, ADB, Gradle, device or network calls."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
LEGACY = "artifacts/android-report-http-tests-20261026/attempt1"
TEST_SOURCE = "android/app/src/qaAndroidTest/java/com/finexy/mobile/data/LedgerReportApiE2ETest.kt"
TEST_CLASS = "Lcom/finexy/mobile/data/LedgerReportApiE2ETest;"
METADATA = "artifacts/android-report-http-tests-20261026/attempt1/code-verification.json"
SOURCE_REVISIONS = ".github/testdata/android-report-api-revisions.json"
EXPECTED_TEST_METHODS = {
    "realFinancialQueriesPaginationPreviewAndCsvKeepOriginalLedgerAndIntegerMeaning",
    "realFourRolesSnapshotMutationAndRevokedExportUseCurrentPermissions",
    "capturedContextRejectsAnotherLedgerTokenServerAndLocalModeWithoutReturningOldResults",
}
IGNORED = {"build", ".gradle", ".kotlin", ".idea"}
PROVIDERS = {
    "exceptions": {"kotlinx.coroutines.android.AndroidExceptionPreHandler",
                   "kotlinx.coroutines.test.internal.ExceptionCollectorAsService"},
    "main": {"kotlinx.coroutines.android.AndroidDispatcherFactory",
             "kotlinx.coroutines.test.internal.TestMainDispatcherFactory"},
}
SERVICES = {"exceptions": "META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler",
            "main": "META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory"}


class CandidateError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise CandidateError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def reviewed_test_metadata(root, source_sha):
    """Review source scope independently of a particular machine's APK archive."""
    registry = read_json(root / SOURCE_REVISIONS)
    require(isinstance(registry, dict) and registry.get("schemaVersion") == 1
            and registry.get("testSource") == TEST_SOURCE and registry.get("testClass") == TEST_CLASS,
            "Report source review registry has another test scope")
    methods = registry.get("testMethods")
    require(isinstance(methods, list) and len(methods) == 3
            and all(isinstance(method, str) for method in methods)
            and set(methods) == EXPECTED_TEST_METHODS,
            "Report source review registry must retain all three mandatory methods")
    revisions = registry.get("revisions")
    require(isinstance(revisions, dict), "Report source review registry is incomplete")
    revision = revisions.get(source_sha)
    require(isinstance(revision, dict) and isinstance(revision.get("review"), str)
            and bool(revision["review"].strip()),
            "Report API source has no reviewed three-method metadata revision")
    # DEX definitions are checked against the selected actual APK below. This
    # registry deliberately makes no claim that any APK or HTTP test ran.
    return {"sourceSHA256": source_sha, "testMethodsInDex": methods}


def find_aapt2():
    explicit = os.environ.get("FINEXY_ANDROID_AAPT2")
    if explicit:
        aapt = Path(explicit)
    else:
        sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
        if not sdk and os.name == "nt":
            sdk = "E:/Android/Sdk"
        require(bool(sdk), "Android SDK root is unavailable")
        aapt = Path(sdk) / "build-tools/35.0.0" / ("aapt2.exe" if os.name == "nt" else "aapt2")
    require(aapt.is_file(), "Android SDK aapt2 is unavailable")
    return aapt


def artifact_path(root, name):
    path = (root / name).resolve()
    require(path.is_relative_to((root / "artifacts").resolve()), "Candidate must stay within workspace artifacts")
    require(path.is_dir(), "Candidate directory is missing")
    return path


def manifest_digest(files):
    return hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()


def checked_manifest(candidate):
    before = read_json(candidate / "source-before.json")
    after = read_json(candidate / "source-after.json")
    require(before == after, "Candidate source changed during its gate")
    entries = after.get("files", {})
    require(isinstance(entries, dict) and entries and all(isinstance(name, str) and isinstance(value, str)
                            and name.startswith("android/") and ".." not in Path(name).parts
                            and re.fullmatch(r"[0-9a-f]{64}", value) for name, value in entries.items()),
            "Candidate source manifest is invalid")
    require(after.get("sha256") == manifest_digest(entries), "Candidate source manifest digest is invalid")
    return after


def verify_source_view(root, candidate, manifest, frozen):
    source_root = candidate / "source-snapshot" if frozen else root
    require(source_root.is_dir(), "Frozen mode requires a complete source-snapshot directory")
    android = source_root / "android"
    require(android.is_dir(), "Source view has no android directory")
    paths = []
    for path in android.rglob("*"):
        if not frozen and any(part in IGNORED for part in path.relative_to(android).parts):
            continue
        require(not path.is_symlink(), "Source view contains a symbolic link")
        if path.is_file():
            require(path.resolve().is_relative_to(source_root.resolve()), "Source view escaped its root")
            paths.append(path)
    if frozen:
        # A supplied snapshot is an exact archive, not an arbitrary larger source tree.
        all_files = [path for path in source_root.rglob("*") if path.is_file()]
        require(len(all_files) == len(paths), "Frozen snapshot contains files outside its android manifest")
    actual = {path.relative_to(source_root).as_posix(): digest(path) for path in paths}
    expected = manifest["files"]
    require(set(actual) == set(expected), "Android source file set differs from the compiled candidate")
    require(actual == expected, "Android source content differs from the compiled candidate")
    return {"sourceMode": "frozen-source-snapshot" if frozen else "strict-current",
            "sourceRoot": source_root.relative_to(root).as_posix() if frozen else ".",
            "sourceViewVerified": True, "currentSourceVerified": not frozen,
            "sourceFileCount": len(actual), "sourceSHA256": manifest["sha256"]}


def dex_definitions(data):
    """Read actual class/method definitions, rather than accepting incidental DEX strings."""
    require(data.startswith(b"dex\n") and len(data) >= 112, "Invalid DEX header")
    u32 = lambda offset: struct.unpack_from("<I", data, offset)[0]
    require(u32(32) == len(data), "DEX size differs from its header")

    def uleb(offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
        raise CandidateError("Invalid DEX ULEB128")

    string_count, string_offset = u32(56), u32(60)
    strings = []
    for index in range(string_count):
        offset = u32(string_offset + index * 4)
        _, offset = uleb(offset)
        end = data.index(0, offset)
        strings.append(data[offset:end].decode("utf-8", errors="replace"))
    types = [strings[u32(u32(68) + index * 4)] for index in range(u32(64))]
    methods = []
    for index in range(u32(88)):
        owner, _, name = struct.unpack_from("<HHI", data, u32(92) + index * 8)
        methods.append((types[owner], strings[name]))
    classes, declared = set(), {}
    for index in range(u32(96)):
        class_offset = u32(100) + index * 32
        owner = types[u32(class_offset)]
        classes.add(owner)
        offset = u32(class_offset + 24)
        if not offset:
            continue
        counts = []
        for _ in range(4):
            count, offset = uleb(offset)
            counts.append(count)
        for _ in range(counts[0] + counts[1]):
            _, offset = uleb(offset)
            _, offset = uleb(offset)
        names = set()
        for count in counts[2:]:
            method_index = 0
            for _ in range(count):
                difference, offset = uleb(offset)
                _, offset = uleb(offset)
                _, offset = uleb(offset)
                method_index += difference
                method_owner, method_name = methods[method_index]
                require(method_owner == owner, "DEX method owner differs from its declaring class")
                names.add(method_name)
        declared[owner] = names
    return classes, declared


def inspect_apk(path):
    definitions, declared = set(), {}
    with zipfile.ZipFile(path) as archive:
        require(archive.testzip() is None, "APK ZIP integrity check failed")
        require("AndroidManifest.xml" in archive.namelist(), "APK has no manifest")
        services = {key: set(line.strip() for line in archive.read(resource).decode().splitlines()
                             if line.strip() and not line.lstrip().startswith("#"))
                    if resource in archive.namelist() else set() for key, resource in SERVICES.items()}
        for name in archive.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                classes, methods = dex_definitions(archive.read(name))
                require(not (definitions & classes), "APK has duplicate DEX class definitions")
                definitions.update(classes)
                declared.update(methods)
    require(definitions, "APK has no DEX definitions")
    return {"definitions": definitions, "methods": declared, "services": services}


def verify_pair(candidate, gate, packaging, app, test):
    for filename, path, expected_package in [("app-qa.apk", app, "com.finexy.mobile.qa"),
                                            ("app-qa-androidTest.apk", test, "com.finexy.mobile.qa.test")]:
        require(path.parent == candidate, "Complete candidate APKs must come from the same gate directory")
        expected = gate.get("apks", {}).get(filename, {}).get("sha256")
        require(expected and digest(path) == expected, "Candidate APK differs from its gate hash")
        recorded = packaging.get("packages", {}).get(filename, {})
        require(recorded.get("sha256") == expected and recorded.get("package") == expected_package,
                "Candidate packaging proof and APK pair disagree")
    require(packaging.get("state") == "passed" and packaging.get("sourceSHA256") == gate.get("sourceSHA256"),
            "QA packaging proof belongs to another source candidate")
    require(packaging.get("standardExceptionCollectorPreserved") is True
            and packaging.get("qaTargetFrameworkNotDuplicatedInTestApk") is True
            and packaging.get("qaOnlyTestFramework") is True, "QA framework packaging proof is incomplete")


def load_candidate(root, requested=None):
    candidate = artifact_path(root, requested or LEGACY)
    manifest = checked_manifest(candidate)
    gate = read_json(candidate / "summary.json")
    require(gate.get("passed") is True and gate.get("sourceUnchanged") is True,
            "Android build candidate is not a passed immutable gate")
    require(gate.get("sourceSHA256") == manifest["sha256"], "Android gate and source manifest disagree")
    legacy = candidate == (root / LEGACY).resolve()
    metadata_path = METADATA if legacy else SOURCE_REVISIONS
    metadata = read_json(root / metadata_path) if legacy else reviewed_test_metadata(root, manifest["files"].get(TEST_SOURCE))
    if legacy:
        require(metadata.get("testClassInDex") is True and metadata.get("methodCount") == 3,
                "Report API test metadata is incomplete")
    expected_methods = metadata.get("testMethodsInDex", [])
    require(len(expected_methods) == 3 and set(expected_methods) == EXPECTED_TEST_METHODS,
            "Report API metadata must identify all three mandatory methods")
    require(manifest["files"].get(TEST_SOURCE) == metadata.get("sourceSHA256"),
            "Report API test source differs from the reviewed three-method metadata")
    if legacy:
        require(gate.get("exitCode") == 0 and gate.get("testsExecuted") is False,
                "Legacy report instrumentation build did not pass")
        app = root / "artifacts/android-report-qa-build-20261026/attempt1/app-qa.apk"
        test = candidate / gate["apk"]["file"]
        require(digest(app) == metadata.get("matchingMainQaApkSHA256"), "Legacy QA app hash changed")
        require(digest(test) == gate["apk"]["sha256"] == metadata.get("apkSHA256"), "Legacy test APK hash changed")
        schema = "legacy-report-207"
    else:
        require(gate.get("phases") == {"debug": 0, "qa": 0}, "Complete Android candidate requires debug and QA gates")
        require(gate.get("jvm", {}).get("tests", 0) > 0 and all(gate.get("jvm", {}).get(key) == 0
                for key in ("failures", "errors", "skipped")), "Complete Android JVM gate is incomplete")
        require(all(not gate.get("lint", {}).get(variant, {}).get("Error", 0)
                    for variant in ("debug", "qa")), "Android lint gate has errors")
        require(gate.get("sourceFiles") == len(manifest["files"]), "Android gate source count disagrees")
        app, test = candidate / "app-qa.apk", candidate / "app-qa-androidTest.apk"
        packaging = read_json(candidate / "qa-packaging.json")
        verify_pair(candidate, gate, packaging, app, test)
        schema = "complete-android-gate-v1"
    app_code, test_code = inspect_apk(app), inspect_apk(test)
    require(TEST_CLASS in test_code["definitions"], "Report API test class is not defined in the selected APK")
    require(set(expected_methods) <= test_code["methods"].get(TEST_CLASS, set()),
            "Report API test methods are not declared by the selected class")
    if not legacy:
        require(app_code["services"] == PROVIDERS, "QA app lacks the standard test framework service bindings")
        require(not any(test_code["services"].values()), "Test APK duplicates target-provided service bindings")
        for provider in set().union(*PROVIDERS.values()):
            descriptor = "L" + provider.replace(".", "/") + ";"
            require(descriptor in app_code["definitions"] and descriptor not in test_code["definitions"],
                    "QA standard framework class ownership is invalid")
    aapt = find_aapt2()
    def sdk(*args):
        result = subprocess.run([str(aapt), *args], capture_output=True, text=True, encoding="utf-8", check=False)
        require(result.returncode == 0, "APK package inspection failed")
        return result.stdout
    for path, package in [(app, "com.finexy.mobile.qa"), (test, "com.finexy.mobile.qa.test")]:
        require(re.search(r"^package: name='" + re.escape(package) + r"'", sdk("dump", "badging", str(path)), re.M),
                "Selected APK package is not the isolated QA package")
    require(re.search(r'android:targetPackage[^\n]*"com\.finexy\.mobile\.qa"',
                      sdk("dump", "xmltree", str(test), "--file", "AndroidManifest.xml")),
            "Selected test APK targets another application")
    return {"candidate": candidate, "manifest": manifest, "summary": {
        "schemaVersion": 1, "candidateSchema": schema, "legacyDefault": requested is None,
        "artifactDirectory": candidate.relative_to(root).as_posix(),
        "sourceSHA256": manifest["sha256"], "sourceFileCount": len(manifest["files"]),
        "summarySHA256": digest(candidate / "summary.json"),
        "qaAppPath": app.relative_to(root).as_posix(), "qaAppSHA256": digest(app),
        "qaTestPath": test.relative_to(root).as_posix(), "qaTestSHA256": digest(test),
        "targetPackage": "com.finexy.mobile.qa", "testPackage": "com.finexy.mobile.qa.test",
        "apiTestMetadataPath": metadata_path, "apiTestSourceSHA256": metadata["sourceSHA256"],
        "apiTestClass": TEST_CLASS, "apiTestMethods": expected_methods, "apiTestDeclaredMethodsVerified": True,
        "standardQaPackagingVerified": not legacy,
        "androidHttpTestsExecuted": False, "deviceUsed": False}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", help="Passed gate directory within workspace artifacts; default is explicitly legacy 207")
    parser.add_argument("--frozen-source", action="store_true", help="Require the candidate's exact full source-snapshot/android tree")
    args = parser.parse_args()
    try:
        checked = load_candidate(ROOT, args.candidate)
        view = verify_source_view(ROOT, checked["candidate"], checked["manifest"], args.frozen_source)
        print(json.dumps({"state": "passed", **checked["summary"], **view}))
        return 0
    except (CandidateError, OSError, ValueError, zipfile.BadZipFile, struct.error, IndexError, KeyError, TypeError) as error:
        print(json.dumps({"state": "failed", "errorType": type(error).__name__,
                          "reason": str(error) if isinstance(error, CandidateError) else "Candidate artifact is invalid or missing",
                          "deviceUsed": False, "androidHttpTestsExecuted": False}))
        return 1


if __name__ == "__main__":
    sys.exit(main())
