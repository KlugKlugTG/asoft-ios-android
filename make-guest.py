#!/usr/bin/env python3
"""The iOS 16 guest's boot files for the app: ram.bin and stub.bin from a kernel.

  make-guest.py KERNEL ADT OUTDIR [--cmdline "..."] [--hw-keyboard]

KERNEL is an ASoftware XNU (ios-sweet/xnu/out-*/kernel.development.sweet), ADT the device tree
from tools/mkadt.py --qemu --highmem. Builds the SWEETXNU payload (tools/mkpayload.py), turns
the hardware keyboard off unless --hw-keyboard (patch-softkbd.py: iOS shows its own keyboard),
and lays it out in RAM as qemu-sweet.py does. Guest.java loads ram.bin at 0x100000000 and
stub.bin at 0x40200000: this checks the payload keeps those.
"""
import argparse
import importlib.util
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.join(HERE, '..', 'ios-sweet', 'tools')
# the phone's boot-args (2026-11-02), with the PC's time zone and region
CMDLINE = ('debug=0xc serial=3 cpus=4 keepsyms=1 -enable_kprintf_spam -noprogress rd=vblkroot0 vblkrw=1 '
           'rootrw=1 unentitled_ios_sim_launch=1 ignition_level=1 wdt_test=no_user_checkin '
           'launchd_unsecure_cache=1 wdt=-1 sweetfb_hz=30 launchd_disable_all_timeouts=1 sweetfb_noconsole=1 '
           'sweetfb_keepon=1 asoft_tz=Europe/Istanbul asoft_region=TR asoft_clock=24')


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('kernel')
    ap.add_argument('adt')
    ap.add_argument('out')
    ap.add_argument('--cmdline', default=CMDLINE)
    ap.add_argument('--hw-keyboard', action='store_true', help='keep SweetHIDKeyboard (no iOS keyboard)')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    payload = os.path.join(a.out, 'payload.bin')
    subprocess.check_call([sys.executable, os.path.join(TOOLS, 'mkpayload.py'), a.kernel, a.adt, payload,
                           '--cmdline', a.cmdline])
    if not a.hw_keyboard:
        subprocess.check_call([sys.executable, os.path.join(HERE, 'patch-softkbd.py'), payload, payload])
    sweet = load('qemu_sweet', os.path.join(TOOLS, 'qemu-sweet.py'))
    data = open(payload, 'rb').read()
    phys_base, img, ba_pa, entry_pa = sweet.build_image(data)
    if phys_base != 0x100000000 or sweet.STUB_PA != 0x40200000:
        sys.exit(f'phys_base {phys_base:#x} / stub {sweet.STUB_PA:#x}: Guest.java expects 0x100000000 / 0x40200000')
    open(os.path.join(a.out, 'ram.bin'), 'wb').write(img)
    open(os.path.join(a.out, 'stub.bin'), 'wb').write(sweet.stub(ba_pa, entry_pa))
    print(f'{a.out}: ram.bin {len(img)} B at {phys_base:#x}, entry {entry_pa:#x}')


if __name__ == '__main__':
    main()
