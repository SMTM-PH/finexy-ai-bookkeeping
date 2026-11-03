#!/usr/bin/env bash
set -euo pipefail

# This script is deliberately unavailable on a developer's primary ledger AVD.
test "${CI:-}" = "true"
test "${RUNNER_ENVIRONMENT:-}" = "github-hosted"
docker inspect finexy-android-ci-e2e | jq -e '.[0].Mounts == [] and (.[0].Config.Env | index("EBK_AUTH_ENABLE_REGISTER=true")) != null and (.[0].NetworkSettings.Ports["8080/tcp"] | any(.HostPort == "18086"))'
mkdir -p artifacts/first-iteration-android
adb -s emulator-5554 install -r android/app/build/outputs/apk/qa/app-qa.apk
adb -s emulator-5554 install -r android/app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  -e finexy.e2e.url http://10.0.2.2:18086 \
  -e class com.finexy.mobile.data.LedgerWritePersistenceTest,com.finexy.mobile.EntryReliabilityUiTest,com.finexy.mobile.data.DockerLedgerWriteE2ETest,com.finexy.mobile.data.TransactionConsistencyTest,com.finexy.mobile.data.SyncWorkerTest,com.finexy.mobile.data.LedgerScopeTest,com.finexy.mobile.data.PrivacyDataTest \
  com.finexy.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner \
  | tee artifacts/first-iteration-android/instrumentation.txt
python3 .github/scripts/verify-android-instrumentation.py artifacts/first-iteration-android/instrumentation.txt \
  | tee artifacts/first-iteration-android/instrumentation-summary.json
FINEXY_ANDROID_E2E_CONTAINER=finexy-android-ci-e2e \
  FINEXY_ANDROID_RECOVERY_EVIDENCE=artifacts/first-iteration-android/process-recovery \
  node .github/scripts/verify-android-process-recovery.mjs
