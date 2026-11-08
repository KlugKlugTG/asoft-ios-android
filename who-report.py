"""who-report.py QEMU_STDERR [FROM_S] [TO_S]: who kept iOS 26's vCPUs busy (QEMU with ASOFT_WHO=1 and ASOFT_EL_SAMPLER).

Sums the sampler's 30 s reports between FROM_S and TO_S (seconds since the first vCPU): the EL shares, the
address spaces (ASID in TTBR0_EL1; XNU's kernel threads run with 0) by EL0 and EL1 samples (1 kHz per vCPU), the
kernel PCs most sampled, and the syscalls from EL0 by address space and number (BSD > 0, Mach traps < 0).
"""
import collections
import re
import sys

# XNU arm64 syscall numbers (BSD) and Mach traps (negative), the common ones
NAMES = {
    3: 'read', 4: 'write', 5: 'open', 6: 'close', 20: 'getpid', 33: 'access', 74: 'mprotect', 116: 'gettimeofday',
    202: 'sysctl', 338: 'stat64', 339: 'fstat64', 340: 'lstat64', 344: 'getdirentries64', 360: 'bsdthread_create',
    366: 'bsdthread_register', 367: 'workq_open', 368: 'workq_kernreturn', 369: 'kevent', 374: 'kevent_qos',
    375: 'kevent_id', 396: 'read_nocancel', 397: 'write_nocancel', 398: 'open_nocancel', 399: 'close_nocancel',
    301: 'psynch_mutexwait', 302: 'psynch_mutexdrop', 303: 'psynch_cvbroad', 304: 'psynch_cvsignal', 305: 'psynch_cvwait',
    336: 'proc_info', 346: 'statfs64', 403: 'fsync_nocancel', 463: 'openat', 515: 'ulock_wait', 516: 'ulock_wake',
    544: 'ulock_wait2', 520: 'terminate_with_payload', 381: 'sandbox_ms', 380: '__mac_syscall', 384: '__mac_syscall',
    425: 'pread_nocancel', 153: 'pread', 154: 'pwrite', 197: 'mmap', 73: 'munmap', 75: 'madvise', 189: 'fstat',
    478: 'bsdthread_ctl', 220: 'getattrlist', 322: 'iopolicysys', 92: 'fcntl', 133: 'sendto',
    0: 'platform (x16 0x80000000)', -11: 'mach_vm_purgable_control', -22: 'mach_port_insert_member',
    -23: 'mach_port_extract_member', -25: 'mach_port_destruct', -43: 'mach_generate_activity_id',
    -50: 'thread_get_special_reply_port', -95: 'mk_timer_arm_leeway',
    -10: 'mach_vm_allocate', -12: 'mach_vm_deallocate', -14: 'mach_vm_protect', -15: 'mach_vm_map',
    -16: 'mach_port_allocate', -18: 'mach_port_deallocate', -19: 'mach_port_mod_refs', -24: 'mach_port_construct',
    -26: 'mach_reply_port', -27: 'thread_self', -28: 'task_self', -29: 'host_self', -31: 'mach_msg',
    -32: 'mach_msg_overwrite', -33: 'semaphore_signal', -36: 'semaphore_wait', -47: 'mach_msg2', -61: 'thread_switch',
    -89: 'mach_timebase_info', -90: 'mach_wait_until', -91: 'mk_timer_create', -93: 'mk_timer_arm', -94: 'mk_timer_cancel',
}


def main(path, lo=0.0, hi=1e18):
    el = []
    who0, who1 = collections.Counter(), collections.Counter()
    pcs = collections.Counter()
    sysc = collections.Counter()
    per_window = []
    for line in open(path, errors='replace'):
        m = re.match(r'el-sample t=(\d+)s (all|who|el1-pcs|syscalls)', line)
        if not m:
            continue
        t = int(m.group(1))
        if not lo <= t <= hi:
            continue
        kind = m.group(2)
        if kind == 'all':
            m2 = re.search(r'halted +([\d.]+)% el1 +([\d.]+)% el0-tcg +([\d.]+)% el0-nat +([\d.]+)%', line)
            if m2:
                el.append((t,) + tuple(float(x) for x in m2.groups()))
        elif kind == 'who':
            w = collections.Counter()
            for a, e0, e1 in re.findall(r' (\d+):(\d+)/(\d+)', line.split(':', 1)[1] if ':' in line else ''):
                who0[int(a)] += int(e0)
                who1[int(a)] += int(e1)
                w[int(a)] = int(e0) + int(e1)
            per_window.append((t, w.most_common(4)))
        elif kind == 'el1-pcs':
            for pc, n in re.findall(r' (0x[0-9a-f]+):(\d+)', line):
                pcs[pc] += int(n)
        else:
            for a, num, n in re.findall(r' (\d+)/(-?\d+):(\d+)', line):
                sysc[(int(a), int(num))] += int(n)
    if el:
        n = len(el)
        print('%d reports %d-%d s: halted %.1f%%, el1 %.1f%%, el0-tcg %.1f%%, el0-nat %.1f%%' % (
            n, el[0][0], el[-1][0], sum(e[1] for e in el) / n, sum(e[2] for e in el) / n,
            sum(e[3] for e in el) / n, sum(e[4] for e in el) / n))
    tot = sum(who0.values()) + sum(who1.values())
    if tot:
        print('address spaces (samples, share of the busy ones):')
        for a, _ in (who0 + who1).most_common(12):
            s = who0[a] + who1[a]
            print('  asid %5d  %6.1f%%  el0 %7d  el1 %7d%s' % (a, 100.0 * s / tot, who0[a], who1[a],
                                                             '  (kernel threads)' if a == 0 else ''))
    if pcs:
        print('kernel PCs most sampled: ' + ', '.join('%s:%d' % kv for kv in pcs.most_common(12)))
    if sysc:
        print('syscalls by address space:')
        for (a, num), c in sysc.most_common(20):
            print('  asid %5d  %-22s %8d' % (a, '%d %s' % (num, NAMES.get(num, '?')), c))
    if per_window:
        print('busiest per report: ' + '; '.join('t=%d %s' % (t, ' '.join('%d:%d' % kv for kv in w)) for t, w in per_window[::max(1, len(per_window) // 12)]))


if __name__ == '__main__':
    a = sys.argv[1:]
    main(a[0], float(a[1]) if len(a) > 1 else 0, float(a[2]) if len(a) > 2 else 1e18)
