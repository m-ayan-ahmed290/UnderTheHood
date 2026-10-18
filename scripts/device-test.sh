#!/usr/bin/env bash
set -euo pipefail
trap 'adb logcat -d -v brief AndroidRuntime:E HoodTest:D "*:S" > device-logcat.txt || true' EXIT
adb install -r dist/UnderTheHood-Android-debug.apk
adb install -r dist/UnderTheHood-Android-tests.apk
adb shell am instrument -w app.underthehood.test/androidx.test.runner.AndroidJUnitRunner | tee device-test-results.txt
grep -Eq 'OK \([0-9]+ tests?\)' device-test-results.txt
