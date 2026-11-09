#!/usr/bin/env python3
"""iOS 26's MobileGestalt cache and the screen it was made for.

    gestalt.py MOUNTED_ROOT WIDTH HEIGHT

iOS works its screen out once (pixels, scale, the island's place) and keeps the answers in
com.apple.MobileGestalt.plist on the Data volume; the file is made again only when the OS build
changes. A disk whose cache was written at another screen (the setup boots at 1179x2556 @3x, the
2x screen is 786x1704) makes iOS lay out for that one on the display it has: the status bar
finds no layout ("rdar:45025538" strip), the island and the icons come out 1.5 times too big.

Run on the mounted root (Data on private/var, root26-do.sh): a cache that does not name a screen
WIDTH x HEIGHT pixels is moved to .stale, and iOS writes a new one at its next boot. Prints one
line for the app:

    ASOFT: gestalt none               no cache (iOS makes one at its next boot)
    ASOFT: gestalt kept WxH@S         the cache is this screen's
    ASOFT: gestalt moved WHY          it was another screen's (or unreadable): moved to .stale
"""
import os
import plistlib
import struct
import sys

CACHE = ('private/var/containers/Shared/SystemGroup/systemgroup.com.apple.mobilegestaltcache/'
         'Library/Caches/com.apple.MobileGestalt.plist')


def screens(extra):
    """The screen records in CacheExtra: 24 bytes, width and height (u32), pitch (u32), scale (f32)."""
    out = []
    for v in extra.values():
        if not isinstance(v, bytes) or len(v) < 16:
            continue
        w, h, _, scale = struct.unpack_from('<IIIf', v)
        if 200 <= w <= 8000 and 200 <= h <= 8000 and 1.0 <= scale <= 4.0:
            out.append((w, h, scale))
    return out


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    root, width, height = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    path = os.path.join(root, CACHE)
    if not os.path.exists(path):
        print('ASOFT: gestalt none')
        return
    try:
        with open(path, 'rb') as f:
            found = screens(plistlib.load(f).get('CacheExtra', {}))
        why = ', '.join('%dx%d@%g' % s for s in found) or 'no screen in it'
    except Exception as e:      # a cache nothing here can read is no use to trust
        found, why = [], 'unreadable (%s)' % type(e).__name__
    if any(w == width and h == height for w, h, _ in found):
        print('ASOFT: gestalt kept ' + why)
        return
    os.replace(path, path + '.stale')
    print('ASOFT: gestalt moved ' + why)


if __name__ == '__main__':
    main()
