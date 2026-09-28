#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static atomic_int input_count = 0;

int fsync(int fd) {
    int (*real_fsync)(int) = dlsym(RTLD_NEXT, "fsync");
    const char *mode = getenv("CQ_FIXTURE_STALL_MODE");
    const char *root = getenv("CQ_FIXTURE_STALL_ROOT");
    if (mode != NULL && root != NULL) {
        char link[64], path[4096], content[8192];
        snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
        ssize_t size = readlink(link, path, sizeof(path) - 1);
        if (size > 0) {
            path[size] = 0;
            if (strcmp(mode, "publication") == 0 && strcmp(path, root) == 0) {
                char release[4096];
                snprintf(release, sizeof(release), "%s/release", root);
                if (access(release, F_OK) != 0) { errno = EIO; return -1; }
            }
            int match = strcmp(mode, "ticket") == 0 && strstr(path, "/children/") != NULL && strstr(path, "/.upload-") != NULL;
            if (strcmp(mode, "checkout-index") == 0 && strstr(path, "/checkouts/") != NULL &&
                size >= 15 && strcmp(path + size - 15, "/index-prepared") == 0) {
                char started[4096];
                snprintf(started, sizeof(started), "%.*s/started.json", (int)(size - 15), path);
                match = access(started, F_OK) == 0;
            }
            if (strcmp(mode, "checkout-completed") == 0 && strstr(path, "/checkouts/") != NULL && strstr(path, "/.upload-") == NULL) {
                char completed[8192];
                snprintf(completed, sizeof(completed), "%s/completed.json", path);
                match = access(completed, F_OK) == 0;
            }
            if ((strcmp(mode, "attached-initial") == 0 || strcmp(mode, "attached-selection") == 0) && strstr(path, "/.upload-") != NULL) {
                int source = open(path, O_RDONLY);
                ssize_t read_size = source < 0 ? -1 : read(source, content, sizeof(content) - 1);
                if (source >= 0) close(source);
                if (read_size > 0) {
                    content[read_size] = 0;
                    match = strstr(content, strcmp(mode, "attached-initial") == 0 ? "\"ownership\":\"Attached\"" : "\"decision\":") != NULL;
                }
            }
            if (strcmp(mode, "input") == 0 && strstr(path, "/payload/") != NULL && size >= 6 && strcmp(path + size - 6, "/input") == 0)
                match = atomic_fetch_add(&input_count, 1) == 1;
            if (strcmp(mode, "integration-observation") == 0 && strstr(path, "/integrations/.integration-") != NULL) {
                int source = open(path, O_RDONLY);
                ssize_t read_size = source < 0 ? -1 : read(source, content, sizeof(content) - 1);
                if (source >= 0) close(source);
                if (read_size > 0) {
                    content[read_size] = 0;
                    match = strstr(content, "\"observation\":{\"Incorporated\"") != NULL;
                }
            }
            if (strcmp(mode, "exit") == 0 && strstr(path, "/journal/") != NULL) {
                int source = open(path, O_RDONLY);
                ssize_t read_size = source < 0 ? -1 : read(source, content, sizeof(content) - 1);
                if (source >= 0) close(source);
                if (read_size > 0) {
                    content[read_size] = 0;
                    match = strstr(content, "\"phase\":\"Settled\"") != NULL;
                }
            }
            if (match) {
                char marker[4096], release[4096];
                snprintf(marker, sizeof(marker), "%s/entered", root);
                snprintf(release, sizeof(release), "%s/release", root);
                int entered = open(marker, O_WRONLY | O_CREAT, 0600);
                if (entered >= 0) { if (write(entered, path, (size_t)size) < 0) abort(); close(entered); }
                while (access(release, F_OK) != 0) usleep(10000);
            }
        }
    }
    return real_fsync(fd);
}
