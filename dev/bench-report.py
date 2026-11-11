"""bench-report.py OUTDIR...: what test-ios26-bench.sh runs measured, a block per run.

Times are seconds on the phone's boot-time clock from marks.txt; reims' LAT frame stamps (CLOCK_MONOTONIC)
are moved onto it with the 'LAT clocks' line. A frame "changes" when at least 1% of its sampled pixels
differ from the previous one. For each input: the first change after it and the last change before the
next input; for Calculator also the first change of 20% or more (the app's window opening).
BQL: the 'bql t=' reports summed over the run, the share of wall time the BQL was held per 30 s.
"""
import collections
import re
import sys


def load(d):
    marks = {}
    for l in open(d + '/marks.txt'):
        k, v = l.split()
        marks[k] = float(v)
    frames, offset = [], None
    bql_hold = collections.Counter()
    bql_holds = collections.Counter()
    bql_wait = collections.Counter()
    bql_util = []
    el = []
    for l in open(d + '/qemu-stderr.txt', errors='replace'):
        m = re.match(r'LAT clocks mono (\d+) boottime (\d+)', l)
        if m:
            offset = (int(m.group(2)) - int(m.group(1))) / 1e9
            continue
        m = re.match(r'LAT shown (\d+) n=\d+ diff=(\d+) of=(\d+)', l)
        if m:
            frames.append((int(m.group(1)) / 1e9, int(m.group(2)) / int(m.group(3))))
            continue
        m = re.match(r'bql t=(\d+)s held (\d+) ms \(([\d.]+)%', l)
        if m:
            bql_util.append((int(m.group(1)), float(m.group(3))))
            continue
        m = re.match(r'bql t=\d+s hold +([\d.]+) ms +(\d+)x +wait +([\d.]+) ms +(.*)', l)
        if m:
            bql_hold[m.group(4)] += float(m.group(1))
            bql_holds[m.group(4)] += int(m.group(2))
            continue
        m = re.match(r'bql t=\d+s wait +([\d.]+) ms +(\d+)x +hold +([\d.]+) ms +(.*)', l)
        if m:
            bql_wait[m.group(4)] += float(m.group(1))
            continue
        m = re.match(r'el-sample t=(\d+)s all: halted +([\d.]+)% el1 +([\d.]+)% el0-tcg +([\d.]+)% el0-nat +([\d.]+)%', l)
        if m:
            el.append(tuple(float(x) for x in m.groups()))
    if offset is not None:
        frames = [(t + offset, f) for t, f in frames]
    heat = []
    try:
        for l in open(d + '/heat.txt'):
            p = l.split()
            if len(p) == 5:
                heat.append((float(p[0]), int(p[1]) / 10, int(p[2]) / 1000, int(p[3]) / 1e6, int(p[4]) / 1e6))
    except FileNotFoundError:
        pass
    return marks, frames, offset, bql_hold, bql_holds, bql_wait, bql_util, el, heat


def changes(frames, t0, t1, thr=0.01):
    return [t for t, f in frames if t0 < t <= t1 and f >= thr]


def report(d):
    marks, frames, offset, hold, holds, wait, util, el, heat = load(d)
    print('==', d)
    s = marks.get('start')
    if 'lock-seen' in marks:
        print('lock screen seen %.0f s after the start (3 s screenshots)' % (marks['lock-seen'] - s))
    if offset is None:
        print('no LAT clocks line: frame times unknown')
    if 'home-done' in marks:    # test-ios26-bench.sh since 08:30: screenshots until each screen is there
        print('home screen with every icon %.0f s after the unlock flick (%.0f s after the start)' % (
            marks['home-done'] - marks['unlock'], marks['home-done'] - s))
        for a, b, label in (('calc', 'back', 'Calculator'), ('back', 'end', 'Home')):
            if a not in marks or b not in marks:
                continue
            big = changes(frames, marks[a], marks[b], 0.2)
            seen = marks.get('calc-open' if a == 'calc' else 'home2-done')
            print('%-10s first 20%% frame change %s, on screen (screenshots) %s' % (
                label, '%.2f s' % (big[0] - marks[a]) if big else 'none',
                '%.1f s' % (seen - marks[a]) if seen else 'not seen'))
    order = [k for k in ('unlock', 'calc', 'home', 'end') if k in marks]
    for a, b in zip(order, order[1:]):
        c = changes(frames, marks[a], marks[b])
        if not c:
            print('%-6s no frame change' % a)
            continue
        extra = ''
        if a == 'calc':
            big = changes(frames, marks[a], marks[b], 0.2)
            extra = ', 20%% change %.2f s' % (big[0] - marks[a]) if big else ', no 20% change'
        print('%-6s first change %.2f s, last %.2f s, %d changing frames%s' % (
            a, c[0] - marks[a], c[-1] - marks[a], len(c), extra))
    if 'lock-seen' in marks and offset is not None:
        boot = [t for t, f in frames if t <= marks['lock-seen'] and f >= 0.3]
        if boot:
            print('lock screen frame (last 30%% change before it was seen) at %.1f s' % (boot[-1] - s))
    if heat:
        run = [h for h in heat if h[0] >= s]
        if run:
            print('heat: battery %.1f -> %.1f C, hottest CPU max %.1f C, mean ceilings small %.2f / big %.2f GHz' % (
                run[0][1], run[-1][1], max(h[2] for h in run), sum(h[3] for h in run) / len(run),
                sum(h[4] for h in run) / len(run)))
    if el:
        steady = el[-4:]
        print('el (last %d samples) halted/el1/el0-tcg/el0-nat: %s' % (len(steady), ' | '.join(
            '%.0f/%.0f/%.0f/%.0f' % x[1:] for x in steady)))
    if util:
        print('bql held: ' + ' '.join('%ds %.0f%%' % u for u in util))
        tot = sum(hold.values())
        print('bql hold by site, run total %.0f ms (top 20 of each report, summed):' % tot)
        for k, v in hold.most_common(25):
            print('  %9.0f ms %5.1f%% %9dx  %s' % (v, 100 * v / tot, holds[k], k))
        print('bql wait by acquiring site:')
        for k, v in wait.most_common(12):
            print('  %9.0f ms  %s' % (v, k))


for d in sys.argv[1:]:
    report(d)
