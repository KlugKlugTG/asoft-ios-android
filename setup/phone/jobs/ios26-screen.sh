# iOS 26's user disk before it starts at a screen it has not run at: a MobileGestalt cache made for another
# screen (the installed disk's last boot was the setup's, at 3x) is moved aside (phone/gestalt.py, Screen26)
progress 10 "iOS's screen settings"
disk_tools
in_root user.qcow2 <<'SCREEN'
python3 -I $A/phone/gestalt.py $R {WIDTH} {HEIGHT}
SCREEN
