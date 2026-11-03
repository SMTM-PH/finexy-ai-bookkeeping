#!/usr/bin/env bash
set -euo pipefail

# The budget scope targets only the isolated QA UID on a fresh hosted CI AVD.
# It is never a shortcut for running instrumentation against a user's ledger.
test "${CI:-}" = "true"
test "${RUNNER_ENVIRONMENT:-}" = "github-hosted"
test -n "${GITHUB_SHA:-}"
docker inspect finexy-android-budget-e2e | jq -e --arg image "finexy-android-ci:${GITHUB_SHA}" \
  '.[0].State.Running == true and .[0].Mounts == [] and .[0].Config.Image == $image and
  (.[0].Config.Env | index("EBK_AUTH_ENABLE_REGISTER=true")) != null and
  (.[0].Config.Env | index("EBK_UUID_SERVER_ID=232")) != null and
  (.[0].NetworkSettings.Ports["8080/tcp"] | any(.HostPort == "18111"))'
curl --fail --silent http://127.0.0.1:18111/healthz.json | jq -e '.success == true'
mkdir -p artifacts/monthly-budget-android
trap 'mkdir -p artifacts/monthly-budget-android/device-evidence; adb -s emulator-5554 pull /storage/emulated/0/Android/data/com.finexy.mobile.qa/files/ledger-budget-20261004/. artifacts/monthly-budget-android/device-evidence/ >artifacts/monthly-budget-android/evidence-pull.txt 2>&1 || true' EXIT
classes="$(python3 .github/scripts/verify-android-instrumentation.py --scope budget --print-classes)"
adb -s emulator-5554 install -r android/app/build/outputs/apk/qa/app-qa.apk
adb -s emulator-5554 install -r android/app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk
adb -s emulator-5554 shell pm list instrumentation \
  | tr -d '\r' | grep -Fx 'instrumentation:com.finexy.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner (target=com.finexy.mobile.qa)'
adb -s emulator-5554 shell am instrument -w -r \
  -e finexy.budget.e2e.url http://10.0.2.2:18111 \
  -e class "$classes" \
  com.finexy.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner \
  | tee artifacts/monthly-budget-android/instrumentation.txt
python3 .github/scripts/verify-android-instrumentation.py --scope budget artifacts/monthly-budget-android/instrumentation.txt \
  | tee artifacts/monthly-budget-android/instrumentation-summary.json

# Preserve a separate count for the account-scoped AI return/switch regression.
ai_classes="$(python3 .github/scripts/verify-android-instrumentation.py --scope ai-review-navigation --print-classes)"
adb -s emulator-5554 shell am instrument -w -r \
  -e finexy.budget.e2e.url http://10.0.2.2:18111 \
  -e class "$ai_classes" \
  com.finexy.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner \
  | tee artifacts/monthly-budget-android/ai-review-navigation.txt
python3 .github/scripts/verify-android-instrumentation.py --scope ai-review-navigation artifacts/monthly-budget-android/ai-review-navigation.txt \
  | tee artifacts/monthly-budget-android/ai-review-navigation-summary.json

budget_navigation_classes="$(python3 .github/scripts/verify-android-instrumentation.py --scope budget-navigation --print-classes)"
adb -s emulator-5554 shell am instrument -w -r \
  -e finexy.budget.e2e.url http://10.0.2.2:18111 \
  -e class "$budget_navigation_classes" \
  com.finexy.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner \
  | tee artifacts/monthly-budget-android/budget-navigation.txt
python3 .github/scripts/verify-android-instrumentation.py --scope budget-navigation artifacts/monthly-budget-android/budget-navigation.txt \
  | tee artifacts/monthly-budget-android/budget-navigation-summary.json
mkdir -p artifacts/monthly-budget-android/device-evidence
adb -s emulator-5554 pull /storage/emulated/0/Android/data/com.finexy.mobile.qa/files/ledger-budget-20261004/. \
  artifacts/monthly-budget-android/device-evidence/
trap - EXIT
