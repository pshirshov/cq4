#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/signalfd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

enum { POLL_MS = 20, BUFFER_BYTES = 8192, DRAIN_READS = 16, CHILD_BATCH = 4096 };
typedef struct {
    int pipe, file;
    uint64_t bytes;
    bool eof, failed, overflow;
} Output;
typedef struct {
    pid_t root;
    bool root_owned, started, stopping;
    int code, signal;
    int64_t stop_at;
    const char *reason;
} Job;

static int64_t now_ms(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) abort();
    return (int64_t)value.tv_sec * 1000 + value.tv_nsec / 1000000;
}

static int64_t number(const char *value, int64_t maximum) {
    char *end;
    errno = 0;
    long long parsed = strtoll(value, &end, 10);
    if (errno || *end || end == value || parsed <= 0 || parsed > maximum) return -1;
    return parsed;
}

static bool nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL);
    return flags >= 0 && fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0;
}

static void stop(Job *job, const char *reason, int64_t now) {
    if (!job->stopping) {
        job->stopping = true;
        job->stop_at = now;
        job->reason = reason;
        if (job->root_owned) {
            /* The unreaped child reserves this PID; never signal a saved PID after reaping. */
            if (getpgid(job->root) == job->root) kill(-job->root, SIGTERM);
            kill(job->root, SIGTERM);
        }
        printf("STOP %s\n", reason);
        fflush(stdout);
    } else if (strcmp(job->reason, "Exited") == 0 && strcmp(reason, "Exited") != 0) {
        job->reason = reason;
    }
}

static bool kill_children(void) {
    char location[128];
    snprintf(location, sizeof(location), "/proc/self/task/%ld/children", (long)getpid());
    FILE *children = fopen(location, "r");
    if (!children) return false;
    long pid;
    int count = 0;
    bool okay = true;
    /* No reaping occurs during this scan, so an exited direct child's PID cannot be reused. */
    while (count < CHILD_BATCH && fscanf(children, "%ld", &pid) == 1) {
        if (pid <= 1 || pid > INT_MAX) { okay = false; break; }
        if (kill((pid_t)pid, SIGKILL) != 0 && errno != ESRCH) okay = false;
        count++;
    }
    if (ferror(children)) okay = false;
    fclose(children);
    return okay;
}

static bool write_all(int fd, const char *data, size_t size) {
    while (size > 0) {
        ssize_t sent = write(fd, data, size);
        if (sent < 0 && errno == EINTR) continue;
        if (sent <= 0) return false;
        data += sent;
        size -= (size_t)sent;
    }
    return true;
}

static void drain(Output *output, uint64_t maximum) {
    char buffer[BUFFER_BYTES];
    for (int round = 0; !output->eof && round < DRAIN_READS; round++) {
        ssize_t count = read(output->pipe, buffer, sizeof(buffer));
        if (count == 0) { output->eof = true; break; }
        if (count < 0) {
            if (errno == EINTR) continue;
            if (errno != EAGAIN && errno != EWOULDBLOCK) { output->failed = true; output->eof = true; }
            break;
        }
        uint64_t remaining = output->bytes < maximum ? maximum - output->bytes : 0;
        size_t keep = (uint64_t)count < remaining ? (size_t)count : (size_t)remaining;
        if (!output->failed && keep > 0 && !write_all(output->file, buffer, keep)) output->failed = true;
        output->bytes += (uint64_t)count;
        if (output->bytes > maximum) output->overflow = true;
    }
}

static bool reap(Job *job, int64_t now, bool *failed) {
    for (int round = 0; round < CHILD_BATCH; round++) {
        siginfo_t info = {0};
        if (waitid(P_ALL, 0, &info, WEXITED | WNOHANG | WNOWAIT) != 0) {
            if (errno == ECHILD) return true;
            if (errno == EINTR) continue;
            *failed = true;
            return false;
        }
        if (!info.si_pid) return false;
        if (info.si_pid == job->root) stop(job, "Exited", now);
        int status;
        if (waitpid(info.si_pid, &status, 0) != info.si_pid) { *failed = true; return false; }
        if (info.si_pid == job->root) {
            job->root_owned = false;
            job->code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
            job->signal = WIFSIGNALED(status) ? WTERMSIG(status) : 0;
        }
    }
    return false;
}

static void child(int input, int out, int err, int acknowledgement, pid_t parent, char **command) {
    int failure;
    sigset_t empty;
    sigemptyset(&empty);
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent || setpgid(0, 0) != 0 ||
        sigprocmask(SIG_SETMASK, &empty, NULL) != 0 || signal(SIGPIPE, SIG_DFL) == SIG_ERR ||
        dup2(input, STDIN_FILENO) < 0 || dup2(out, STDOUT_FILENO) < 0 || dup2(err, STDERR_FILENO) < 0) {
        failure = errno == 0 ? ECHILD : errno;
    } else {
        execvp(command[0], command);
        failure = errno;
    }
    write_all(acknowledgement, (const char *)&failure, sizeof(failure));
    _exit(127);
}

