#!/usr/bin/env bash
set -euo pipefail
collect() {
  mkdir -p device-artifacts
  adb pull /sdcard/Android/data/dev.atlas.nym/files/screenshots device-artifacts/screenshots || true
  for report in *-run.txt; do if [ -f "$report" ]; then cp "$report" device-artifacts/; fi; done
  adb shell dumpsys meminfo dev.atlas.nym > device-artifacts/meminfo.txt || true
  adb logcat -d > device-artifacts/logcat.txt || true
}
trap collect EXIT
./gradlew :app:assembleDebugAndroidTest --stacktrace
adb install -r verified-apk/Nym_Mobile_0.1.0_arm64-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am start -W -n dev.atlas.nym/.MainActivity
installed_path="$(adb shell pm path dev.atlas.nym | tr -d '\r' | sed -n 's/^package://p' | head -n 1)"
installed_sha="$(adb shell sha256sum "$installed_path" | awk '{print $1}')"
expected_sha="$(awk '{print $1}' verified-apk/SHA256SUMS.txt)"
test "$installed_sha" = "$expected_sha"
mkdir -p device-artifacts
printf '%s  installed-base.apk\n' "$installed_sha" > device-artifacts/installed-apk-sha256.txt
runner="dev.atlas.nym.test/androidx.test.runner.AndroidJUnitRunner"
listener="dev.atlas.nym.AndroidRunReport"
adb shell am instrument -w -e listener "$listener" -e reportName normal "$runner" | tee normal-run.txt
grep -Eq 'OK \([0-9]+ tests?\)' normal-run.txt
adb shell am instrument -w -e listener "$listener" -e reportName recovery-prepare -e class dev.atlas.nym.ProcessRecoveryTest#prepareCheckpoint -e recoveryStage prepare "$runner" | tee recovery-prepare-run.txt
grep -q 'OK (1 test)' recovery-prepare-run.txt
adb shell am force-stop dev.atlas.nym
adb shell am start -W -n dev.atlas.nym/.MainActivity
adb shell am instrument -w -e listener "$listener" -e reportName recovery-verify -e class dev.atlas.nym.ProcessRecoveryTest#resumeAfterProcessRestart -e recoveryStage verify "$runner" | tee recovery-resume-run.txt
grep -q 'OK (1 test)' recovery-resume-run.txt
if [ "$1" = "36" ]; then
  adb shell am instrument -w -e listener "$listener" -e reportName production -e class dev.atlas.nym.ProductionEndpointSmokeTest -e liveSmoke true "$runner" | tee production-smoke-run.txt
  grep -q 'OK (1 test)' production-smoke-run.txt
fi
