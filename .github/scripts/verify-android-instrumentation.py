#!/usr/bin/env python3
"""Fail closed on incomplete AndroidJUnitRunner -r output; never operates a device.

Protocol: androidx.test:runner:1.6.2 InstrumentationResultPrinter.java, published in
https://dl.google.com/dl/android/maven2/androidx/test/runner/1.6.2/runner-1.6.2-sources.jar
START=1, OK=0, ERROR=-1, FAILURE=-2, IGNORED=-3, ASSUMPTION_FAILURE=-4.
The final INSTRUMENTATION_CODE (-1) is distinct from a per-test error (-1).
"""

import argparse
import json
from pathlib import Path
import re
import sys


EXPECTED_COUNTS = {
    "com.finexy.mobile.data.LedgerWritePersistenceTest": 3,
    "com.finexy.mobile.EntryReliabilityUiTest": 5,
    "com.finexy.mobile.data.DockerLedgerWriteE2ETest": 1,
    "com.finexy.mobile.data.TransactionConsistencyTest": 28,
    "com.finexy.mobile.data.SyncWorkerTest": 2,
    "com.finexy.mobile.data.LedgerScopeTest": 2,
    "com.finexy.mobile.data.PrivacyDataTest": 12,
}
EXPECTED_TOTAL = 53
BUDGET_EXPECTED_COUNTS = {
    "com.finexy.mobile.MonthlyBudgetUiTest": 6,
    "com.finexy.mobile.data.DockerMonthlyBudgetE2ETest": 3,
}
BUDGET_EXPECTED_TOTAL = 9
AI_REVIEW_NAVIGATION_EXPECTED_COUNTS = {
    "com.finexy.mobile.AIReviewLedgerScopeE2ETest": 2,
}
AI_REVIEW_NAVIGATION_EXPECTED_TOTAL = 2
BUDGET_NAVIGATION_EXPECTED_COUNTS = {
    "com.finexy.mobile.MonthlyBudgetNavigationE2ETest": 2,
}
BUDGET_NAVIGATION_EXPECTED_TOTAL = 2
REPOSITORY = Path(__file__).resolve().parents[2]
SCOPES = {
    "ai-pending-capture": ({"com.finexy.mobile.data.AIPendingCaptureTest": 8}, 8, "android/app/src/androidTest/java"),
    "ai-pending-capture-process": ({"com.finexy.mobile.AIPendingCaptureProcessE2ETest": 2}, 2, "android/app/src/qaAndroidTest/java"),
    "ai-source-native-ui": ({"com.finexy.mobile.AIReviewNativeImageE2ETest": 9}, 9, "android/app/src/qaAndroidTest/java"),
    "ai-source-import-lifecycle": ({"com.finexy.mobile.AIImageImportLifecycleTest": 3}, 3, "android/app/src/qaAndroidTest/java"),
    "ai-source-ui-e2e": ({"com.finexy.mobile.AIReviewProductionE2ETest": 8}, 8, "android/app/src/qaAndroidTest/java"),
    "ai-source-data": ({"com.finexy.mobile.data.AIReviewSourceSessionTest": 15}, 15, "android/app/src/androidTest/java"),
    "ai-source-expected-resume": ({"com.finexy.mobile.data.AIReviewSourceExpectedResumeTest": 4}, 4, "android/app/src/androidTest/java"),
    "ai-source-image-read": ({"com.finexy.mobile.data.AIReviewSourceImageReadTest": 3}, 3, "android/app/src/androidTest/java"),
    "ai-legacy-quarantine": ({"com.finexy.mobile.data.LegacyAIQuarantineSafetyTest": 6}, 6, "android/app/src/androidTest/java"),
    "ledger-write-e2e": ({"com.finexy.mobile.data.DockerLedgerWriteE2ETest": 1}, 1, "android/app/src/androidTest/java"),
    "original-transaction-recovery-data": ({"com.finexy.mobile.data.OriginalTransactionRecoveryPersistenceTest": 18}, 18, "android/app/src/androidTest/java"),
    "original-transfer-entry-ui": ({"com.finexy.mobile.OriginalTransferEntryUiTest": 4}, 4, "android/app/src/androidTest/java"),
    "original-transaction-recovery-e2e": ({"com.finexy.mobile.data.OriginalTransactionRecoveryE2ETest": 3}, 3, "android/app/src/qaAndroidTest/java"),
    "ledger-report-api-e2e": ({"com.finexy.mobile.data.LedgerReportApiE2ETest": 3}, 3, "android/app/src/qaAndroidTest/java"),
    "ledger-report-navigation": ({"com.finexy.mobile.LedgerReportNavigationE2ETest": 3}, 3, "android/app/src/qaAndroidTest/java"),
    "ledger-report-saf": ({"com.finexy.mobile.LedgerReportSafE2ETest": 6}, 6, "android/app/src/qaAndroidTest/java"),
    "ai-images": ({"com.finexy.mobile.data.AIReviewImagePersistenceTest": 10}, 10, "android/app/src/androidTest/java"),
    "ai-storage-delivery": ({"com.finexy.mobile.data.AIReviewDeliveryE2ETest": 2}, 2, "android/app/src/qaAndroidTest/java"),
    "ai-storage-process": ({"com.finexy.mobile.data.AIReviewProcessRecoveryE2ETest": 2}, 2, "android/app/src/qaAndroidTest/java"),
    "ai-storage": ({"com.finexy.mobile.data.AIReviewPersistenceTest": 10}, 10, "android/app/src/androidTest/java"),
    "ai-ledger-api-e2e": ({"com.finexy.mobile.data.AIReviewLedgerApiE2ETest": 3}, 3, "android/app/src/qaAndroidTest/java"),
    "ai-ledger-api-regression": ({"com.finexy.mobile.data.AIReviewContractTest": 3,
        "com.finexy.mobile.data.TemplateCreationPersistenceTest": 7,
        "com.finexy.mobile.data.SyncWorkerTest": 2,
        "com.finexy.mobile.data.LedgerScopeTest": 2}, 14, "android/app/src/androidTest/java"),
    "ai-ledger-api": ({"com.finexy.mobile.data.AIReviewLedgerApiContractTest": 12}, 12, "android/app/src/androidTest/java"),
    "template-editor-navigation": ({"com.finexy.mobile.NormalTemplateNavigationE2ETest": 13}, 13, "android/app/src/qaAndroidTest/java"),
    "template-editor-ui": ({"com.finexy.mobile.NormalTemplateUiTest": 4, "com.finexy.mobile.ScheduleUiTest": 6,
        "com.finexy.mobile.TemplateManagementUiTest": 3}, 13, "android/app/src/androidTest/java"),
    "template-creation-compatibility": ({"com.finexy.mobile.data.TemplateCreationCompatibilityE2ETest": 2}, 2, "android/app/src/qaAndroidTest/java"),
    "template-storage-data": ({
        "com.finexy.mobile.data.TemplateCreationPersistenceTest": 7,
        "com.finexy.mobile.data.PrivacyDataTest": 12,
        "com.finexy.mobile.data.PeriodicLedgerPersistenceTest": 4,
        "com.finexy.mobile.data.LedgerWritePersistenceTest": 3,
        "com.finexy.mobile.data.TransactionConsistencyTest": 28,
        "com.finexy.mobile.data.ExchangeRateContractTest": 3,
        "com.finexy.mobile.data.FamilyGoalContractTest": 14,
        "com.finexy.mobile.data.ProductAssetContractTest": 3,
        "com.finexy.mobile.data.ScheduleOccurrenceTest": 8,
    }, 82, "android/app/src/androidTest/java"),
    "template-creation-regression": ({
        "com.finexy.mobile.data.TemplateCreationPersistenceTest": 7,
        "com.finexy.mobile.data.PrivacyDataTest": 12,
        "com.finexy.mobile.data.PeriodicLedgerPersistenceTest": 4,
        "com.finexy.mobile.data.LedgerWritePersistenceTest": 3,
        "com.finexy.mobile.data.TransactionConsistencyTest": 28,
        "com.finexy.mobile.data.ExchangeRateContractTest": 3,
        "com.finexy.mobile.data.FamilyGoalContractTest": 14,
        "com.finexy.mobile.data.ProductAssetContractTest": 3,
        "com.finexy.mobile.data.ScheduleOccurrenceTest": 8,
        "com.finexy.mobile.data.TemplatePayloadTest": 3,
        "com.finexy.mobile.data.NormalTemplatePersistenceTest": 2,
        "com.finexy.mobile.data.SyncWorkerTest": 2,
        "com.finexy.mobile.data.LedgerScopeTest": 2,
    }, 91, "android/app/src/androidTest/java"),
    "template-storage-e2e": ({"com.finexy.mobile.data.TemplateCreationStorageE2ETest": 1}, 1, "android/app/src/qaAndroidTest/java"),
    "iteration": (EXPECTED_COUNTS, EXPECTED_TOTAL, "android/app/src/androidTest/java"),
    "budget": (BUDGET_EXPECTED_COUNTS, BUDGET_EXPECTED_TOTAL, "android/app/src/qaAndroidTest/java"),
    "ai-review-navigation": (AI_REVIEW_NAVIGATION_EXPECTED_COUNTS, AI_REVIEW_NAVIGATION_EXPECTED_TOTAL, "android/app/src/qaAndroidTest/java"),
    "budget-navigation": (BUDGET_NAVIGATION_EXPECTED_COUNTS, BUDGET_NAVIGATION_EXPECTED_TOTAL, "android/app/src/qaAndroidTest/java"),
}


