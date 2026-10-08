#!/usr/bin/env bash
set -euo pipefail
collect() {
  mkdir -p device-artifacts
  adb pull /sdcard/Android/data/dev.atlas.nym/files/screenshots device-artifacts/screenshots || true
  if [ -f production-smoke-run.txt ]; then cp production-smoke-run.txt device-artifacts/; fi
  adb shell dumpsys meminfo dev.atlas.nym > device-artifacts/meminfo.txt || true
  adb logcat -d > device-artifacts/logcat.txt || true
}
trap collect EXIT
./gradlew :app:connectedDebugAndroidTest --stacktrace
if [ "${1:-}" = "36" ]; then
  adb shell am instrument -w -e class dev.atlas.nym.ProductionEndpointSmokeTest -e liveSmoke true dev.atlas.nym.test/androidx.test.runner.AndroidJUnitRunner | tee production-smoke-run.txt
  grep -q 'OK (1 test)' production-smoke-run.txt
fi
