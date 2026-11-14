/*
 * kabuk-open: the Phone tab's link to the Kabuk app, one program under several names. Shipped as libkabuk-open.so
 * (nativeLibraryDir: the busybox shell links it from there; Termux's shell gets links in files/kabuk/bin).
 * A program in the Phone tab cannot start an Android activity or reach the app's service itself, so it hands the
 * request to the app through a directory Kabuk watches ($KABUK_OPEN_DIR, files/open), written via a temp name +
 * rename so the app never reads half a file:
 *
 *   xdg-open, open, termux-open-url, kabuk-open <http(s) URL>
 *       -> <time>-<pid>.url (the URL); Kabuk opens it in the browser. No answer is awaited (rclone authorize
 *          runs it and goes on waiting for the redirect). xdg-open/open with anything else work like termux-open.
 *   am, termux-am, termux-open, termux-wake-lock, termux-wake-unlock, termux-reload-settings [args]
 *       -> <time>-<pid>.req: "kabuk-req 1", the cwd, the command name and its arguments, each NUL-terminated.
 *          Kabuk (term/KabukCommands.kt) answers with <time>-<pid>.res: the exit code on the first line, then the
 *          text to print (stdout for 0, else stderr). No answer within 10 s: exit 1 with a message.
 *
 * Built by scripts/build-tools.sh. Apache-2.0, like Kabuk.
 */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define VERSION "2"
#define MAX_URL 8192
#define MAX_REQ 65536
#define MAX_RES 16384
#define WAIT_MS 10000

static const char *URL_NAMES[] = {"xdg-open", "open", "termux-open-url", "kabuk-open", NULL};
static const char *REQ_NAMES[] = {"am", "termux-am", "termux-open", "termux-wake-lock", "termux-wake-unlock",
                                  "termux-reload-settings", NULL};

static int in(const char *name, const char **list) {
    for (; *list; list++) if (strcmp(name, *list) == 0) return 1;
    return 0;
}

static int is_http(const char *s) { return strncasecmp(s, "http://", 7) == 0 || strncasecmp(s, "https://", 8) == 0; }

static const char *open_dir(const char *name) {
    const char *dir = getenv("KABUK_OPEN_DIR");
    if (!dir || !*dir) { fprintf(stderr, "%s: KABUK_OPEN_DIR is not set (run me from Kabuk's Phone tab)\n", name); return NULL; }
    return dir;
}

/* writes [len] bytes to dir/.<base>.tmp, then renames it to dir/<base><ext> */
static int drop(const char *name, const char *dir, const char *base, const char *ext, const char *data, size_t len) {
    char tmp[4096], final[4096];
    snprintf(tmp, sizeof tmp, "%s/.%s.tmp", dir, base);
    snprintf(final, sizeof final, "%s/%s%s", dir, base, ext);
    int fd = open(tmp, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd < 0) { fprintf(stderr, "%s: %s: %s\n", name, dir, strerror(errno)); return 1; }
    ssize_t w = write(fd, data, len);
    if (close(fd) != 0 || w != (ssize_t)len || rename(tmp, final) != 0) {
        fprintf(stderr, "%s: cannot hand the request to Kabuk: %s\n", name, strerror(errno));
        unlink(tmp);
        return 1;
    }
    return 0;
}

static void make_base(char *base, size_t n) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    long long ms = (long long)tv.tv_sec * 1000 + tv.tv_usec / 1000;
    snprintf(base, n, "%lld-%d", ms, (int)getpid());
}

static int open_url(const char *name, const char *url) {
    size_t len = strlen(url);
    if (len > MAX_URL) { fprintf(stderr, "%s: URL too long\n", name); return 1; }
    for (size_t i = 0; i < len; i++) {
        if ((unsigned char)url[i] < 0x20 || url[i] == 0x7f) { fprintf(stderr, "%s: control character in URL\n", name); return 1; }
    }
    const char *dir = open_dir(name);
    if (!dir) return 3;
    char base[64];
    make_base(base, sizeof base);
    return drop(name, dir, base, ".url", url, len);
}

/* the request; then waits for Kabuk's answer and prints it */
static int request(const char *name, int argc, char **argv) {
    const char *dir = open_dir(name);
    if (!dir) return 3;
    static char buf[MAX_REQ];
    size_t len = 0;
    char cwd[4096];
    if (!getcwd(cwd, sizeof cwd)) strcpy(cwd, "/");
#define PUT(s) do { size_t l_ = strlen(s) + 1; \
        if (len + l_ > sizeof buf) { fprintf(stderr, "%s: arguments too long\n", name); return 1; } \
        memcpy(buf + len, s, l_); len += l_; } while (0)
    PUT("kabuk-req 1");
    PUT(cwd);
    PUT(name);
    for (int i = 1; i < argc; i++) PUT(argv[i]);
#undef PUT
    char base[64], res[4096], req[4096];
    make_base(base, sizeof base);
    if (drop(name, dir, base, ".req", buf, len) != 0) return 1;
    snprintf(res, sizeof res, "%s/%s.res", dir, base);
    snprintf(req, sizeof req, "%s/%s.req", dir, base);
    struct timespec step = {0, 25 * 1000 * 1000};
    for (int waited = 0; waited < WAIT_MS; waited += 25) {
        int fd = open(res, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            static char out[MAX_RES + 1];
            ssize_t n = 0, r;
            while (n < MAX_RES && (r = read(fd, out + n, MAX_RES - n)) > 0) n += r;
            close(fd);
            unlink(res);
            out[n < 0 ? 0 : n] = 0;
            int code = atoi(out);
            char *text = strchr(out, '\n');
            if (text && text[1]) fputs(text + 1, code == 0 ? stdout : stderr);
            return code;
        }
        nanosleep(&step, NULL);
    }
    unlink(req);
    fprintf(stderr, "%s: Kabuk did not answer (is the app running?)\n", name);
    return 1;
}

int main(int argc, char **argv) {
    const char *name = strrchr(argv[0], '/') ? strrchr(argv[0], '/') + 1 : argv[0];
    if (strncmp(name, "lib", 3) == 0) name = "kabuk-open"; /* run as libkabuk-open.so (the self-test) */
    if (argc == 2 && strcmp(argv[1], "--version") == 0 && !in(name, REQ_NAMES)) { printf("kabuk-open " VERSION "\n"); return 0; }
    if (in(name, REQ_NAMES)) return request(name, argc, argv);
    if (!in(name, URL_NAMES)) { fprintf(stderr, "kabuk-open: unknown name %s\n", name); return 2; }
    int url_only = strcmp(name, "termux-open-url") == 0 || strcmp(name, "kabuk-open") == 0;
    /* termux-open-url <url> [app]: Termux's second argument (a package to open it with) is ignored */
    if (argc == 2 && is_http(argv[1])) return open_url(name, argv[1]);
    if (argc == 3 && url_only && is_http(argv[1])) return open_url(name, argv[1]);
    if (!url_only && argc >= 2) return request("termux-open", argc, argv); /* xdg-open <file> */
    if (argc == 2 && argv[1][0] != '-') {
        fprintf(stderr, "%s: only http:// and https:// URLs can be opened\n", name);
        return 1;
    }
    fprintf(stderr, "usage: %s <http(s) URL>\nOpens the URL in the phone's browser (through Kabuk).\n", name);
    return 2;
}