SCOPES["ai-storage-regression"] = ({**SCOPES["template-creation-regression"][0],
    "com.finexy.mobile.data.AIReviewContractTest": 3,
    "com.finexy.mobile.data.AIReviewLedgerApiContractTest": 12}, 106, "android/app/src/androidTest/java")

SCOPES["ai-images-regression"] = ({**SCOPES["ai-storage-regression"][0],
    "com.finexy.mobile.data.AIReviewPersistenceTest": 10}, 116, "android/app/src/androidTest/java")


def expected_tests(scope="iteration"):
    """Bind the fixed delivery scope to actual methods, so counts cannot hide omissions."""
    expected = set()
    counts, total, source_root = SCOPES[scope]
    for class_name, count in counts.items():
        source = REPOSITORY / source_root / (class_name.replace(".", "/") + ".kt")
        methods = re.findall(r"(?m)^\s*@Test\s+fun\s+(\w+)\s*\(", source.read_text(encoding="utf-8"))
        if len(methods) != count or len(set(methods)) != count:
            raise ValueError(f"Delivery scope changed: {class_name} requires {count} unique @Test methods")
        expected.update((class_name, method) for method in methods)
    if len(expected) != total:
        raise ValueError(f"Delivery scope {scope} must contain exactly {total} tests")
    return expected


