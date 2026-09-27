#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static const char *fault(int fd) {
    const char *log = getenv("CQ_TEST_MERGE_LOG");
    const char *mode = getenv("CQ_TEST_MERGE_FAULT");
    if (!log || !mode) return "";
    char path[64], target[PATH_MAX];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    ssize_t size = readlink(path, target, sizeof(target) - 1);
    if (size < 0) return "";
    target[size] = 0;
    return strcmp(target, log) == 0 ? mode : "";
}

static void observed(void) {
    const char message[] = "Injected merge diagnostic failure\n";
    syscall(SYS_write, STDERR_FILENO, message, sizeof(message) - 1);
}

ssize_t write(int fd, const void *buffer, size_t count) {
    if (strcmp(fault(fd), "write") == 0) {
        observed();
        errno = EIO;
        return -1;
    }
    ssize_t (*original)(int, const void *, size_t) = dlsym(RTLD_NEXT, "write");
    return original(fd, buffer, count);
}

int fsync(int fd) {
    const char *mode = fault(fd);
    if (strcmp(mode, "fsync") == 0) {
        observed();
        errno = EIO;
        return -1;
    }
    if (strcmp(mode, "missing") == 0 || strcmp(mode, "partial") == 0 || strcmp(mode, "extra") == 0) {
        const char *status = getenv("CQ_TEST_MERGE_STATUS");
        if (!status) abort();
        if (strcmp(mode, "missing") == 0) {
            if (unlink(status) != 0) abort();
        } else {
            int output = open(status, O_WRONLY | O_TRUNC | O_NOFOLLOW);
            const char *content = strcmp(mode, "partial") == 0 ? "0" : "0\n1";
            if (output < 0 || write(output, content, strlen(content)) != (ssize_t)strlen(content) || close(output) != 0) abort();
        }
        observed();
    }
    int (*original)(int) = dlsym(RTLD_NEXT, "fsync");
    return original(fd);
}
