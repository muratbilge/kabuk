/*
 * kabuk-open: the Phone tab's xdg-open / open / termux-open-url. Shipped as libkabuk-open.so (nativeLibraryDir is the
 * only place a targetSdk-35 app may exec its own files; a #!/system/bin/sh script in filesDir is refused, see
 * docs/LOCAL-TOOLS.md). It cannot start an Android activity itself, so it hands the URL to the app: the URL is
 * written to $KABUK_OPEN_DIR/<time>-<pid>.url (via a temp name + rename, so the app never reads half a file) and
 * Kabuk, which watches that directory, opens it in the browser. Only http(s) URLs; Kabuk checks again.
 * Built by scripts/build-tools.sh. Apache-2.0, like Kabuk.
 */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/time.h>
#include <unistd.h>

#define VERSION "1"
#define MAX_URL 8192

static int usage(const char *name) {
    fprintf(stderr, "usage: %s <http(s) URL>\nOpens the URL in the phone's browser (through Kabuk).\n", name);
    return 2;
}

int main(int argc, char **argv) {
    const char *name = strrchr(argv[0], '/') ? strrchr(argv[0], '/') + 1 : argv[0];
    if (argc == 2 && strcmp(argv[1], "--version") == 0) { printf("kabuk-open " VERSION "\n"); return 0; }
    if (argc != 2 || argv[1][0] == '-') return usage(name);
    const char *url = argv[1];
    size_t len = strlen(url);
    if (strncasecmp(url, "http://", 7) != 0 && strncasecmp(url, "https://", 8) != 0) {
        fprintf(stderr, "%s: only http:// and https:// URLs can be opened\n", name);
        return 1;
    }
    if (len > MAX_URL) { fprintf(stderr, "%s: URL too long\n", name); return 1; }
    for (size_t i = 0; i < len; i++) {
        if ((unsigned char)url[i] < 0x20 || url[i] == 0x7f) { fprintf(stderr, "%s: control character in URL\n", name); return 1; }
    }
    const char *dir = getenv("KABUK_OPEN_DIR");
    if (!dir || !*dir) { fprintf(stderr, "%s: KABUK_OPEN_DIR is not set (run me from Kabuk's Phone tab)\n", name); return 3; }

    struct timeval tv;
    gettimeofday(&tv, NULL);
    char tmp[4096], final[4096];
    long long ms = (long long)tv.tv_sec * 1000 + tv.tv_usec / 1000;
    snprintf(tmp, sizeof tmp, "%s/.%lld-%d.tmp", dir, ms, (int)getpid());
    snprintf(final, sizeof final, "%s/%lld-%d.url", dir, ms, (int)getpid());
    int fd = open(tmp, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd < 0) { fprintf(stderr, "%s: %s: %s\n", name, dir, strerror(errno)); return 1; }
    ssize_t w = write(fd, url, len);
    if (close(fd) != 0 || w != (ssize_t)len || rename(tmp, final) != 0) {
        fprintf(stderr, "%s: cannot hand the URL to Kabuk: %s\n", name, strerror(errno));
        unlink(tmp);
        return 1;
    }
    return 0;
}