def verify_output(output, expected):
    total = len(expected)
    status = {}
    completed = set()
    active = None
    started = 0
    final_codes = []
    result_started = False
    in_result_stream = False
    result_stream = []
    for line in output.splitlines():
        if line.startswith(("INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED")) or line.strip() == "FAILURES!!!":
            raise ValueError("Runner reported a failure or aborted instrumentation")
        if line.startswith("INSTRUMENTATION_STATUS: "):
            if result_started or final_codes:
                raise ValueError("Test status appeared after the final result")
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if not separator or key in status:
                raise ValueError("Malformed or repeated field in a status bundle")
            status[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line[len("INSTRUMENTATION_STATUS_CODE: "):])
            if code not in (0, 1):
                names = {-1: "error", -2: "failure", -3: "ignored", -4: "assumption failure"}
                raise ValueError(f"Mandatory test did not pass: {status.get('class')}#{status.get('test')} ({names.get(code, code)})")
            key = (status.get("class"), status.get("test"))
            if key not in expected or status.get("id") != "AndroidJUnitRunner":
                raise ValueError(f"Unexpected test or runner: {key}")
            if status.get("numtests") != str(total):
                raise ValueError(f"Runner did not discover all {total} mandatory tests")
            current = int(status.get("current", "0"))
            if code == 1:
                if active is not None or key in completed or current != started + 1:
                    raise ValueError("Duplicate, overlapping or out-of-order test start")
                started += 1
                active = (key, current)
            else:
                if active != (key, current):
                    raise ValueError("Successful completion does not match its test start")
                completed.add(key)
                active = None
            status = {}
        elif line.startswith("INSTRUMENTATION_RESULT: "):
            if active is not None or status or final_codes:
                raise ValueError("Final result interrupted a test or followed termination")
            result_started = True
            key, separator, value = line[len("INSTRUMENTATION_RESULT: "):].partition("=")
            if not separator or key == "shortMsg":
                raise ValueError("Malformed result or runner shortMsg error")
            in_result_stream = key == "stream"
            if in_result_stream:
                result_stream.append(value)
        elif line.startswith("INSTRUMENTATION_CODE: "):
            if active is not None or status or not result_started or final_codes:
                raise ValueError("Missing, interrupted or repeated final result")
            final_codes.append(int(line[len("INSTRUMENTATION_CODE: "):]))
            in_result_stream = False
        elif line.startswith("INSTRUMENTATION_"):
            raise ValueError("Malformed instrumentation protocol record")
        elif in_result_stream:
            result_stream.append(line)
    if active is not None or status or completed != expected:
        missing = sorted(f"{name}#{method}" for name, method in expected - completed)
        raise ValueError(f"Incomplete execution: {len(completed)}/{total} passed; missing {', '.join(missing[:3])}")
    if final_codes != [-1] or re.findall(r"(?m)^OK \((\d+) tests?\)\s*$", "\n".join(result_stream)) != [str(total)]:
        raise ValueError(f"Missing successful final runner summary/termination for all {total} tests")
    return {"tests": len(completed), "classes": {name: sum(key[0] == name for key in completed) for name in sorted({key[0] for key in expected})}}


