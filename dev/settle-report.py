"""settle-report.py OUTDIR: what test-ios26-settle.sh measured, per input and round.

marks.txt holds the phone's boot-time clock just before each input; reims' LAT frame stamps (CLOCK_MONOTONIC) are
moved onto it with QEMU's 'LAT clocks mono .. boottime ..' line. A frame "changes" when at least 1% of its sampled
pixels differ from the frame before. For each input: the first change after it (first reaction), the last change
before the next input (settled), and how many frames changed in between (frames per second over that span).
Then the EL shares and the BQL from QEMU's samplers, at ~5 min and after the idle time, and maps.txt's peak.
"""
import re
import sys


def main(d):
    marks = []
    for line in open(d + '/marks.txt'):
        k, v = line.split()
        marks.append((k, float(v)))
    frames, offset = [], None
    el = []
    for line in open(d + '/qemu-stderr.txt', errors='replace'):
        m = re.match(r'LAT clocks mono (\d+) boottime (\d+)', line)
        if m:
            offset = (int(m.group(2)) - int(m.group(1))) / 1e9
            continue
        m = re.match(r'LAT shown (\d+) n=\d+ diff=(\d+) of=(\d+)', line)
        if m:
            frames.append((int(m.group(1)) / 1e9, int(m.group(2)) / max(1, int(m.group(3)))))
            continue
        m = re.match(r'el-sample t=(\d+)s all: halted +([\d.]+)% el1 +([\d.]+)% el0-tcg +([\d.]+)% el0-nat +([\d.]+)%', line)
        if m:
            el.append(tuple(float(x) for x in m.groups()))
    if offset is None:
        print('no LAT clocks line: was ASOFT_LAT=1 set?')
        return
    frames = [(t + offset, f) for t, f in frames]
    changes = [t for t, f in frames if f >= 0.01]
    # small changes (iOS's volume HUD is ~0.3% of the screen); the lock and home screens tick by ~0.2% too
    small = [t for t, f in frames if f >= 0.0025]
    start = dict(marks).get('start', marks[0][1])
    rows = []
    for i, (name, t) in enumerate(marks):
        if '-' not in name:
            continue
        nxt = marks[i + 1][1] if i + 1 < len(marks) else t + 15
        win = [c for c in changes if t < c < nxt]
        swin = [c for c in small if t < c < nxt]
        first = win[0] - t if win else None
        last = win[-1] - t if win else None
        sfirst = swin[0] - t if swin else None
        fps = len(win) / (win[-1] - win[0]) if len(win) > 1 and win[-1] > win[0] else 0
        rows.append((name, t - start, first, last, len(win), fps, sfirst))
    print('%-14s %8s %9s %9s %7s %6s %10s' % ('input', 't (s)', 'first (s)', 'settle', 'frames', 'fps', 'first .25%'))
    for name, t, first, last, n, fps, sfirst in rows:
        f = '%9.2f' % first if first is not None else '        -'
        s = '%9.2f' % last if last is not None else '        -'
        sf = '%10.2f' % sfirst if sfirst is not None else '         -'
        print('%-14s %8.0f %s %s %7d %6.1f %s' % (name, t, f, s, n, fps, sf))
    # the same input over the rounds: a (~5 min) against b (after the idle time)
    print()
    kinds = sorted({r[0].split('-', 1)[1] for r in rows})
    for k in kinds:
        for g in 'abr':
            sel = [r for r in rows if r[0].startswith(g) and r[0].endswith('-' + k) and r[2] is not None]
            ssel = [r for r in rows if r[0].startswith(g) and r[0].endswith('-' + k) and r[6] is not None]
            if sel or ssel:
                print('%-10s %s: first %s, settle %s (mean of %d); first small change %s (of %d)' % (
                    k, g, '%5.2f s' % (sum(r[2] for r in sel) / len(sel)) if sel else '   - ',
                    '%5.2f s' % (sum(r[3] for r in sel) / len(sel)) if sel else '   - ', len(sel),
                    '%5.2f s' % (sum(r[6] for r in ssel) / len(ssel)) if ssel else '   - ', len(ssel)))
    if el:
        print()
        for t, halted, el1, tcg, nat in el[::max(1, len(el) // 16)]:
            print('t=%5.0f s  halted %5.1f%%  el1 %5.1f%%  el0-tcg %5.1f%%  el0-nat %5.1f%%' % (t, halted, el1, tcg, nat))
    try:
        rows = [l.split() for l in open(d + '/maps.txt') if l.strip()]
        peak = max(rows, key=lambda r: int(r[1]))
        print('\nmaps: %d samples, peak %s mappings (%s runs of views of guest RAM) at epoch %s, last %s' % (
            len(rows), peak[1], peak[2], peak[0], ' '.join(rows[-1][1:3])))
    except (OSError, ValueError):
        pass


if __name__ == '__main__':
    for d in sys.argv[1:]:
        print('==', d)
        main(d)
