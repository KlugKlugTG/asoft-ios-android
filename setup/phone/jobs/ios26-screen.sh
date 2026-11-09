# iOS 26's user disk before it starts (Screen26): a MobileGestalt cache made for another screen (the installed
# disk's last boot was the setup's, at 3x) is moved aside (phone/gestalt.py), and the folder of the Auto-Lock
# setting goes to mobile where an earlier setup made it as root (phone/autolock.py)
progress 10 "iOS's screen settings"
disk_tools
in_root user.qcow2 <<'SCREEN'
python3 -I $A/phone/gestalt.py $R {WIDTH} {HEIGHT}
python3 -I $A/phone/autolock.py $R
SCREEN