def synthetic_log(tests, terminal_code=0, final=True, total=EXPECTED_TOTAL):
    """Use the actual raw bundle framing, including multiline stream and CRLF."""
    lines = []
    for current, (class_name, method) in enumerate(tests, 1):
        for code in (1, terminal_code if current == 1 else 0):
            lines.extend([f"INSTRUMENTATION_STATUS: class={class_name}", f"INSTRUMENTATION_STATUS: current={current}",
                          "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner", f"INSTRUMENTATION_STATUS: numtests={total}",
                          "INSTRUMENTATION_STATUS: stream=" if code == 1 else "INSTRUMENTATION_STATUS: stream=."])
            if code == 1:
                lines.append(f"{class_name}:")
            lines.extend([f"INSTRUMENTATION_STATUS: test={method}", f"INSTRUMENTATION_STATUS_CODE: {code}"])
    if final:
        lines.extend(["INSTRUMENTATION_RESULT: stream=", "", "Time: 12.345", "", f"OK ({total} tests)", "", "INSTRUMENTATION_CODE: -1"])
    return "\r\n".join(lines) + "\r\n"


def self_test(expected, evidence_dir):
    tests = sorted(expected)
    total = len(expected)
    omitted_class = next((name for name in sorted({key[0] for key in expected}) if name.endswith("E2ETest")), tests[0][0])
    successful_log = synthetic_log(tests, total=total)
    cases = {
        "success": (successful_log, True),
        "missing-class": (synthetic_log([test for test in tests if test[0] != omitted_class], total=total), False),
        "missing-method": (synthetic_log(tests[:-1], total=total), False),
        "ignored": (synthetic_log(tests, -3, total=total), False),
        "assumption-skipped": (synthetic_log(tests, -4, total=total), False),
        "failure-with-misleading-summary": (synthetic_log(tests, -2, total=total), False),
        "error": (synthetic_log(tests, -1, total=total), False),
        "truncated": (synthetic_log(tests, final=False, total=total), False),
        "duplicate-method": (synthetic_log(tests + [tests[0]], total=total), False),
        "wrong-discovery-count": (successful_log.replace(f"numtests={total}", f"numtests={total - 1}"), False),
        "wrong-final-count": (successful_log.replace(f"OK ({total} tests)", f"OK ({total - 1} tests)"), False),
        "wrong-scope-class": (synthetic_log([("com.finexy.mobile.UnexpectedScopeTest", tests[0][1])] + tests[1:], total=total), False),
        "empty": ("", False),
        "malformed-status-code": (successful_log.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: nope", 1), False),
        "wrong-final-code": (successful_log.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"), False),
        "status-after-final": (successful_log + "INSTRUMENTATION_STATUS: class=late\r\n", False),
    }
    evidence_dir.mkdir(parents=True, exist_ok=True)
    results = []
    for name, (output, should_pass) in cases.items():
        (evidence_dir / f"{name}.txt").write_bytes(output.encode("utf-8"))
        try:
            summary = verify_output(output, expected)
            passed, message = True, summary
        except ValueError as error:
            passed, message = False, str(error)
        if passed != should_pass:
            raise ValueError(f"Synthetic verification failed: {name}")
        results.append({"case": name, "expectedAccepted": should_pass, "accepted": passed, "result": message})
    report = {"syntheticOnly": True, "mandatoryTests": total, "cases": results}
    (evidence_dir / "verification.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def main():
    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument("log", nargs="?", type=Path)
    arguments.add_argument("--self-test", action="store_true")
    arguments.add_argument("--scope", choices=SCOPES, default="iteration")
    arguments.add_argument("--print-classes", action="store_true", help="validate source scope and print its exact runner class selection")
    arguments.add_argument("--evidence-dir", type=Path, default=REPOSITORY / "artifacts/first-iteration-20261003/android-instrumentation-protocol")
    args = arguments.parse_args()
    try:
        expected = expected_tests(args.scope)
        if args.print_classes:
            if args.log or args.self_test:
                arguments.error("--print-classes cannot be combined with a log or --self-test")
            print(",".join(SCOPES[args.scope][0]))
            return 0
        if args.self_test:
            report = self_test(expected, args.evidence_dir)
        elif args.log:
            report = verify_output(args.log.read_text(encoding="utf-8-sig"), expected)
        else:
            arguments.error("provide a runner log or --self-test")
        report["scope"] = args.scope
        print(json.dumps(report, indent=2))
        return 0
    except (ValueError, OSError) as error:
        print(f"Android instrumentation gate rejected: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
