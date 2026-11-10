#!/usr/bin/env python3
"""patch-slirp.py LIBSLIRP: Termux builds libslirp to read Termux's resolv.conf; make it read the app's.

The path becomes a relative resolv.conf, which QEMU, started in the app's files dir (Render.spawn),
finds where Guest.writeResolvConf() keeps it under either package (com.asoft.ios, com.asoft.ios.dev).
The release package's absolute path, an earlier patch's, is taken over too: the dev package cannot
read the release package's files, and its guests resolved no names.
"""
import sys

p = sys.argv[1]
olds = [b'/data/data/com.termux/files/usr/etc/resolv.conf\0', b'/data/data/com.asoft.ios/files/resolv.conf\0']
new = b'resolv.conf\0'
d = open(p, 'rb').read()
found = [old for old in olds if d.count(old) == 1]
if len(found) != 1:
    sys.exit(f'{p}: expected one of {olds!r}')
old = found[0]
open(p, 'wb').write(d.replace(old, new.ljust(len(old), b'\0')))
print('libslirp: DNS path patched')
