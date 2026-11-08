"""The phone's time zone, region format and 12/24-hour clock on a guest root (PhoneSetup26, in the helper VM
inside in_root): what ios26/scripts/guest-clock.py does over the serial console on the PC, done on the disk
before its next start (the phone's setup runs it after the boot that runs DataMigration, which writes the
preferences itself).

    locale.py R [--tz Europe/Istanbul] [--region TR] [--clock 24|12]

--tz: /private/var/db/timezone/localtime becomes a link to /var/db/timezone/zoneinfo/<tz> (when the zone
  is in the image). --region, --clock: AppleLocale and the first of AppleLanguages (the language with the
  phone's region: English with TR is en_TR and en-TR) and AppleICUForce24HourTime or
  AppleICUForce12HourTime in mobile's .GlobalPreferences.plist (mobile 501:501, 0600, binary).
"""
import argparse, os, plistlib, re, sys

LINK = 'private/var/db/timezone/localtime'
ZONES = '/var/db/timezone/zoneinfo/'
PREFS = 'private/var/mobile/Library/Preferences/.GlobalPreferences.plist'


def main():
    a = argparse.ArgumentParser()
    a.add_argument('root')
    a.add_argument('--tz', default='')
    a.add_argument('--region', default='')
    a.add_argument('--clock', default='', choices=['', '12', '24'])
    o = a.parse_args()
    R = o.root
    if o.tz:
        if not re.fullmatch(r'[A-Za-z0-9_+-]+(/[A-Za-z0-9_+-]+)*', o.tz):
            print('time zone %s: not a zone name' % o.tz)
        elif not any(os.path.exists(os.path.join(R, p + o.tz)) for p in ('private/var/db/timezone/zoneinfo/', 'usr/share/zoneinfo/')):
            print('time zone %s: not in this image' % o.tz)
        else:
            link = os.path.join(R, LINK)
            os.makedirs(os.path.dirname(link), exist_ok=True)
            tmp = link + '.asoft'
            if os.path.lexists(tmp):
                os.remove(tmp)
            os.symlink(ZONES + o.tz, tmp)
            os.replace(tmp, link)
            print('time zone %s' % o.tz)
    if o.region and not re.fullmatch(r'[A-Z]{2}|[0-9]{3}', o.region):
        print('region %s: not a region code' % o.region)
        o.region = ''
    if o.region or o.clock:
        p = os.path.join(R, PREFS)
        prefs = {}
        if os.path.exists(p):
            with open(p, 'rb') as f:
                prefs = plistlib.load(f)
        if o.region:
            langs = prefs.get('AppleLanguages')
            langs = list(langs) if isinstance(langs, list) and langs and isinstance(langs[0], str) else ['en']
            parts = re.split(r'[-_]', langs[0].split('@')[0])
            lang = [parts[0].lower()] + [x for x in parts[1:] if len(x) == 4 and x.isalpha()][:1]
            langs[0] = '-'.join(lang + [o.region])
            prefs['AppleLanguages'] = langs
            prefs['AppleLocale'] = '_'.join(lang + [o.region])
            print('region %s (%s, %s)' % (o.region, prefs['AppleLocale'], langs[0]))
        if o.clock:
            h24 = o.clock == '24'
            prefs.pop('AppleICUForce12HourTime' if h24 else 'AppleICUForce24HourTime', None)
            prefs['AppleICUForce24HourTime' if h24 else 'AppleICUForce12HourTime'] = True
            print('%s-hour clock' % o.clock)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        tmp = p + '.asoft'
        with open(tmp, 'wb') as f:
            plistlib.dump(prefs, f, fmt=plistlib.FMT_BINARY)
        os.chown(tmp, 501, 501)
        os.chmod(tmp, 0o600)
        os.replace(tmp, p)


if __name__ == '__main__':
    sys.exit(main())
