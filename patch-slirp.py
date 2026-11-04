#!/usr/bin/env python3
"""patch-slirp.py LIBSLIRP: Termux builds libslirp to read Termux's resolv.conf; make it read the app's."""
import sys

p = sys.argv[1]
old = b'/data/data/com.termux/files/usr/etc/resolv.conf'
new = b'/data/data/com.asoft.ios/files/resolv.conf'
d = open(p, 'rb').read()
if d.count(old) != 1 or len(new) > len(old):
    sys.exit(f'{p}: expected one {old!r}')
open(p, 'wb').write(d.replace(old, new.ljust(len(old), b'\0')))
print('libslirp: DNS path patched')
