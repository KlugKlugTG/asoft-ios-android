#!/usr/bin/env python3
"""iOS's own keyboard for the iOS 16 guest: stop the kernel from starting SweetHIDKeyboard.

  patch-softkbd.py IN OUT [--revert]

IN is a SWEETXNU payload.bin or a ram.bin made from one. Patch 0096 made every key that is not
F1-F4 a hardware keyboard; iOS then hides its software keyboard. The driver is one text
personality in gIOKernelConfigTables, so one byte in its 'IOProviderClass' (IOResources ->
IOResourceX) means it never matches and the service never starts:
sweet_hid_keyboard() does nothing when gSweetKeyboard is null, and F1-F4 go through
SweetHIDButtons as before. The length stays the same, so nothing else moves.
"""
import argparse
import sys

HEAD = b"'IOClass'         = SweetHIDKeyboard;"
ON = b"'IOProviderClass' = IOResources;"
OFF = b"'IOProviderClass' = IOResourceX;"


def find_provider(data):
    """Offset of the provider line of the keyboard's personality, exactly one in the image."""
    at = data.find(HEAD)
    if at < 0 or data.find(HEAD, at + 1) >= 0:
        sys.exit("no single SweetHIDKeyboard personality in this image")
    end = data.find(b"}", at)
    for line in (ON, OFF):
        p = data.find(line, at, end)
        if p >= 0:
            return p, line
    sys.exit("the personality has no IOProviderClass line this script knows")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--revert", action="store_true", help="turn the hardware keyboard back on")
    a = ap.parse_args()
    data = bytearray(open(a.input, "rb").read())
    at, now = find_provider(data)
    want = ON if a.revert else OFF
    data[at:at + len(want)] = want
    open(a.output, "wb").write(data)
    print(f"{a.output}: {now.decode()} -> {want.decode()} at {at:#x}")


if __name__ == "__main__":
    main()
