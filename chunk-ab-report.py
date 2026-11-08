"""chunk-ab-report.py OUTDIR: test-ios26-chunk-ab.sh's arms side by side.

Per arm (OUTDIR/chunk-*): the gather lines of qemu-stderr.txt from the unlock on (pinned chunk MiB against the live
gathered buffers' MiB, mean and most), the last line's imports and binds with their time, MemAvailable's lowest
(mem.txt), and from settle.txt (settle-report.py) the mean first reaction and settle time of the app opens and of the
returns Home, and the frames per second of the opens, per round.
"""
import os
import re
import sys

GATHER = re.compile(r'gathered: (\d+) buffers live of (\d+) MiB \((\d+) made, (\d+) binds in (\d+) ms\), '
                    r'(\d+) chunks of (\d+) MiB live \((\d+) imported(?: in (\d+) ms)?\)')
ROW = re.compile(r'^(r\d)-(home)?(\w+)\s+(\d+)\s+([\d.]+|-)\s+([\d.]+|-)\s+(\d+)\s+([\d.]+)')


def mean(xs):
    return sum(xs) / len(xs) if xs else float('nan')


def arm(d):
    text = open(os.path.join(d, 'qemu-stderr.txt'), encoding='utf-8', errors='replace').read()
    rows = [[int(x) if x is not None else 0 for x in m.groups()] for m in GATHER.finditer(text)]
    rows = [r for r in rows if r[1] > 0]
    after = rows[6:] or rows
    out = {
        'pinned': mean([r[6] for r in after]),
        'pinned_max': max((r[6] for r in rows), default=0),
        'live': mean([r[1] for r in after]),
        'imports': rows[-1][7] if rows else 0,
        'import_ms': rows[-1][8] if rows else 0,
        'binds': rows[-1][3] if rows else 0,
        'bind_ms': rows[-1][4] if rows else 0,
        'seconds': 30 * len(rows),
    }
    try:
        avail = [int(m.group(1)) for m in re.finditer(r'MemAvailable: (\d+)', open(os.path.join(d, 'mem.txt')).read())]
        out['avail_min'] = min(avail)
    except (OSError, ValueError):
        out['avail_min'] = 0
    opens = {}
    homes = {}
    try:
        for line in open(os.path.join(d, 'settle.txt')):
            m = ROW.match(line)
            if not m or m.group(5) == '-':
                continue
            rnd, home = m.group(1), m.group(2)
            first, settle, fps = float(m.group(5)), float(m.group(6)), float(m.group(8))
            (homes if home else opens).setdefault(rnd, []).append((first, settle, fps))
    except OSError:
        pass
    out['opens'] = opens
    out['homes'] = homes
    return out


def main(outdir):
    arms = sorted(n for n in os.listdir(outdir) if n.startswith('chunk-'))
    print('%-12s %8s %8s %6s %6s %9s %10s %9s %10s %9s' % (
        'arm', 'pinned', 'max', 'live', 'x', 'imports', 'import ms', 'binds', 'bind ms', 'avail min'))
    res = {}
    for name in arms:
        r = arm(os.path.join(outdir, name))
        res[name] = r
        print('%-12s %8.0f %8d %6.0f %6.1f %9d %10d %9d %10d %9d' % (
            name, r['pinned'], r['pinned_max'], r['live'], r['pinned'] / r['live'] if r['live'] else 0,
            r['imports'], r['import_ms'], r['binds'], r['bind_ms'], r['avail_min']))
    print()
    print('%-12s %-4s %24s %24s' % ('arm', 'rnd', 'app open first/settle/fps', 'Home first/settle'))
    for name in arms:
        r = res[name]
        for rnd in sorted(set(r['opens']) | set(r['homes'])):
            o, h = r['opens'].get(rnd, []), r['homes'].get(rnd, [])
            print('%-12s %-4s %7.2f %7.2f %7.1f   %7.2f %7.2f   (n %d/%d)' % (
                name, rnd, mean([x[0] for x in o]), mean([x[1] for x in o]), mean([x[2] for x in o]),
                mean([x[0] for x in h]), mean([x[1] for x in h]), len(o), len(h)))


if __name__ == '__main__':
    main(sys.argv[1])
