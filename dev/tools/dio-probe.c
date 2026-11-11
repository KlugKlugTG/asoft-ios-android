/*
 * dio-probe: what O_DIRECT accepts on this phone's /data (f2fs + inline encryption), and what
 * QEMU's file-posix alignment probe (raw_probe_alignment) decides from it.
 *
 * Usage (as the app's uid, in its files dir): dio-probe SCRATCH [EXISTING_FILE_READ_ONLY]
 * SCRATCH is created (2 MiB), written and deleted; EXISTING is only read.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef STATX_DIOALIGN
#define STATX_DIOALIGN 0x2000U
#endif

static char *buf;

static const char *res(ssize_t r, int e)
{
    static char s[64];
    if (r >= 0) {
        snprintf(s, sizeof(s), "ok %zd", r);
    } else {
        snprintf(s, sizeof(s), "%s", e == EINVAL ? "EINVAL" : e == EIO ? "EIO" : strerror(e));
    }
    return s;
}

/* QEMU block/file-posix.c raw_is_io_aligned(): any error but EINVAL counts as aligned */
static int qemu_is_io_aligned(int fd, void *b, size_t len)
{
    ssize_t r = pread(fd, b, len, 0);
    if (r >= 0) {
        return 1;
    }
    return errno != EINVAL;
}

static void qemu_probe(int fd, const char *what)
{
    static const size_t alignments[] = {1, 512, 1024, 2048, 4096};
    size_t max_align = 4096, req = 0, mem = 0;
    if (sysconf(_SC_PAGESIZE) > (long)max_align) {
        max_align = sysconf(_SC_PAGESIZE);
    }
    for (unsigned i = 0; i < 5; i++) {
        if (qemu_is_io_aligned(fd, buf, alignments[i])) {
            req = alignments[i] != 1 ? alignments[i] : max_align;
            break;
        }
    }
    for (unsigned i = 0; i < 5; i++) {
        if (qemu_is_io_aligned(fd, buf + alignments[i], max_align)) {
            mem = alignments[i] != 1 ? alignments[i] : req;
            break;
        }
    }
    printf("%s: QEMU's probe decides request_alignment %zu, buf_align %zu (max_align %zu)\n", what, req, mem, max_align);
}

static void dioalign(const char *path)
{
    struct statx sx;
    memset(&sx, 0, sizeof(sx));
    if (statx(AT_FDCWD, path, 0, STATX_DIOALIGN, &sx) < 0) {
        printf("%s: statx: %s\n", path, strerror(errno));
        return;
    }
    printf("%s: statx DIOALIGN %s: dio_mem_align %u, dio_offset_align %u, blksize %u\n", path,
           (sx.stx_mask & STATX_DIOALIGN) ? "reported" : "NOT reported", sx.stx_dio_mem_align,
           sx.stx_dio_offset_align, sx.stx_blksize);
}

static void matrix(int fd, int writes)
{
    static const size_t memoff[] = {0, 512, 1024, 2048, 4096};
    static const off_t fileoff[] = {0, 512, 4096};
    static const size_t lens[] = {512, 4096, 16384};
    for (unsigned a = 0; a < 3; a++) {
        for (unsigned b = 0; b < 3; b++) {
            printf("  %s file+%-5lld len %-6zu:", writes ? "pwrite" : "pread ", (long long)fileoff[a], lens[b]);
            for (unsigned m = 0; m < 5; m++) {
                ssize_t r;
                errno = 0;
                if (writes) {
                    memset(buf + memoff[m], 0x5a, lens[b]);
                    r = pwrite(fd, buf + memoff[m], lens[b], fileoff[a] + 65536);
                } else {
                    r = pread(fd, buf + memoff[m], lens[b], fileoff[a]);
                }
                printf("  mem+%-4zu %-8s", memoff[m], res(r, errno));
            }
            printf("\n");
        }
    }
}

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: %s SCRATCH [EXISTING]\n", argv[0]);
        return 2;
    }
    if (posix_memalign((void **)&buf, 65536, 1 << 20)) {
        return 1;
    }
    const char *scratch = argv[1];
    int fd = open(scratch, O_RDWR | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) {
        perror("create scratch");
        return 1;
    }
    memset(buf, 0xa5, 1 << 20);
    for (int i = 0; i < 2; i++) {
        if (write(fd, buf, 1 << 20) != 1 << 20) {
            perror("write scratch");
            return 1;
        }
    }
    fsync(fd);
    close(fd);
    dioalign(scratch);
    fd = open(scratch, O_RDWR | O_DIRECT);
    if (fd < 0) {
        perror("open O_DIRECT");
        return 1;
    }
    qemu_probe(fd, scratch);
    matrix(fd, 0);
    matrix(fd, 1);
    close(fd);
    unlink(scratch);
    if (argc > 2) {
        dioalign(argv[2]);
        fd = open(argv[2], O_RDONLY | O_DIRECT);
        if (fd < 0) {
            perror("open existing O_DIRECT");
            return 1;
        }
        qemu_probe(fd, argv[2]);
        matrix(fd, 0);
        close(fd);
    }
    return 0;
}
