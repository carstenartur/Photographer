/* Black-box client of the documented LibRaw C API. No decoder implementation.
 * Declarations/types: LibRaw API-C.html and the Debian libraw-dev public headers.
 * One new context + file open + full unpack per iteration; no result caching.
 */
#define _POSIX_C_SOURCE 200809L
#include <libraw/libraw_types.h>
#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <time.h>

extern libraw_data_t *libraw_init(unsigned int);
extern void libraw_close(libraw_data_t *);
extern const char *libraw_version(void);
extern const char *libraw_strerror(int);
extern unsigned libraw_capabilities(void);
extern int libraw_open_file(libraw_data_t *, const char *);
extern int libraw_open_bayer(libraw_data_t *, unsigned char *, unsigned, ushort,
    ushort, ushort, ushort, ushort, ushort, unsigned char, unsigned char,
    unsigned, unsigned, unsigned);
extern int libraw_unpack(libraw_data_t *);
extern int libraw_dcraw_process(libraw_data_t *);
extern libraw_processed_image_t *libraw_dcraw_make_mem_image(libraw_data_t *, int *);
extern void libraw_dcraw_clear_mem(libraw_processed_image_t *);

static double now(clockid_t clock) {
    struct timespec t;
    if (clock_gettime(clock, &t)) { perror("clock_gettime"); exit(2); }
    return (double)t.tv_sec + (double)t.tv_nsec / 1e9;
}
static void require_ok(int rc, const char *stage) {
    if (rc) { fprintf(stderr, "%s: %d: %s\n", stage, rc, libraw_strerror(rc)); exit(2); }
}
static void fail(const char *message) { fprintf(stderr, "%s\n", message); exit(2); }
static int number(const char *s) {
    char *end = NULL; errno = 0; long v = strtol(s, &end, 10);
    if (errno || !end || *end || v < 0 || v > 100000) fail("invalid integer");
    return (int)v;
}
static void json_string(const char *s) {
    putchar('"');
    for (; *s; ++s) {
        unsigned char c = (unsigned char)*s;
        if (c == '\\' || c == '"') printf("\\%c", c);
        else if (c < 32) printf("\\u%04x", c);
        else putchar(c);
    }
    putchar('"');
}
static uint64_t hash_bytes(const void *data, size_t samples, int bits, int full) {
    uint64_t h = UINT64_C(14695981039346656037);
    size_t stride = full ? 1 : (samples / 64 ? samples / 64 : 1);
    for (size_t i=0; i<samples; i+=stride) {
        unsigned v = bits == 16 ? ((const uint16_t *)data)[i] : ((const uint8_t *)data)[i];
        h = (h ^ (v & 255)) * UINT64_C(1099511628211);
        if (bits == 16) h = (h ^ (v >> 8)) * UINT64_C(1099511628211);
    }
    return h;
}
static void dump_image(const char *path, const void *data, unsigned width,
                       unsigned height, unsigned channels, unsigned bits) {
    FILE *f = fopen(path, "wb");
    if (!f) { perror(path); exit(2); }
    if (fprintf(f, "P%u\n%u %u\n%u\n", channels == 1 ? 5 : 6,
                width, height, bits == 16 ? 65535 : 255) < 0) fail("dump header failed");
    size_t samples = (size_t)width * height * channels;
    if (bits == 8) {
        if (fwrite(data, 1, samples, f) != samples) fail("dump write failed");
    } else {
        /* Netpbm stores u16 samples most-significant byte first. */
        unsigned char row[16384];
        for (size_t start = 0; start < samples;) {
            size_t n = samples - start;
            if (n > sizeof(row) / 2) n = sizeof(row) / 2;
            for (size_t i=0; i<n; ++i) {
                unsigned v = ((const uint16_t *)data)[start+i];
                row[2*i] = (unsigned char)(v >> 8); row[2*i+1] = (unsigned char)v;
            }
            if (fwrite(row, 2, n, f) != n) fail("dump write failed");
            start += n;
        }
    }
    if (fclose(f)) fail("dump close failed");
}
static void usage(void) {
    puts("libraw-bench --version | --help | --selftest\n"
         "libraw-bench --input FILE --mode sensor|rgb8|rgb16 [options]\n"
         "  --warmup N --iterations N       defaults 0,1; fresh decode each time\n"
         "  --quality 0|3                  bilinear or AHD; default 0\n"
         "  --wb camera|daylight|unity      default camera\n"
         "  --transfer srgb|linear|default  default srgb for rgb8, linear for rgb16\n"
         "  --auto-bright 0|1               default 0\n"
         "  --orientation camera|none      default camera\n"
         "  --checksum                     full FNV-1a of canonical u16le/8-bit samples, after timing\n"
         "  --dump PATH                    write last output PGM/PPM, after timing\n"
         "Use OMP_NUM_THREADS=N OMP_THREAD_LIMIT=N OMP_DYNAMIC=FALSE externally.\n"
         "Sensor includes full raw mosaic materialization, without crop/color/orientation.\n"
         "Timings include input open/read and context close; exclude checksum and output encoding.\n"
         "maxrss_kb is process lifetime Linux ru_maxrss, not per-iteration allocation.");
}
int main(int argc, char **argv) {
    const char *input = NULL, *mode = "sensor", *wb = "camera", *transfer = NULL;
    const char *orientation = "camera", *dump = NULL;
    int warmup = 0, iterations = 1, quality = 0, auto_bright = 0, checksum = 0, selftest = 0;
    for (int i=1; i<argc; ++i) {
        const char *a = argv[i];
        if (!strcmp(a,"--version")) {
            printf("LibRaw %s; public-header %s; capabilities %u\n", libraw_version(), LIBRAW_VERSION_STR, libraw_capabilities());
            return 0;
        }
        if (!strcmp(a,"--help")) { usage(); return 0; }
        if (!strcmp(a,"--selftest")) { selftest = 1; continue; }
        if (!strcmp(a,"--checksum")) { checksum = 1; continue; }
        if (i+1 >= argc) fail("option requires an argument");
        const char *v = argv[++i];
        if (!strcmp(a,"--input")) input = v;
        else if (!strcmp(a,"--mode")) mode = v;
        else if (!strcmp(a,"--wb")) wb = v;
        else if (!strcmp(a,"--transfer")) transfer = v;
        else if (!strcmp(a,"--orientation")) orientation = v;
        else if (!strcmp(a,"--dump")) dump = v;
        else if (!strcmp(a,"--warmup")) warmup = number(v);
        else if (!strcmp(a,"--iterations")) iterations = number(v);
        else if (!strcmp(a,"--quality")) quality = number(v);
        else if (!strcmp(a,"--auto-bright")) auto_bright = number(v);
        else fail("unknown option");
    }
    int sensor = !strcmp(mode,"sensor");
    if (!sensor && strcmp(mode,"rgb8") && strcmp(mode,"rgb16")) fail("bad mode");
    if (!input && !selftest) { usage(); return 2; }
    if (!iterations || warmup+iterations > 100000) fail("bad iteration count");
    if (quality != 0 && quality != 3) fail("quality must be 0 or 3");
    if (auto_bright != 0 && auto_bright != 1) fail("auto-bright must be 0 or 1");
    if (strcmp(wb,"camera") && strcmp(wb,"daylight") && strcmp(wb,"unity")) fail("bad wb");
    if (strcmp(orientation,"camera") && strcmp(orientation,"none")) fail("bad orientation");
    if (!transfer) transfer = !strcmp(mode,"rgb8") ? "srgb" : "linear";
    if (strcmp(transfer,"srgb") && strcmp(transfer,"linear") && strcmp(transfer,"default")) fail("bad transfer");
    if (strncmp(libraw_version(),"0.21.4",6)) fail("unexpected runtime ABI: expected 0.21.4");
    uint16_t tiny[32*32];
    for (size_t i=0; i<32*32; ++i) tiny[i] = (uint16_t)(1024 + (i*17)%8192);
    for (int iteration=-warmup; iteration<iterations; ++iteration) {
        double cpu0 = now(CLOCK_PROCESS_CPUTIME_ID), t0 = now(CLOCK_MONOTONIC);
        libraw_data_t *lr = libraw_init(0);
        if (!lr) fail("libraw_init failed");
        lr->rawparams.use_rawspeed = 0;
        lr->rawparams.use_dngsdk = 0;
        if (selftest) {
            require_ok(libraw_open_bayer(lr, (unsigned char *)tiny, sizeof(tiny),
                32,32,0,0,0,0,0,LIBRAW_OPENBAYER_RGGB,0,0,0), "open_bayer");
        } else require_ok(libraw_open_file(lr,input),"open_file");
        double t_open = now(CLOCK_MONOTONIC);
        unsigned raw_width = lr->sizes.raw_width, raw_height = lr->sizes.raw_height;
        unsigned visible_width = lr->sizes.width, visible_height = lr->sizes.height;
        unsigned left = lr->sizes.left_margin, top = lr->sizes.top_margin;
        int source_flip = lr->sizes.flip;
        if (!sensor) {
            lr->params.half_size = 0;
            lr->params.four_color_rgb = 0;
            lr->params.output_color = 1;
            lr->params.output_bps = !strcmp(mode,"rgb8") ? 8 : 16;
            lr->params.user_qual = quality;
            lr->params.use_auto_wb = 0;
            lr->params.use_camera_wb = !strcmp(wb,"camera");
            lr->params.use_camera_matrix = 1;
            if (!strcmp(wb,"unity")) for (int c=0;c<4;++c) lr->params.user_mul[c] = 1;
            lr->params.no_auto_bright = !auto_bright;
            lr->params.adjust_maximum_thr = 0;
            lr->params.bright = 1;
            lr->params.highlight = 0;
            lr->params.user_flip = !strcmp(orientation,"none") ? 0 : -1;
            lr->params.use_fuji_rotate = 0;
            lr->params.no_auto_scale = 0;
            lr->params.med_passes = 0;
            lr->params.fbdd_noiserd = 0;
            lr->params.threshold = 0;
            if (!strcmp(transfer,"linear")) lr->params.gamm[0] = lr->params.gamm[1] = 1;
            if (!strcmp(transfer,"srgb")) { lr->params.gamm[0] = 1.0/2.4; lr->params.gamm[1] = 12.92; }
        }
        require_ok(libraw_unpack(lr),"unpack");
        double t_unpack = now(CLOCK_MONOTONIC), t_process = t_unpack;
        void *data = NULL;
        libraw_processed_image_t *rgb = NULL;
        unsigned width, height, channels, bits;
        if (sensor) {
            width = lr->sizes.raw_width; height = lr->sizes.raw_height; channels = 1; bits = 16;
            size_t row_bytes = (size_t)width*2;
            size_t pitch = lr->sizes.raw_pitch ? lr->sizes.raw_pitch : row_bytes;
            if (!width || !height || !lr->rawdata.raw_image || pitch < row_bytes) fail("unsupported sensor layout");
            data = malloc(row_bytes*height);
            if (!data) fail("sensor output allocation failed");
            for (unsigned y=0; y<height; ++y)
                memcpy((unsigned char *)data+y*row_bytes, (unsigned char *)lr->rawdata.raw_image+y*pitch, row_bytes);
        } else {
            require_ok(libraw_dcraw_process(lr),"dcraw_process");
            t_process = now(CLOCK_MONOTONIC);
            int err = 0; rgb = libraw_dcraw_make_mem_image(lr,&err);
            require_ok(err,"dcraw_make_mem_image");
            if (!rgb || rgb->type != LIBRAW_IMAGE_BITMAP) fail("expected bitmap output");
            width = rgb->width; height = rgb->height; channels = rgb->colors; bits = rgb->bits; data = rgb->data;
            if (channels != 3 || (bits != 8 && bits != 16) || rgb->data_size != (size_t)width*height*channels*(bits/8))
                fail("unexpected RGB output layout");
        }
        double t_materialize = now(CLOCK_MONOTONIC);
        unsigned warnings = lr->process_warnings;
        libraw_close(lr);
        double t_ready = now(CLOCK_MONOTONIC), cpu1 = now(CLOCK_PROCESS_CPUTIME_ID);
        struct rusage usage_data;
        if (getrusage(RUSAGE_SELF,&usage_data)) fail("getrusage failed");
        uint64_t hash = hash_bytes(data,(size_t)width*height*channels,bits,checksum);
        if (iteration >= 0) {
            printf("{\"engine\":\"LibRaw\",\"version\":"); json_string(libraw_version());
            printf(",\"input\":"); json_string(selftest?"synthetic-open-bayer-32x32":input);
            printf(",\"mode\":"); json_string(mode);
            printf(",\"wb\":"); json_string(wb);
            printf(",\"transfer\":"); json_string(transfer);
            printf(",\"orientation\":"); json_string(orientation);
            printf(",\"iteration\":%d,\"warmup\":%d,\"quality\":%d,\"auto_bright\":%d,\"width\":%u,\"height\":%u,\"channels\":%u,\"bits\":%u,\"raw_width\":%u,\"raw_height\":%u,\"visible_width\":%u,\"visible_height\":%u,\"left_margin\":%u,\"top_margin\":%u,\"source_flip\":%d,\"warnings\":%u,\"open_ms\":%.3f,\"unpack_ms\":%.3f,\"process_ms\":%.3f,\"materialize_ms\":%.3f,\"close_ms\":%.3f,\"total_ms\":%.3f,\"cpu_ms\":%.3f,\"maxrss_kb\":%ld,\"checksum_kind\":\"%s\",\"fnv1a64\":\"%016" PRIx64 "\"}\n",
                iteration,warmup,quality,auto_bright,width,height,channels,bits,raw_width,raw_height,
                visible_width,visible_height,left,top,source_flip,warnings,
                (t_open-t0)*1000,(t_unpack-t_open)*1000,(t_process-t_unpack)*1000,
                (t_materialize-t_process)*1000,(t_ready-t_materialize)*1000,(t_ready-t0)*1000,
                (cpu1-cpu0)*1000,usage_data.ru_maxrss,checksum?"full-u16le-or-u8":"sampled-u16le-or-u8",hash);
            fflush(stdout);
        }
        if (dump && iteration==iterations-1) dump_image(dump,data,width,height,channels,bits);
        if (rgb) libraw_dcraw_clear_mem(rgb); else free(data);
    }
    return 0;
}
