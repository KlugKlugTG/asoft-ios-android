# ASoftware iOS for Android

iOS 16 in an Android app, on a stock phone: no root, locked bootloader, no Termux. The companion
of [ASoftware iOS for Windows](https://github.com/alpersamur3/asoft-ios), and it runs the iOS that
the Windows installer set up.

Research preview. Tested on one phone so far: Galaxy S25 Ultra (Snapdragon 8 Elite, 12 GB RAM).

## What works

- iOS 16.7 (20H392) full screen, touch, iOS's own keyboard, Safari with HTTPS pages.
- Side bar: Home, side button, 💾 saves. The phone's volume keys are iOS's. Android's back gesture
  is iOS's edge swipe. Turning the phone's screen off locks iOS too.
- Quick boot: swiping the app away, or Save and close, keeps iOS as it is; it comes back in about
  10 s instead of a 5 minute boot. Named saves (about 3 GB each) can be taken and loaded.
- Settings and apps you add stay on the phone's own copy of the disk.

Not working: App Store and Apple ID, audio, GPU acceleration (iOS 16 draws on the CPU).

## Setting it up

1. On a Windows PC, install ASoftware iOS with iOS 16.
2. Install this app on the phone (arm64, Android 10 or later, about 12 GB free).
3. On the PC, run `Send-ToPhone.cmd` from the ASoftware iOS folder. It packs that iOS into
   `ASoftware-iOS16.asoft` (about 6.5 GB) and, with the phone on USB and USB debugging on, sends it
   to the app. Otherwise copy the file to the phone and pick it in the app.
4. In the app: Set up > Import, then Start iOS 16. The first start takes about 5 minutes.

The file is made on your PC from what the Windows installer downloaded from Apple. Neither this
repository nor the app holds any Apple file. Do not share the file.

## How it works

QEMU runs as the app's child process. A locked phone gives an app no hypervisor, so QEMU emulates
XNU's kernel, but the iOS processes themselves run on the phone's CPU: the native EL0 engine maps
each iOS address space into a helper process and runs its arm64 code there, trapping to QEMU for
system calls and page faults. The screen is read straight from the guest's RAM into a GLES
texture each display frame; input goes over QMP.

## Building

- QEMU and `qemu-img`: [qemu-sptm](https://github.com/alpersamur3/qemu-sptm), branch
  `asoft-android`, built in Termux on the phone (`-D__TERMUX__ -O2 -fno-emulated-tls`).
  `pull-libs.sh` copies them (as `libqemu.so`, `libqemuimg.so`) and their Termux libraries into
  `app/src/main/jniLibs`.
- The app: `./gradlew assembleDebug` (Android Studio's JDK, SDK 36, NDK 29). A release build is
  signed when `~/.android/asoft-ios-release.properties` (or `$ASOFT_SIGNING`) names a key.
- Tests on a phone over adb: `test-*.sh` (input goes to the phone only while the app is on top
  and the phone is unlocked).

## Licenses

The app's code: MIT (LICENSE). The APK also carries:

- QEMU 11.1 (GPL v2): source at https://github.com/alpersamur3/qemu-sptm, branch `asoft-android`.
- Termux packages QEMU links (GLib, GnuTLS, Nettle, libslirp, pixman, zstd and others, under
  their own licenses): sources at https://github.com/termux/termux-packages.

Not affiliated with Apple. iOS is Apple's.
