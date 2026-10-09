/*
 * vk640_uhid.c - VEIKK VK640 -> UHID "Pen" (INPUT_PROP_DIRECT) koprusu, surum 3
 *
 * v3: imlec konumunu 127.0.0.1:47650 UDP portuna yayinlar (overlay APK cizer).
 *     Loopback oldugu icin Wi-Fi/internet gerekmez. Format: "u v inrange tip\n"
 *
 * (asagida v2 aciklamasi)
 *
 * v1'e gore degisenler:
 *   - Ekran donusunu (dikey/yatay) kendisi izler: arka planda `dumpsys input`
 *     icindeki "Viewport INTERNAL ... orientation=N" degerini okur. Elle echo gerekmez.
 *   - Donus yonu ters cikarsa -i, tablet ekrana gore dondurulmus durursa -H var.
 *   - Varsayilan olarak kirpma YOK (-a verilmezse tum tablet alani tum ekrana yayilir).
 *   - -v ile her kalem cikisinda ham ve cikis koordinat araliklari yazilir
 *     (olu bolge teshisi icin).
 *
 * Derleme (Termux):  clang -O2 -o vk640_uhid vk640_uhid_v2.c -lm
 * Kullanim:  vk640_uhid [-d /dev/input/eventN] [-r auto|0|90|180|270] [-i] [-H 0|90|180|270]
 *                       [-p 6:4] [-a 1920:1200] [-t esik] [-v]
 *
 *  -r  ekran donusu: auto (varsayilan) ya da sabit deger
 *  -i  donus telafisini ters yone cevir (yatayda eksenler ters cikiyorsa dene)
 *  -H  tablet ekrana gore bu kadar dondurulmus (eklenir)
 *  -a  ekran orani; verilirse tablet alani ortadan kirpilir (kenarlarda olu serit olur)
 *
 * Canli ayar (kalemi menzilden cikarip tekrar yaklastirinca okunur):
 *   echo "rot=auto inv=1 hold=0" > /data/local/tmp/vk640.cfg
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uhid.h>
#include <math.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <unistd.h>

#define RAW_MAX 32767
#define PRESS_MAX 8191
#define TILT_LIM 90
#define CFG_FILE "/data/local/tmp/vk640.cfg"

static volatile sig_atomic_t g_stop;
static void on_sig(int s) { (void)s; g_stop = 1; }

static const unsigned char RDESC[] = {
    0x05, 0x0D, 0x09, 0x02, 0xA1, 0x01,             /* Digitizer / Pen / Application   */
    0x85, 0x01,                                     /* Report ID 1                     */
    0x09, 0x20, 0xA1, 0x00,                         /* Stylus / Physical               */
    0x09, 0x42, 0x09, 0x44, 0x09, 0x3C, 0x09, 0x32, /* Tip, Barrel, Invert, In Range   */
    0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x04, 0x81, 0x02,
    0x95, 0x04, 0x81, 0x03,                         /* dolgu                           */
    0x05, 0x01, 0x09, 0x30,                         /* X                               */
    0x15, 0x00, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x01, 0x81, 0x02,
    0x09, 0x31, 0x81, 0x02,                         /* Y                               */
    0x05, 0x0D, 0x09, 0x30, 0x26, 0xFF, 0x1F, 0x81, 0x02, /* Tip Pressure 0..8191      */
    0x09, 0x3D, 0x09, 0x3E, 0x15, 0xA6, 0x25, 0x5A, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02,
    0xC0, 0xC0
};
#define REPORT_LEN 10

/* ---- ayarlar ---- */
static int g_verbose = 0, g_thresh = 0;
static int g_rot_auto = 1, g_rot_fixed = 0, g_inv = 0, g_hold = 0, g_scr_rot = 0;
static double g_fx = 1.0, g_fy = 1.0;

static int valid_rot(int r) { return r == 0 || r == 90 || r == 180 || r == 270; }

static int eff_rot(void)
{
    int base = g_rot_auto ? g_scr_rot : g_rot_fixed;
    if (g_inv) base = (360 - base) % 360;
    return (base + g_hold) % 360;
}

