# touch-report.py QEMU_STDERR...: from ASOFT_LAT's "LAT in" (an input-send-event QEMU handled: the pending report) and
# "LAT report" (a report the guest read) lines: how long each finger edge (down/up) waited for the guest to read it,
# the guest's gaps between reads while a finger is down, and edges the guest never saw (the next edge came first).
import sys, statistics

def pct(v, p):
    v = sorted(v)
    return v[min(len(v) - 1, int(len(v) * p))] if v else 0

for path in sys.argv[1:]:
    ins, reps = [], []
    for l in open(path, errors='replace'):
        p = l.split()
        if len(p) >= 6 and p[0] == 'LAT' and p[1] in ('in', 'report'):
            t = int(p[2]) / 1e6
            b = int(p[3].split('=')[1])
            (ins if p[1] == 'in' else reps).append((t, b))
    edges, lost, waits = [], 0, []
    last = 0
    for t, b in ins:
        if b != last:
            edges.append((t, b))
            last = b
    ri = 0
    for k, (t, b) in enumerate(edges):
        nxt = edges[k + 1][0] if k + 1 < len(edges) else float('inf')
        while ri < len(reps) and reps[ri][0] < t:
            ri += 1
        j = ri
        while j < len(reps) and reps[j][0] < nxt and reps[j][1] != b:
            j += 1
        if j < len(reps) and reps[j][0] < nxt:
            waits.append(reps[j][0] - t)
        else:
            lost += 1
    gaps = [reps[i][0] - reps[i - 1][0] for i in range(1, len(reps)) if reps[i - 1][1] == 1]
    print('%s: %d inputs, %d guest reads, %d edges; edge wait ms median %.1f p90 %.1f max %.1f; edges never read %d; '
          'gaps between reads while down: median %.1f p90 %.1f max %.1f ms' % (
              path, len(ins), len(reps), len(edges), statistics.median(waits) if waits else 0, pct(waits, .9),
              max(waits) if waits else 0, lost, statistics.median(gaps) if gaps else 0, pct(gaps, .9),
              max(gaps) if gaps else 0))
