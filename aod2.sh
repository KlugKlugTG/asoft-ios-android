#!/bin/bash
# aod2.sh start OUTDIR DTREE | end OUTDIR: iOS 26 on a throwaway test disk with the device tree DTREE (a file in the
# phone's iOS 26 folder), its console shell on a socket (console26.sh), AutoSave off; left running for work by hand.
# start sets the user's disk (the app's current scale) and every file of its saves aside (*.keep) and keeps the
# user's ios26.txt in OUTDIR; end stops QEMU (the app's off), keeps the logs, deletes the test disk and puts the
# user's files and ios26.txt back. Run end even after a failed start.
WHAT=${1:?start|end}; OUT=${2:?output directory}; DTREE=${3:-}
PKG=com.asoft.ios.dev
EXT=/sdcard/Android/data/$PKG/files
export MSYS_NO_PATHCONV=1
mkdir -p "$OUT"
rs() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
qpid() { adb shell "ps -A -o PID,ARGS | grep '[l]ibqemu26.so -name' | awk '{print \$1}'" | tr -d '\r' | head -n 1; }
log() { echo "$(date +%T) $*" | tee -a "$OUT/aod2.txt"; }
SCALE=$(rs 'grep -o "ios26-scale\" value=\"[0-9]" shared_prefs/guest.xml' | tail -c 2)
DISK=$([ "$SCALE" = 2 ] && echo ios26-2x-user || echo ios26-user)
if [ "$WHAT" = start ]; then
    [ -n "$DTREE" ] || { echo "start needs DTREE"; exit 1; }
    [ -z "$(qpid)" ] || { echo "a QEMU of the dev app runs already"; exit 1; }
    # files set aside already (a start without its end): moving again would put the test disk over the user's
    [ -z "$(rs "cd files && ls $DISK.*.keep 2>/dev/null")" ] || { echo "$DISK files are set aside already: aod2.sh end first"; exit 1; }
    [ "$DTREE" = auto ] || adb shell "test -r /data/local/tmp/asoft/ios26/$DTREE && chmod 666 /data/local/tmp/asoft/ios26/$DTREE" || { echo "no $DTREE"; exit 1; }
    [ -f "$OUT/ios26-user.txt" ] || adb shell "cat $EXT/ios26.txt" | tr -d '\r' > "$OUT/ios26-user.txt"
    log "user disk $DISK: set aside"
    rs "cd files && for f in $DISK.*; do case \$f in *.keep) ;; *) mv \$f \$f.keep;; esac; done; ls | grep $DISK"
    # BASE=X.qcow2 (in the iOS 26 folder): the test disk on X instead of the app's root26r (made here, empty)
    if [ -n "${BASE:-}" ]; then
        # made in WSL (D:\ios26-m2) with the phone's backing path, unchecked (-u); the root chain's virtual size
        rm -f /d/ios26-m2/aod2-test.qcow2
        wsl -d ios-emu -u root -- qemu-img create -q -f qcow2 -u -b "/data/local/tmp/asoft/ios26/$BASE" -F qcow2 \
            /mnt/d/ios26-m2/aod2-test.qcow2 51541704704 || { echo "qemu-img create failed"; exit 1; }
        adb push 'D:\ios26-m2\aod2-test.qcow2' /data/local/tmp/aod2-test.qcow2 > /dev/null 2>&1
        rs "cat /data/local/tmp/aod2-test.qcow2 > files/$DISK.qcow2"
        log "test disk on $BASE"
    fi
    # TC=X.tc (in the iOS 26 folder): root26m.tc replaced by X for the test (kept as root26m.tc.aodkeep; end puts it back)
    if [ -n "${TC:-}" ]; then
        adb shell "cd /data/local/tmp/asoft/ios26 && [ ! -e root26m.tc.aodkeep ] && cp -p root26m.tc root26m.tc.aodkeep && cat $TC > root26m.tc && sha256sum root26m.tc.aodkeep root26m.tc"
        log "trust cache root26m.tc = $TC for the test"
    fi
    # DTREE=auto: no dtree line, the app picks the tree (and trust cache) from the disk's base
    printf '%s\n' "touchlog=1" "env.ASOFT_LAT=1" "console=socket" "autosave-maps=999999" "autosave-settle-s=999999" \
        > "$OUT/ios26.in"
    [ "$DTREE" = auto ] || echo "dtree=$DTREE" >> "$OUT/ios26.in"
    adb push "$(cygpath -w "$OUT/ios26.in")" "$EXT/ios26.txt" > /dev/null
    adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService --ez cold true" > /dev/null
    sleep 2
    adb shell "am start -n $PKG/com.asoft.ios.ScreenActivity" > /dev/null
    sleep 15
    [ -n "$(qpid)" ] || { log "QEMU ended at once: $(rs 'tail -n 3 files/run/qemu-stderr.txt' | tr '\n' ' ')"; exit 1; }
    log "booting with $DTREE (pid $(qpid)); console: console26.sh 'CMD'; finish with: aod2.sh end $OUT"
else
    log "end: the app's off"
    adb shell "am start-foreground-service -n $PKG/com.asoft.ios.GuestService -a off" > /dev/null 2>&1
    t=0; while [ -n "$(qpid)" ] && [ $t -lt 60 ]; do sleep 1; t=$((t + 1)); done
    for f in qemu-stderr serial app; do rs "cat files/run/$f.txt" > "$OUT/$f.txt"; done
    rs "cd files && if ls $DISK.qcow2.keep > /dev/null 2>&1; then for f in $DISK.*; do case \$f in *.keep) ;; *) rm -f \$f;; esac; done; for f in $DISK.*.keep; do mv \$f \${f%.keep}; done; fi; ls -la | grep $DISK"
    [ -f "$OUT/ios26-user.txt" ] && adb push "$(cygpath -w "$OUT/ios26-user.txt")" "$EXT/ios26.txt" > /dev/null
    adb shell "cd /data/local/tmp/asoft/ios26 && if [ -e root26m.tc.aodkeep ]; then cat root26m.tc.aodkeep > root26m.tc && rm root26m.tc.aodkeep; fi; sha256sum root26m.tc"
    adb shell "rm -f /data/local/tmp/aod2-test.qcow2"
    log "user files back; ios26.txt: $(adb shell cat $EXT/ios26.txt | tr -d '\r' | tr '\n' ' ')"
fi
