#!/usr/bin/env python3
"""iOS 26's Auto-Lock setting and the folder it lives in.

    autolock.py MOUNTED_ROOT

Auto-Lock is managed configuration's "maxInactivity": the user's choice in UserSettings.plist, and
what iOS acts on in EffectiveUserSettings.plist beside it, which profiled (user mobile) writes. The
setup wrote UserSettings.plist (Auto-Lock Never, root26-aod.py) into a folder that did not exist
yet and so made it as root: profiled could never write the effective settings there, iOS fell back
to its own one minute, and went to its Always On Display by itself, which the app cannot see (a
swipe then unlocked a dimmed iOS, 2026-11-10).

Run on the mounted root (Data on private/var, root26-do.sh): the folder (and PublicInfo in it)
goes to mobile, and profiled writes the effective settings at the next boot. Prints one line for
the app:

    ASOFT: autolock none              no such folder (iOS makes it itself)
    ASOFT: autolock ok                the folder is mobile's already
    ASOFT: autolock fixed UID:GID     it was UID:GID's: now mobile's
"""
import os
import sys

MOBILE = 501
FOLDER = 'private/var/mobile/Library/UserConfigurationProfiles'


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    d = os.path.join(sys.argv[1], FOLDER)
    if not os.path.isdir(d):
        print('ASOFT: autolock none')
        return
    was = None
    for p in (d, os.path.join(d, 'PublicInfo')):
        if not os.path.isdir(p):
            continue
        st = os.stat(p)
        if st.st_uid != MOBILE or st.st_gid != MOBILE:
            was = was or '%d:%d' % (st.st_uid, st.st_gid)
            os.chown(p, MOBILE, MOBILE)
            os.chmod(p, 0o755)
    print('ASOFT: autolock fixed ' + was if was else 'ASOFT: autolock ok')


if __name__ == '__main__':
    main()