static int parse_ratio(const char *s, double *a, double *b)
{
    return sscanf(s, "%lf:%lf", a, b) == 2 && *a > 0 && *b > 0;
}

static void reload_cfg(void)
{
    FILE *f = fopen(CFG_FILE, "r");
    char line[256];
    if (!f) return;
    if (!fgets(line, sizeof line, f)) { fclose(f); return; }
    fclose(f);
    for (char *t = strtok(line, " \t\r\n"); t; t = strtok(NULL, " \t\r\n")) {
        if (!strncmp(t, "rot=", 4)) {
            if (!strcmp(t + 4, "auto")) g_rot_auto = 1;
            else if (valid_rot(atoi(t + 4))) { g_rot_auto = 0; g_rot_fixed = atoi(t + 4); }
        } else if (!strncmp(t, "inv=", 4)) {
            g_inv = atoi(t + 4) != 0;
        } else if (!strncmp(t, "hold=", 5)) {
            if (valid_rot(atoi(t + 5))) g_hold = atoi(t + 5);
        }
    }
}

static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

/* ---- overlay imleci icin UDP (loopback) ---- */
#define CUR_PORT 47650
static int g_us = -1;
static struct sockaddr_in g_dst;

static void cursor_init(void)
{
    g_us = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    memset(&g_dst, 0, sizeof g_dst);
    g_dst.sin_family = AF_INET;
    g_dst.sin_port = htons(CUR_PORT);
    g_dst.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
}

/* tablet alani (kirpma dahil) -> ekran orani, donusten bagimsiz 0..1 */
static void frac_xy(int rx, int ry, double *u, double *v)
{
    *u = clamp01(rx / (double)RAW_MAX);
    *v = clamp01(ry / (double)RAW_MAX);
    if (g_fx < 1.0) *u = clamp01((*u - (1.0 - g_fx) / 2.0) / g_fx);
    if (g_fy < 1.0) *v = clamp01((*v - (1.0 - g_fy) / 2.0) / g_fy);
}

static void cursor_send(int rx, int ry, int inrange, int tip)
{
    char b[64];
    double u, v;
    int n;
    if (g_us < 0) return;
    frac_xy(rx, ry, &u, &v);
    n = snprintf(b, sizeof b, "%.4f %.4f %d %d\n", u, v, inrange, tip);
    sendto(g_us, b, (size_t)n, MSG_DONTWAIT, (struct sockaddr *)&g_dst, sizeof g_dst);
}

static void map_xy(int rx, int ry, int *ox, int *oy)
{
    double u = clamp01(rx / (double)RAW_MAX), v = clamp01(ry / (double)RAW_MAX);
    double X, Y;
    if (g_fx < 1.0) u = clamp01((u - (1.0 - g_fx) / 2.0) / g_fx);
    if (g_fy < 1.0) v = clamp01((v - (1.0 - g_fy) / 2.0) / g_fy);
    switch (eff_rot()) {
    case 90:  X = 1.0 - v; Y = u;       break;
    case 180: X = 1.0 - u; Y = 1.0 - v; break;
    case 270: X = v;       Y = 1.0 - u; break;
    default:  X = u;       Y = v;       break;
    }
    *ox = (int)lround(X * RAW_MAX);
    *oy = (int)lround(Y * RAW_MAX);
}

/* ---- ekran donusu izleyici: sh dongusu dumpsys input'tan orientation=N okur ---- */
#define ROT_SCRIPT \
    "while :; do " \
    "o=$(dumpsys input 2>/dev/null | grep -m1 'Viewport INTERNAL' | " \
    "sed -n 's/.*orientation=\\([0-9]\\).*/\\1/p'); " \
    "[ -n \"$o\" ] && echo $o; sleep 1; done"

static pid_t start_rot_watcher(int *rfd)
{
    int p[2];
    pid_t pid;
    if (pipe(p) < 0) return -1;
    pid = fork();
    if (pid < 0) { close(p[0]); close(p[1]); return -1; }
    if (pid == 0) {
        int dn;
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        dup2(p[1], 1);
        close(p[0]);
        close(p[1]);
        dn = open("/dev/null", O_RDWR);
        if (dn >= 0) { dup2(dn, 0); dup2(dn, 2); }
        execl("/system/bin/sh", "sh", "-c", ROT_SCRIPT, (char *)NULL);
        _exit(127);
    }
    close(p[1]);
    fcntl(p[0], F_SETFL, O_NONBLOCK);
    *rfd = p[0];
    return pid;
}

