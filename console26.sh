#!/bin/bash
# console26.sh 'CMD' [WAIT_S=20]: runs CMD in iOS 26's root shell on the dev app's console socket (ios26.txt
# console=socket) and prints what it printed. The command is wrapped as echo @@S""ID@@; CMD; echo; echo @@E""ID@@ (ID unique
# per call) and its output read back from the console's log (files/run/serial.txt), where QEMU writes all the
# console prints: toybox nc gives nothing back on the phone. Kernel lines printed meanwhile are mixed in (the
# endless hfs hardlink and SEP lines are dropped).
CMD=${1:?command}; WAIT=${2:-20}
PKG=${PKG:-com.asoft.ios.dev}
export MSYS_NO_PATHCONV=1
ID=$(date +%s%N | tail -c 9)
T=${TMPDIR:-/tmp}/console26.in
printf '\necho @@S""%s@@; %s; echo; echo @@E""%s@@\n' "$ID" "$CMD" "$ID" > "$T"
adb push "$(cygpath -w "$T")" /data/local/tmp/console26.in > /dev/null 2>&1
adb shell "chmod 644 /data/local/tmp/console26.in; run-as $PKG sh -c 'cat /data/local/tmp/console26.in | toybox nc -q 1 -U files/run/console.sock'" > /dev/null 2>&1
end=$(( $(date +%s) + WAIT ))
while :; do
    out=$(adb exec-out "run-as $PKG grep -a -c '^@@E$ID@@' files/run/serial.txt" | tr -d '\r')
    [ "${out:-0}" -gt 0 ] && break
    [ "$(date +%s)" -ge "$end" ] && { echo "(console26: no end marker after $WAIT s; partial output)" >&2; break; }
    sleep 1
done
adb exec-out "run-as $PKG cat files/run/serial.txt" | tr -d '\r' | sed -n "/^@@S$ID@@\$/,/^@@E$ID@@\$/p" | sed '1d;$d' |
    grep -av 'hfs: set hardlink bit\|"AppleSEPKeyStore"'
