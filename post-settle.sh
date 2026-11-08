#!/bin/bash
# post-settle.sh LOGROOT: after test-ios26-settle.sh ends (its run.log's exit line): the new dev APK, then
# test-ios26-resume.sh (the settled save back: idle at once?) and test-ios26-who.sh (what the ~13 busy minutes
# after a boot are), one after the other.
ROOT=${1:?log root}
HERE=$(cd "$(dirname "$0")" && pwd)
export MSYS_NO_PATHCONV=1
until grep -q -E "^exit [0-9]+$" "$ROOT/ios26-settle/run.log" 2>/dev/null; do sleep 20; done
echo "$(date +%T) settle test ended"
adb install -r "$(cygpath -w "$HERE/app/build/outputs/apk/debug/app-debug.apk")" 2>&1 | tail -n 1
sleep 10
adb shell "am start --activity-clear-top -n com.asoft.ios.dev/com.asoft.ios.MainActivity" >/dev/null
sleep 5
mkdir -p "$ROOT/ios26-resume" "$ROOT/ios26-who"
echo "$(date +%T) resume test"
bash "$HERE/test-ios26-resume.sh" "$ROOT/ios26-resume" 6 > "$ROOT/ios26-resume/run.log" 2>&1
echo "$(date +%T) resume test exit $?"
sleep 10
echo "$(date +%T) who test"
bash "$HERE/test-ios26-who.sh" "$ROOT/ios26-who" 17 > "$ROOT/ios26-who/run.log" 2>&1
echo "$(date +%T) who test exit $?"