static void read_rot_watcher(int rfd)
{
    char buf[64];
    ssize_t n = read(rfd, buf, sizeof buf);
    int last = -1;
    if (n <= 0) return;
    for (ssize_t i = 0; i < n; i++)
        if (buf[i] >= '0' && buf[i] <= '3') last = buf[i] - '0';
    if (last >= 0 && last * 90 != g_scr_rot) {
        g_scr_rot = last * 90;
        if (g_verbose) fprintf(stderr, "ekran donusu -> %d\n", g_scr_rot);
    }
}

/* ---- giris cihazini bul ---- */
static int has_abs(int fd, int code)
{
    unsigned char bits[ABS_MAX / 8 + 1];
    memset(bits, 0, sizeof bits);
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof bits), bits) < 0) return 0;
    return (bits[code / 8] >> (code % 8)) & 1;
}

static int find_device(char *out, size_t n)
{
    for (int i = 0; i < 64; i++) {
        char p[64], name[128] = {0};
        int fd, ok;
        snprintf(p, sizeof p, "/dev/input/event%d", i);
        fd = open(p, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        ioctl(fd, EVIOCGNAME(sizeof name - 1), name);
        ok = strstr(name, "VK640") && !strstr(name, "Mouse") && has_abs(fd, ABS_PRESSURE);
        close(fd);
        if (ok) { snprintf(out, n, "%s", p); return 0; }
    }
    return -1;
}

/* ---- UHID ---- */
static int uhid_write(int ufd, const struct uhid_event *ev)
{
    ssize_t r = write(ufd, ev, sizeof *ev);
    return r == (ssize_t)sizeof *ev ? 0 : -1;
}

static int uhid_create(int ufd)
{
    struct uhid_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = UHID_CREATE2;
    strcpy((char *)ev.u.create2.name, "VK640 Fixed Pen");
    strcpy((char *)ev.u.create2.phys, "vk640-uhid-bridge");
    ev.u.create2.rd_size = sizeof RDESC;
    ev.u.create2.bus = BUS_USB;
    ev.u.create2.vendor = 0x2FEB;
    ev.u.create2.product = 0x7F05;
    ev.u.create2.version = 1;
    memcpy(ev.u.create2.rd_data, RDESC, sizeof RDESC);
    return uhid_write(ufd, &ev);
}

static int send_report(int ufd, int tip, int barrel, int invert, int inrange,
                       int x, int y, int press, int tx, int ty)
{
    struct uhid_event ev;
    unsigned char *d;
    memset(&ev, 0, sizeof ev);
    ev.type = UHID_INPUT2;
    ev.u.input2.size = REPORT_LEN;
    d = ev.u.input2.data;
    d[0] = 1;
    d[1] = (tip ? 1 : 0) | (barrel ? 2 : 0) | (invert ? 4 : 0) | (inrange ? 8 : 0);
    d[2] = x & 0xFF;     d[3] = (x >> 8) & 0x7F;
    d[4] = y & 0xFF;     d[5] = (y >> 8) & 0x7F;
    d[6] = press & 0xFF; d[7] = (press >> 8) & 0x1F;
    d[8] = (signed char)tx;
    d[9] = (signed char)ty;
    return uhid_write(ufd, &ev);
}

static void uhid_service(int ufd)
{
    struct uhid_event ev, rep;
    if (read(ufd, &ev, sizeof ev) <= 0) return;
    memset(&rep, 0, sizeof rep);
    if (ev.type == UHID_GET_REPORT) {
        rep.type = UHID_GET_REPORT_REPLY;
        rep.u.get_report_reply.id = ev.u.get_report.id;
        rep.u.get_report_reply.err = EIO;
        uhid_write(ufd, &rep);
    } else if (ev.type == UHID_SET_REPORT) {
        rep.type = UHID_SET_REPORT_REPLY;
        rep.u.set_report_reply.id = ev.u.set_report.id;
        rep.u.set_report_reply.err = 0;
        uhid_write(ufd, &rep);
    }
}

static int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

int main(int argc, char **argv)
{
    char path[64] = "";
    double tw = 6, th = 4, sw = 0, sh = 0;
    int opt;

    while ((opt = getopt(argc, argv, "d:r:iH:p:a:t:v?")) != -1) {
        switch (opt) {
        case 'd': snprintf(path, sizeof path, "%s", optarg); break;
        case 'r':
            if (!strcmp(optarg, "auto")) g_rot_auto = 1;
            else if (valid_rot(atoi(optarg))) { g_rot_auto = 0; g_rot_fixed = atoi(optarg); }
            else { fprintf(stderr, "-r auto|0|90|180|270\n"); return 2; }
            break;
        case 'i': g_inv = 1; break;
        case 'H':
            if (!valid_rot(atoi(optarg))) { fprintf(stderr, "-H 0|90|180|270\n"); return 2; }
            g_hold = atoi(optarg);
            break;
        case 'p': if (!parse_ratio(optarg, &tw, &th)) { fprintf(stderr, "-p W:H\n"); return 2; } break;
        case 'a': if (!parse_ratio(optarg, &sw, &sh)) { fprintf(stderr, "-a W:H\n"); return 2; } break;
        case 't': g_thresh = atoi(optarg); break;
        case 'v': g_verbose = 1; break;
        default:
            fprintf(stderr, "kullanim: %s [-d dev] [-r auto|0|90|180|270] [-i] [-H 0|90|180|270] "
                            "[-p 6:4] [-a 1920:1200] [-t esik] [-v]\n", argv[0]);
            return 2;
        }
    }
    if (sw > 0 && sh > 0) {
        double pt = tw / th, ps = sw / sh;
        if (ps > pt) { g_fx = 1.0; g_fy = pt / ps; }
        else         { g_fy = 1.0; g_fx = ps / pt; }
    }
    if (!path[0] && find_device(path, sizeof path) < 0) {
        fprintf(stderr, "VK640 kalem cihazi bulunamadi (-d ile yol ver)\n");
        return 1;
    }

    signal(SIGINT, on_sig);
    signal(SIGTERM, on_sig);
    signal(SIGHUP, SIG_IGN);

    int ifd = open(path, O_RDONLY | O_CLOEXEC);
    if (ifd < 0) { perror(path); return 1; }
    int ufd = open("/dev/uhid", O_RDWR | O_CLOEXEC);
    if (ufd < 0) { perror("/dev/uhid"); return 1; }
    if (ioctl(ifd, EVIOCGRAB, 1) < 0) { perror("EVIOCGRAB (baska bir kopya calisiyor olabilir)"); return 1; }
    if (uhid_create(ufd) < 0) { perror("UHID_CREATE2"); ioctl(ifd, EVIOCGRAB, 0); return 1; }

    cursor_init();
    int rfd = -1;
    pid_t wpid = -1;
    reload_cfg();
    if (g_rot_auto) wpid = start_rot_watcher(&rfd);
    fprintf(stderr, "kaynak=%s rot=%s inv=%d hold=%d kirpma=%.3f/%.3f\n", path,
            g_rot_auto ? "auto" : "sabit", g_inv, g_hold, g_fx, g_fy);

    int x = 0, y = 0, press = 0, tx = 0, ty = 0;
    int touch = 0, barrel = 0, rubber = 0, inrange = 0, prev_in = 0, have_xy = 0;
    int in_x0 = RAW_MAX, in_x1 = 0, in_y0 = RAW_MAX, in_y1 = 0;
    int o_x0 = RAW_MAX, o_x1 = 0, o_y0 = RAW_MAX, o_y1 = 0;

    struct pollfd pf[3] = { { ifd, POLLIN, 0 }, { ufd, POLLIN, 0 }, { rfd, POLLIN, 0 } };
    while (!g_stop) {
        if (poll(pf, 3, 500) < 0) { if (errno == EINTR) continue; break; }
        if (pf[1].revents & POLLIN) uhid_service(ufd);
        if (rfd >= 0 && (pf[2].revents & POLLIN)) read_rot_watcher(rfd);
        if (pf[0].revents & (POLLERR | POLLHUP)) { fprintf(stderr, "tablet koptu\n"); break; }
        if (!(pf[0].revents & POLLIN)) continue;

        struct input_event e[64];
        ssize_t n = read(ifd, e, sizeof e);
        if (n < 0) { if (errno == EINTR || errno == EAGAIN) continue; break; }
        for (size_t i = 0; i < (size_t)n / sizeof e[0]; i++) {
            if (e[i].type == EV_KEY) {
                if (e[i].code == BTN_TOOL_PEN) {
                    inrange = e[i].value ? 1 : 0;
                    if (inrange) { have_xy = 0; reload_cfg(); }
                    else { touch = 0; press = 0; }
                } else if (e[i].code == BTN_TOUCH) touch = e[i].value ? 1 : 0;
                else if (e[i].code == BTN_STYLUS) barrel = e[i].value ? 1 : 0;
                else if (e[i].code == BTN_TOOL_RUBBER) rubber = e[i].value ? 1 : 0;
            } else if (e[i].type == EV_ABS) {
                if (!inrange) continue; /* menzil disi cop koordinat */
                switch (e[i].code) {
                case ABS_X: x = e[i].value; have_xy |= 1; break;
                case ABS_Y: y = e[i].value; have_xy |= 2; break;
                case ABS_PRESSURE: press = clampi(e[i].value, 0, PRESS_MAX); break;
                case ABS_TILT_X: tx = clampi(e[i].value, -TILT_LIM, TILT_LIM); break;
                case ABS_TILT_Y: ty = clampi(e[i].value, -TILT_LIM, TILT_LIM); break;
                }
            } else if (e[i].type == EV_SYN && e[i].code == SYN_REPORT) {
                int emit = (inrange && have_xy == 3) || (!inrange && prev_in);
                if (!emit) continue;
                int ox, oy, tip;
                map_xy(x, y, &ox, &oy);
                tip = g_thresh > 0 ? (press >= g_thresh) : touch;
                if (!inrange) tip = 0;
                if (send_report(ufd, tip, barrel, rubber, inrange, ox, oy, press, tx, ty) < 0) {
                    perror("uhid write");
                    g_stop = 1;
                    break;
                }
                cursor_send(x, y, inrange, tip);
                if (inrange) {
                    if (x < in_x0) in_x0 = x;
                    if (x > in_x1) in_x1 = x;
                    if (y < in_y0) in_y0 = y;
                    if (y > in_y1) in_y1 = y;
                    if (ox < o_x0) o_x0 = ox;
                    if (ox > o_x1) o_x1 = ox;
                    if (oy < o_y0) o_y0 = oy;
                    if (oy > o_y1) o_y1 = oy;
                }
                if (g_verbose && inrange != prev_in) {
                    if (inrange)
                        fprintf(stderr, "pen menzilde  etkin_rot=%d (ekran=%d inv=%d hold=%d)\n",
                                eff_rot(), g_scr_rot, g_inv, g_hold);
                    else {
                        fprintf(stderr, "pen menzil disi  ham x[%d..%d] y[%d..%d]  cikis x[%d..%d] y[%d..%d]\n",
                                in_x0, in_x1, in_y0, in_y1, o_x0, o_x1, o_y0, o_y1);
                        in_x0 = o_x0 = RAW_MAX; in_x1 = o_x1 = 0;
                        in_y0 = o_y0 = RAW_MAX; in_y1 = o_y1 = 0;
                    }
                }
                prev_in = inrange;
            }
        }
    }

    {
        struct uhid_event d;
        memset(&d, 0, sizeof d);
        d.type = UHID_DESTROY;
        uhid_write(ufd, &d);
    }
    ioctl(ifd, EVIOCGRAB, 0);
    if (wpid > 0) { kill(wpid, SIGTERM); waitpid(wpid, NULL, WNOHANG); }
    close(ufd);
    close(ifd);
    return 0;
}
