/*
 * What the setup from Apple needs natively (Unpack.java): LZFSE decoding (Apple's reference decoder,
 * lzfse/, BSD) for AEA segments and IM4P payloads, and freeing a file's blocks behind a decrypt
 * (fallocate PUNCH_HOLE), so an encrypted image and its decrypted copy are not both whole on the phone.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/falloc.h>
#include <stdlib.h>
#include <unistd.h>

#include "lzfse/lzfse.h"

/* the decoded size, or -1 (bad input, or more than @dcap) */
JNIEXPORT jlong JNICALL Java_com_asoft_ios_Unpack_lzfse(JNIEnv *env, jclass c, jobject src, jint off, jint len, jobject dst,
                                                        jint dcap)
{
    (void)c;
    unsigned char *s = (*env)->GetDirectBufferAddress(env, src);
    unsigned char *d = (*env)->GetDirectBufferAddress(env, dst);
    if (!s || !d) {
        return -1;
    }
    /* the scratch on the heap: the decoder's state is ~50 KB, per thread */
    void *scratch = malloc(lzfse_decode_scratch_size());
    if (!scratch) {
        return -1;
    }
    /* one byte more than the room: a stream that fills it exactly is told from one cut short */
    size_t n = lzfse_decode_buffer(d, (size_t)dcap, s + off, (size_t)len, scratch);
    free(scratch);
    return n >= (size_t)dcap ? -1 : (jlong)n;
}

/* frees [off, off + len) of @path's blocks, its size kept; 0, or the errno */
JNIEXPORT jint JNICALL Java_com_asoft_ios_Unpack_punchHole(JNIEnv *env, jclass c, jstring jpath, jlong off, jlong len)
{
    (void)c;
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    int fd = open(p, O_WRONLY | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, jpath, p);
    if (fd < 0) {
        return errno;
    }
    int r = fallocate(fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE, off, len) ? errno : 0;
    close(fd);
    return r;
}
