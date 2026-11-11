# dev/

Scripts used while developing and measuring the app on a phone over adb. The app and its build do not use them.

- `guarded.sh`, `tap.sh`, `tools/bar-xy.sh`: sourced by the tests. Input and screenshots go to the phone only while the
  app's screen is on top and the phone is unlocked.
- `qmp26.sh`, `console26.sh`: the running iOS 26's test QMP and its root shell (debug builds, `com.asoft.ios.dev`).
- `test-*.sh`: end-to-end runs (setup, quick boot, saves, turn off, update, release smoke tests); `test-ios26-*.sh` for
  iOS 26 (boot, settle, saves, AOD, memory peaks, A/B runs of engine options).
- `*-report.py`, `bench-*.py`, `maps-*.{sh,py}`, `post-settle.sh`: turn what the tests recorded into numbers.
- `make-guest.py`, `patch-softkbd.py`: build an iOS 16 test payload by hand.
- `aod2.sh`: iOS 26 on a throwaway test disk with another device tree.
- `tools/`: small helpers pushed to the phone (`maps-peak.sh`, `leds-log.sh`, `drag-rate.sh`), C probes, the icon
  generator, and the Wireless debugging pairing test (`test-wadb-pc.sh`).

Run them from anywhere: they find each other through their own folder.
