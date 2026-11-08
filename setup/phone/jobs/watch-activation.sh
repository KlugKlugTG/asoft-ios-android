# Install-watchOS.ps1's activation step (after the first boot), and the phone's time zone, region and clock
progress 10 "activation"
step activation
in_root watchos/watch.qcow2 <<'LOCALE'
python3 -I $A/phone/locale.py $R {LOCALE}
LOCALE
