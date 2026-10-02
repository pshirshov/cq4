#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Fails every listing of the directory CQ_TEST_READDIR_FAULT names with EIO, as a failing disk or network filesystem does.
 * The directory still opens and its entries can still be reached by name; other directories are untouched. */
static int faulty(DIR *directory) {
    const char *target = getenv("CQ_TEST_READDIR_FAULT");
    if (!target || !directory) return 0;
    char link[64], path[PATH_MAX];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", dirfd(directory));
    ssize_t size = readlink(link, path, sizeof(path) - 1);
    if (size < 0) return 0;
    path[size] = 0;
    return strcmp(path, target) == 0;
}

struct dirent *readdir(DIR *directory) {
    if (faulty(directory)) {
        errno = EIO;
        return NULL;
    }
    struct dirent *(*original)(DIR *) = dlsym(RTLD_NEXT, "readdir");
    return original(directory);
}

struct dirent64 *readdir64(DIR *directory) {
    if (faulty(directory)) {
        errno = EIO;
        return NULL;
    }
    struct dirent64 *(*original)(DIR *) = dlsym(RTLD_NEXT, "readdir64");
    return original(directory);
}
