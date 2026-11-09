# the phone's time zone, region and clock on root.qcow2, after the boot that ran DataMigration (on the PC
# guest-clock.py gives iOS the PC's during that boot)
progress 10 "this phone's time zone, region and clock"
disk_tools
in_root root.qcow2 <<'LOCALE'
python3 -I $A/phone/locale.py $R {LOCALE}
ls -l $R/private/var/db/timezone/localtime
LOCALE