int main(int argc, char **argv) {
    if (argc < 12 || strcmp(argv[10], "--") != 0) {
        fputs("Usage: cq-guardian startup-ms run-ms heartbeat-ms grace-ms kill-ms max-output-bytes input stdout stderr -- command [args]\n", stderr);
        return 2;
    }
    const int64_t max_duration = 24LL * 60 * 60 * 1000;
    int64_t startup = number(argv[1], max_duration), duration = number(argv[2], max_duration);
    int64_t heartbeat = number(argv[3], max_duration), grace = number(argv[4], max_duration), force = number(argv[5], max_duration);
    int64_t maximum = number(argv[6], 64LL * 1024 * 1024);
    if (startup < 0 || duration < 0 || heartbeat < 0 || grace < 0 || force < 0 || maximum < 0) return 2;
    sigset_t signals;
    sigemptyset(&signals);
    sigaddset(&signals, SIGTERM); sigaddset(&signals, SIGINT); sigaddset(&signals, SIGHUP);
    if (sigprocmask(SIG_BLOCK, &signals, NULL) != 0 || signal(SIGCHLD, SIG_DFL) == SIG_ERR ||
        signal(SIGPIPE, SIG_IGN) == SIG_ERR || prctl(PR_SET_CHILD_SUBREAPER, 1) != 0) return 2;
    int notifications = signalfd(-1, &signals, SFD_CLOEXEC | SFD_NONBLOCK);
    int out[2], err[2], ack[2];
    if (notifications < 0 || pipe2(out, O_CLOEXEC) != 0 || pipe2(err, O_CLOEXEC) != 0 || pipe2(ack, O_CLOEXEC) != 0) return 2;
    int input = open(argv[7], O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    struct stat input_stat;
    if (input < 0 || fstat(input, &input_stat) != 0 || !S_ISREG(input_stat.st_mode)) return 2;
    Output outputs[2] = {
        {out[0], open(argv[8], O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600), 0, false, false, false},
        {err[0], open(argv[9], O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600), 0, false, false, false}
    };
    if (input < 0 || outputs[0].file < 0 || outputs[1].file < 0 || !nonblocking(STDIN_FILENO) ||
        !nonblocking(out[0]) || !nonblocking(err[0]) || !nonblocking(ack[0])) return 2;
    int64_t began = now_ms(), beat = began, launched = began;
    pid_t parent = getpid();
    pid_t root = fork();
    if (root < 0) return 2;
    if (root == 0) child(input, out[1], err[1], ack[1], parent, &argv[11]);
    close(input); close(out[1]); close(err[1]); close(ack[1]);
    Job job = {root, true, false, false, -1, 0, 0, "Running"};
    bool failed = false, acknowledged = false, all_gone = false;
    struct pollfd watched[] = {{STDIN_FILENO, POLLIN, 0}, {out[0], POLLIN, 0}, {err[0], POLLIN, 0}, {ack[0], POLLIN, 0}, {notifications, POLLIN, 0}};
    while (true) {
        if (poll(watched, 5, POLL_MS) < 0 && errno != EINTR) failed = true;
        int64_t now = now_ms();
        if (watched[0].revents) {
            char control[256];
            ssize_t count = read(STDIN_FILENO, control, sizeof(control));
            if (count == 0) { stop(&job, "OwnerExited", now); watched[0].fd = -1; }
            else if (count < 0 && errno != EAGAIN && errno != EINTR) failed = true;
            for (ssize_t index = 0; index < count; index++) {
                if (control[index] == 'H') beat = now;
                else if (control[index] == 'C') stop(&job, "Cancelled", now);
                else stop(&job, "InvalidControl", now);
            }
        }
        if (watched[4].revents) {
            struct signalfd_siginfo notification;
            if (read(notifications, &notification, sizeof(notification)) == sizeof(notification)) stop(&job, "Signalled", now);
            else failed = true;
        }
        if (!acknowledged) {
            int failure;
            ssize_t count = read(ack[0], &failure, sizeof(failure));
            if (count == 0) {
                acknowledged = true; job.started = true; launched = now;
                printf("START %ld\n", (long)root); fflush(stdout);
            } else if (count > 0) {
                acknowledged = true;
                stop(&job, "LaunchFailed", now);
            } else if (errno != EAGAIN && errno != EINTR) failed = true;
            if (acknowledged) { close(ack[0]); watched[3].fd = -1; }
        }
        for (int stream = 0; stream < 2; stream++) {
            drain(&outputs[stream], (uint64_t)maximum);
            if (outputs[stream].eof) watched[stream + 1].fd = -1;
            if (outputs[stream].overflow) stop(&job, "OutputLimit", now);
            if (outputs[stream].failed) failed = true;
        }
        if (failed) stop(&job, "HostFailure", now);
        if (!acknowledged && now - began >= startup) stop(&job, "StartupDeadline", now);
        if (job.started && now - launched >= duration) stop(&job, "ExecutionDeadline", now);
        if (now - beat >= heartbeat) stop(&job, "HeartbeatLost", now);
        if (job.stopping && now - job.stop_at >= grace && !kill_children()) failed = true;
        all_gone = reap(&job, now, &failed);
        if (all_gone && outputs[0].eof && outputs[1].eof) break;
        if (job.stopping && now - job.stop_at >= grace + force) break;
    }
    for (int stream = 0; stream < 2; stream++) {
        if (fsync(outputs[stream].file) != 0 || close(outputs[stream].file) != 0) failed = true;
    }
    bool settled = all_gone && !job.root_owned && outputs[0].eof && outputs[1].eof;
    printf("EXIT %d %d %s %llu %llu %d %d\n", job.code, job.signal, job.reason,
        (unsigned long long)outputs[0].bytes, (unsigned long long)outputs[1].bytes, settled, failed);
    fflush(stdout);
    return settled && !failed ? 0 : 3;
}
