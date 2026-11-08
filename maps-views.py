"""maps-views.py MAPSFILE...: reims' views of guest RAM in a QEMU's /proc/PID/maps (vm.max_map_count 65,530 ends it).

A view is mapped over a reservation one run at a time (reims-vgpu-vulkan os/linux/vm.rs): each run is a
`rw-s ... /memfd:dram` mapping (or r--s), so a view is a stretch of such mappings that abut in address space.
Prints how many views, how many runs (VMAs) they take, the runs-per-view spread and the bytes they show, the
biggest views, and what else the process maps.
"""
import collections
import re
import sys

LINE = re.compile(r'^([0-9a-f]+)-([0-9a-f]+) (\S{4}) ([0-9a-f]+) \S+ \d+\s*(.*)$')


def main(path):
    rows = []
    for l in open(path, errors='replace'):
        m = LINE.match(l.strip())
        if m:
            rows.append((int(m.group(1), 16), int(m.group(2), 16), m.group(3), int(m.group(4), 16), m.group(5)))
    rows.sort()
    kinds = collections.Counter()
    views = []          # [first, end, runs, bytes, file pages contiguous runs]
    cur = None
    for first, end, perm, off, name in rows:
        is_dram = 'memfd:dram' in name and perm[3] == 's'
        kinds['dram view run' if is_dram else ('memfd ' + name.split('memfd:')[1].split()[0] if 'memfd:' in name
                                               else ('anon' if not name else name.split('/')[-1][:40]))] += 1
        if is_dram:
            if cur and cur[1] == first:
                cur[1] = end
                cur[2] += 1
                cur[3] += end - first
            else:
                cur = [first, end, 1, end - first]
                views.append(cur)
        else:
            cur = None
    total = len(rows)
    runs = sum(v[2] for v in views)
    print('%s: %d mappings, %d of them runs of %d views of guest RAM (%.1f MB shown)' % (
        path, total, runs, len(views), sum(v[3] for v in views) / 1e6))
    if views:
        spread = collections.Counter()
        for v in views:
            b = 1 if v[2] == 1 else 2 if v[2] <= 4 else 5 if v[2] <= 16 else 17 if v[2] <= 64 else 65 if v[2] <= 256 else 257
            spread[b] += 1
        names = {1: '1', 2: '2-4', 5: '5-16', 17: '17-64', 65: '65-256', 257: '>256'}
        print('  runs per view: ' + ', '.join('%s: %d views (%d runs)' % (
            names[b], spread[b], sum(v[2] for v in views if (1 if v[2] == 1 else 2 if v[2] <= 4 else 5 if v[2] <= 16
                                                             else 17 if v[2] <= 64 else 65 if v[2] <= 256 else 257) == b))
            for b in sorted(spread)))
        big = sorted(views, key=lambda v: -v[2])[:8]
        print('  most runs: ' + ', '.join('%d runs/%.1f MB' % (v[2], v[3] / 1e6) for v in big))
        sizes = collections.Counter(v[3] for v in views)
        print('  commonest view sizes: ' + ', '.join('%d KiB x%d' % (s // 1024, n) for s, n in sizes.most_common(6)))
    print('  by kind: ' + ', '.join('%s %d' % (k, n) for k, n in kinds.most_common(10)))


if __name__ == '__main__':
    for p in sys.argv[1:]:
        main(p)
