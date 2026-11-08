# ASoftware iOS for Android

iOS 16, and as a preview iOS 26, in an Android app, on a stock phone: no root, locked bootloader,
no Termux. The companion
of [ASoftware iOS for Windows](https://github.com/alpersamur3/asoft-ios), and it runs the iOS that
the Windows installer set up.

Research preview. Tested on a Galaxy S25 Ultra (Snapdragon 8 Elite, 12 GB RAM); iOS 16 also on a
Galaxy A71 and a Galaxy M31 (much slower: their CPUs cannot run iOS's code natively, see below).

## What works

- iOS 16.7 (20H392) full screen, touch, iOS's own keyboard, Safari with HTTPS pages.
- Side bar (right edge): tap its handle to show or hide the buttons, drag it to move the bar up or
  down; it stays where you leave it. Home, the side button, a touch lock (touches stop reaching iOS),
  pin (Android's screen pinning: the phone's edge swipes no longer pull down the notification shade
  or go home over iOS), saves. A long press on a button says what it does.
- The phone's volume keys are iOS's (held, they repeat). Android's back gesture is iOS's edge swipe.
  Turning the phone's screen off locks iOS too.
- Quick boot: swiping the app away, or Save and close, keeps iOS as it is; it comes back in about
  10 s instead of a boot of a few minutes. Named saves (about 3 GB each) can be taken and loaded.
- Turn off (in 💾, or the notification) shuts iOS down without a save; the next start boots.
- Settings and apps you add stay on the phone's own copy of the disk.

Not working: App Store and Apple ID, audio, GPU acceleration on iOS 16 (it draws on the CPU).

### iOS 26 (preview)

iOS 26.6.2 in Apple's research VM (vphone600), drawing on the phone's GPU: Metal is translated to
Vulkan (reims-vgpu, metal2vulkan) on Mesa's Turnip driver for Adreno. Snapdragon 8 Elite (Adreno 830)
only so far. Home screen, apps, touch, keyboard, Liquid Glass; quick boot and saves as for iOS 16, and
an automatic save before Android could run out of memory. It is slow for about 15 minutes after a
boot. The iOS 26 card appears once its files are in `/data/local/tmp/asoft/ios26` (put there with adb
from the Windows install; there is no Send-ToPhone package for iOS 26 yet). 3x (sharp) or 2x
(faster) screen under More.

## Setting it up

1. On a Windows PC, install ASoftware iOS with iOS 16.
2. Install this app on the phone (arm64, Android 10 or later, about 12 GB free).
3. On the PC, run `Send-ToPhone.cmd` from the ASoftware iOS folder. It packs that iOS into
   `ASoftware-iOS16.asoft` (about 6.5 GB) and, with the phone on USB and USB debugging on, sends it
   to the app. Otherwise copy the file to the phone and pick it in the app.
4. In the app: Set up > Import, then Start iOS 16. The first start takes a few minutes (2 to 4 on
   our phone).

The file is made on your PC from what the Windows installer downloaded from Apple. Neither this
repository nor the app holds any Apple file. Do not share the file.

## How it works

QEMU runs as the app's child process. A locked phone gives an app no hypervisor, so QEMU emulates
XNU's kernel, but the iOS processes themselves run on the phone's CPU: the native EL0 engine maps
each iOS address space into a helper process and runs its arm64 code there, trapping to QEMU for
system calls and page faults. On CPUs without the ARMv8.2 features iOS uses (LSE atomics, RDM,
dot product, FP16, RCpc) the engine stays off and QEMU emulates everything. The screen is read straight from the guest's RAM into a GLES
texture each display frame; input goes over QMP.

## Building

- QEMU and `qemu-img`: [qemu-sptm](https://github.com/alpersamur3/qemu-sptm), branch
  `asoft-android`, built in Termux on the phone (`-D__TERMUX__ -O2 -fno-emulated-tls`).
  `pull-libs.sh` copies them (as `libqemu.so`, `libqemuimg.so`) and their Termux libraries into
  `app/src/main/jniLibs`.
- iOS 26's QEMU (`libqemu26.so`): the same branch with reims-vgpu and metal2vulkan linked in, built
  with the NDK; Turnip and the Vulkan loader beside it. `pull-libs26.sh` puts them in
  `app/src/main/jniLibs26`.
- The app: `./gradlew assembleDebug` (Android Studio's JDK, SDK 36, NDK 29). A release build is
  signed when `~/.android/asoft-ios-release.properties` (or `$ASOFT_SIGNING`) names a key.
- Tests on a phone over adb: `test-*.sh` (input goes to the phone only while the app is on top
  and the phone is unlocked).

## Licenses

The app's code: MIT (LICENSE). The APK also carries:

- QEMU 11.1 (GPL v2): source at https://github.com/alpersamur3/qemu-sptm, branch `asoft-android`.
- reims-vgpu and metal2vulkan (GPL v2 or later, or LGPL v3 or later): sources at
  https://github.com/alpersamur3/reims-vgpu and https://github.com/alpersamur3/metal2vulkan, branch
  `asoft-android`.
- Mesa's Turnip Vulkan driver (MIT) and the Khronos Vulkan loader (Apache 2.0).
- Termux packages QEMU links (GLib, GnuTLS, Nettle, libslirp, pixman, zstd and others, under
  their own licenses): sources at https://github.com/termux/termux-packages.

Not affiliated with Apple. iOS is Apple's.
