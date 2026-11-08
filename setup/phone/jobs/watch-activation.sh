# Install-watchOS.ps1's activation step (after the first boot), and the phone's time zone, region and clock
progress 5 "the first boot's crash reports (for the log)"
disk_tools
in_root watchos/watch.qcow2 <<'LIST'
d=$R/private/var/mobile/Library/Logs/CrashReporter
ls $d 2> /dev/null | sed -E 's/-[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{6}.*//' | sort | uniq -c | sort -rn | head -15 | sed 's/^/crash reports: /'
LIST
progress 10 "activation"
step activation
in_root watchos/watch.qcow2 <<'LOCALE'
python3 -I $A/phone/locale.py $R {LOCALE}
LOCALE
