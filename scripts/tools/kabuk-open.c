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
 *   every other name (am, termux-am, termux-open, termux-wake-lock, termux-clipboard-get, termux-toast, ...)
 *       -> <time>-<pid>.req: "kabuk-req 2", the cwd, the command name and its arguments, each NUL-terminated.
 *          Kabuk (term/KabukCommands.kt, term/TermuxApi.kt) answers with <time>-<pid>.res: the exit code on the
 *          first line, then the text to print (stdout for 0, else stderr). No answer within 10 s: exit 1.
 *          "kabuk-req 2" (version 4) says the program knows keep-alives: while a command runs longer (speech,
 *          a big media scan) Kabuk creates <time>-<pid>.wait every few seconds; the program removes it and starts
 *          its 10 s again. A separate file, so it can never race with the answer. Ctrl-C ends the wait as usual.
 *          ("kabuk-req 1", versions 2-3, gets no keep-alives.)
 *          A command that reads its input (termux-clipboard-set, termux-toast ... without a text argument) gets
 *          "stdin" (always read, like cat) or "stdin-if-piped" (only when stdin is not a terminal) as the first
 *          line instead: the program then hands its stdin over as <time>-<pid>.in (max 1 MiB) and waits for the
 *          real answer the same way.
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

#define VERSION "4"
#define MAX_URL 8192
#define MAX_REQ 65536
#define MAX_RES (1 << 20)
#define MAX_STDIN (1 << 20)
#define WAIT_MS 10000

static const char *URL_NAMES[] = {"xdg-open", "open", "termux-open-url", "kabuk-open", NULL};

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

static int await_answer(const char *name, const char *dir, const char *base);

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
    PUT("kabuk-req 2");
    PUT(cwd);
    PUT(name);
    for (int i = 1; i < argc; i++) PUT(argv[i]);
#undef PUT
    char base[64];
    make_base(base, sizeof base);
    if (drop(name, dir, base, ".req", buf, len) != 0) return 1;
    return await_answer(name, dir, base);
}

/* reads all of stdin (at most MAX_STDIN bytes) into a malloc'd buffer; -1 when it is longer */
static ssize_t read_stdin(char **out) {
    size_t cap = 65536, len = 0;
    char *b = malloc(cap);
    if (!b) return -1;
    for (;;) {
        if (len == cap) {
            if (cap >= MAX_STDIN) { free(b); return -1; }
            cap *= 2;
            char *nb = realloc(b, cap);
            if (!nb) { free(b); return -1; }
            b = nb;
        }
        ssize_t r = read(0, b + len, cap - len);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) break;
        len += r;
        if (len > MAX_STDIN) { free(b); return -1; }
    }
    *out = b;
    return (ssize_t)len;
}

/* waits for <base>.res and prints it; hands stdin over first when Kabuk asks for it */
static int await_answer(const char *name, const char *dir, const char *base) {
    char res[4096], req[4096], alive[4096];
    snprintf(res, sizeof res, "%s/%s.res", dir, base);
    snprintf(req, sizeof req, "%s/%s.req", dir, base);
    snprintf(alive, sizeof alive, "%s/%s.wait", dir, base);
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
            if (strncmp(out, "stdin", 5) == 0) {
                int if_piped = strncmp(out, "stdin-if-piped", 14) == 0;
                char *input = NULL;
                ssize_t len = 0;
                if (!(if_piped && isatty(0))) {
                    len = read_stdin(&input);
                    if (len < 0) { fprintf(stderr, "%s: input too long (at most 1 MiB)\n", name); return 1; }
                }
                int failed = drop(name, dir, base, ".in", input ? input : "", (size_t)len);
                free(input);
                if (failed) return 1;
                waited = 0;
                continue;
            }
            int code = atoi(out);
            char *text = strchr(out, '\n');
            if (text && text[1]) fputs(text + 1, code == 0 ? stdout : stderr);
            return code;
        }
        /* Kabuk is still working on it (keep-alive): start the 10 s again */
        if (unlink(alive) == 0) waited = 0;
        nanosleep(&step, NULL);
    }
    unlink(req);
    fprintf(stderr, "%s: Kabuk did not answer (is the app running?)\n", name);
    return 1;
}

int main(int argc, char **argv) {
    const char *name = strrchr(argv[0], '/') ? strrchr(argv[0], '/') + 1 : argv[0];
    if (strncmp(name, "lib", 3) == 0) name = "kabuk-open"; /* run as libkabuk-open.so (the self-test) */
    /* every other name is a request; Kabuk answers "unknown command" for names it doesn't know */
    if (!in(name, URL_NAMES)) return request(name, argc, argv);
    if (argc == 2 && strcmp(argv[1], "--version") == 0) { printf("kabuk-open " VERSION "\n"); return 0; }
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
