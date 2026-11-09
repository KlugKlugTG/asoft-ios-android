# ASoftware iOS for Android

iOS 16, and as previews iOS 26 and watchOS, in an Android app, on a stock phone: no root, locked
bootloader, no Termux. Each can be installed on the phone itself from Apple's servers (iOS 26 and
watchOS after a check of the phone's GPU); iOS 16 can also be brought from a PC. The companion of
[ASoftware iOS for Windows](https://github.com/alpersamur3/asoft-ios).

Research preview. Tested on a Galaxy S25 Ultra (Snapdragon 8 Elite, 12 GB RAM); iOS 16 also on a
Galaxy A71 and a Galaxy M31 (much slower: their CPUs cannot run iOS's code natively, see below).

## Which phones

Nothing in it is specific to the S25 Ultra; it is the phone it is developed and measured on.

| | Needs | Recommended |
|---|---|---|
| iOS 16 | 64-bit Android 10 or later, 6 GB RAM | ARMv8.2-A or later (iOS's code runs natively), 8 GB RAM or more |
| iOS 26, watchOS | the same, plus an Adreno GPU (Mesa's Turnip driver) | ARMv8.3-A or later (pointer authentication), 12 GB RAM |
| Room | iOS 16 and iOS 26 about 12 GB each, watchOS about 5 GB; more while installing from Apple (below) | |

- Without the ARMv8.2 features below, iOS still runs, all of it emulated, several times slower.
- iOS 26 and watchOS are arm64e. On a CPU without pointer authentication (before ARMv8.3) their PAC
  instructions cannot run natively; untested here, expect them to be slower or not to start.
- Mali GPUs (most Exynos and MediaTek phones) have no Vulkan driver the GPU path works with yet: iOS
  26 and watchOS do not run there, iOS 16 does (it draws on the CPU). Set up's GPU check says so
  before anything is downloaded.
- Speed is mostly the CPU's big cores: an older or smaller core is slower whatever its ARM version.
- RAM: iOS 16 reached its lock screen on a 6 GB phone (Galaxy M31) with about 0.2 GB left to Android;
  with less to spare, Android may close it. iOS 26 and watchOS have been run only on a 12 GB phone.
  Their memory is set under More: iOS 26 3, 4 or 6 GB, watchOS 2 or 3 GB (the smaller by default on a
  phone under 10 GB). On the S25 Ultra iOS 26 left Android 1.5 GB at 3 GB and 0.8 to 1.0 GB at 6 GB,
  where the app warns that Android may close it. Less RAM is a warning, not a stop.

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
- Turn off (in 💾, or the notification) shuts iOS down without a save; the next start boots. The
  last quick save stays as a way back: Go back, on the home screen or in 💾, returns to it with the
  memory it was saved with, until the next Save and close replaces it.
- Settings and apps you add stay on the phone's own copy of the disk.

Not working: App Store and Apple ID, audio, GPU acceleration on iOS 16 (it draws on the CPU).

### iOS 26 (preview)

iOS 26.6.2 in Apple's research VM (vphone600), drawing on the phone's GPU: Metal is translated to
Vulkan (reims-vgpu, metal2vulkan) on Mesa's Turnip driver for Adreno. Snapdragon 8 Elite (Adreno 830)
only so far. Home screen, apps, touch, keyboard, Liquid Glass; quick boot and saves as for iOS 16, and
an automatic save before Android could run out of memory. It is slow for about 15 minutes after a
boot. Set up installs it from Apple (below). The screen is 2x by default (faster); 3x (sharp) is under More. On a disk set up with Always On Display, the side button takes iOS to it
and a touch there wakes it; iOS's own Auto-Lock is set to Never there, because the app cannot
see iOS go to its Always On Display by itself.

### watchOS (preview)

watchOS 27.2 as an Apple Watch Series 9, on the phone's GPU like iOS 26, in a drawn watch: tap the
crown to press it, drag it to turn it; the side button is under it. Its own disk and saves. The first
start boots in 5 to 7 minutes (longer on a hot phone); a save comes back in about 5 s with the right
time. Set up installs it from Apple, with or without iOS 26.

## Installing on the phone from Apple (no PC)

Set up builds iOS 16, iOS 26 and watchOS on the phone itself from Apple's files, the way the
Windows installers do: no PC, no adb.

### iOS 16

- It downloads only the parts of Apple's iOS 16.7.16 IPSW (iPhone X) the disk is made of, 5.8 GB,
  straight from Apple's servers: 8 connections, 32 MB pieces written in place, each part checked
  against the IPSW's own CRC-32. It picks the fastest of Apple's servers (the phone's own DNS
  answer was a cache giving 0.1 MB/s per connection, Apple's servers up to 6 MB/s), goes on after
  an app kill, a restart or a lost network, can wait for Wi-Fi, and has Pause and Cancel on its
  notification.
- Then the Windows installer's helper VM (a small arm64 Linux that comes with the app) runs in the
  app's QEMU: it makes the kernel with the iPhone's device tree and builds the iOS disk from the
  downloaded images, as `Build-iOSDisk.ps1` does on Windows. The disk is packed (6.2 GB) and set up
  like one brought from a PC, then iOS is started once until its lock screen and saved, so your
  first start takes seconds.
- On a Galaxy S25 Ultra (measured from the app, 2026-11-08): the download 26 minutes, the disk 21
  (the helper's Linux programs run on the phone's CPU, as iOS's apps do, so this step is as fast as
  on our PC), packing 2, iOS's first start 23 (38 in an earlier run): about 75 minutes from Apple's
  servers to the lock screen. It needs about 24 GB free at its height (with less, the downloaded
  files go as soon as the disk no longer needs them) and leaves about 10 GB: the disk (6.2 GB) and
  iOS's own data with its save. Keep the phone on its charger.

### iOS 26 and watchOS

- First a GPU check: a few Vulkan tests on the app's Turnip, about 10 s, nothing downloaded. When it
  fails, the screen says whether iOS 26 will not run on this phone or is not supported yet, with
  its log to copy or share (please send it).
- Their shared part comes once, whichever is installed first: cloudOS 26.4's kernel, firmware and
  GPU driver from Apple, about 1 GB (3.5 minutes on the S25 Ultra).
- Then each OS's own IPSW parts from Apple, and the helper VM builds its disk with the Windows
  installers' own steps. iOS 26 is then started once through its first-boot work (iOS's data
  migration, about an hour), watchOS until its watch face.
- On a Galaxy S25 Ultra (2026-11-08/09): watchOS 5 GB from Apple, about 65 minutes in all (the
  download 14, its root file system 32, the first start 10), about 21 GB free at its height, leaves
  about 5 GB. iOS 26 10.2 GB from Apple, about 2.5 hours (the download 32 minutes, its root file
  system 32, the data migration about 60), about 46 GB free at its height, leaves about 12 GB.
- Besides Apple's servers they fetch, from GitHub: a small iOS sysroot (bash and file tools the
  disk steps run, [jprx/darwin-vm](https://github.com/jprx/darwin-vm)), our device-tree fix-up
  (qemu-sptm), the GPU compiler plugin (vphone-cli's open-source GraphicLoader with our patch, an
  asoft-ios release), and the FCS key list
  [blacktop/ipsw](https://github.com/blacktop/ipsw) carries, used when Apple's key service does not
  answer.

The app and this repository hold no Apple file: what is built comes from Apple's servers on the phone.

## Setting it up from a PC

1. On a Windows PC, install ASoftware iOS with iOS 16.
2. Install this app on the phone (see Which phones; about 12 GB free).
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
- The setup from Apple: `pull-setup.sh [ASOFT_IOS_CHECKOUT]` copies the helper VM (`Image.gz`,
  `initrd.gz`; `Image16k.gz` from `$KERNEL16K`, built with `setup/helper-kernel16k.config`), the
  iOS 16 kernel, the tools that make `payload.bin` and `asoft-setup.json` (the member and step list
  the installers share), and for iOS 26 and watchOS the Windows installers' Linux step bodies and
  scripts (`ios26/`, `watchos/` there) with this repository's `setup/phone` jobs, into
  `app/src/main/assets`.
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
- For the setup from Apple: our XNU kernel for iOS 16 (Apple Public Source License 2.0; built from
  Apple's XNU with the patches in [asoft-ios](https://github.com/alpersamur3/asoft-ios) `xnu/`),
  and the Windows installer's helper VM (`installer/helper` there): Linux 6.12 (GPL v2; kernel.org's
  6.12.114 with `installer/helper/kernel.config`, and for the phone's engine also
  `setup/helper-kernel16k.config` here), Ubuntu 24.04 arm64 packages under their own licenses
  (sources at https://launchpad.net/ubuntu), and apfs-fuse (GPL v2,
  https://github.com/sgan81/apfs-fuse).
- Termux packages QEMU links (GLib, GnuTLS, Nettle, libslirp, pixman, zstd and others, under
  their own licenses): sources at https://github.com/termux/termux-packages.
- Conscrypt (Apache 2.0, https://github.com/google/conscrypt): TLS 1.3 for the pairing with Wireless
  debugging. The adb client itself (`Adb*.java`, `Spake2.java`) is the app's own, written after
  AOSP's adb (Apache 2.0); SPAKE2's two points are BoringSSL's.

Not affiliated with Apple. iOS is Apple's.
