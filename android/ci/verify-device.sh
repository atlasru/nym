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
./gradlew :app:connectedDebugAndroidTest --stacktrace
adb shell am instrument -w -e class dev.atlas.nym.ProcessRecoveryTest#prepareCheckpoint -e recoveryStage prepare dev.atlas.nym.test/androidx.test.runner.AndroidJUnitRunner | tee recovery-prepare-run.txt
grep -q 'OK (1 test)' recovery-prepare-run.txt
adb shell am force-stop dev.atlas.nym
adb shell am start -W -n dev.atlas.nym/.MainActivity
adb shell am instrument -w -e class dev.atlas.nym.ProcessRecoveryTest#resumeAfterProcessRestart -e recoveryStage verify dev.atlas.nym.test/androidx.test.runner.AndroidJUnitRunner | tee recovery-resume-run.txt
grep -q 'OK (1 test)' recovery-resume-run.txt
if [ "${1:-}" = "36" ]; then
  adb shell am instrument -w -e class dev.atlas.nym.ProductionEndpointSmokeTest -e liveSmoke true dev.atlas.nym.test/androidx.test.runner.AndroidJUnitRunner | tee production-smoke-run.txt
  grep -q 'OK (1 test)' production-smoke-run.txt
fi
