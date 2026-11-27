#include "upsample.h"
#include "band_frame.h"
#include "dsx.h"
#include "mem_io.h"

#include <stdio.h>
#include <stdlib.h>

#ifdef _WIN32
#include <windows.h>
static double f80_now(void)
{
    static LARGE_INTEGER freq;
    LARGE_INTEGER t;
    if (!freq.QuadPart)
        QueryPerformanceFrequency(&freq);
    QueryPerformanceCounter(&t);
    return (double)t.QuadPart / (double)freq.QuadPart;
}
#else
#include <time.h>
static double f80_now(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec * 1e-9;
}
#endif

void dsee_upsample(const float *h, int N, int S, const float *x, float *y,
                   int accumulate, int niter);
void dsee_biquad(const float *coef, float gain, float *buf, const float *src,
                 float *st, int n);
void dsee_fir21(const float *c, int stride, const float *in, float *out);

__attribute__((visibility("default"))) void dsee_f80_pipeline(uint8_t *mem, float *pcm,
                                             int n_in_ch, int n_out_ch)
{
    int c = DSX_CTX;
    int nslots = (int)dsx_ru32(mem, DSX_OFF_NSLOTS);
    int up = (int)dsx_ru32(mem, c + F80_OFF_UP_FACTOR);
    int n8 = (int)dsx_ru32(mem, c + F80_OFF_N8);
    int n12 = (int)dsx_ru32(mem, c + F80_OFF_N12);
    int path = (int)dsx_ru32(mem, c + DSX_OFF_PATH_MODE);
    int off_up = (int)dsx_ru32(mem, c + F80_OFF_OFF_UP);
    int off_low = (int)dsx_ru32(mem, c + F80_OFF_OFF_LOW);
    int gate = (int)dsx_ru32(mem, DSX_OFF_GATE);
    int stride = nslots;
    int k, i;
    /* F80_DETAIL: sub-timers + print. DSEE_PROFILE (frame.c) stays quiet here. */
    int do_detail = getenv("F80_DETAIL") != NULL;
    double sec_up1 = 0, sec_bq = 0, sec_fir = 0, sec_up2 = 0, sec_misc = 0, t0;

    /* Per-slot state is disjoint; band_gain/input_decode stay serial.
     * Skip OpenMP while detailing so timers are accurate. */
#ifdef _OPENMP
#pragma omp parallel for private(i) if (nslots > 1 && !do_detail)
#endif
    for (k = 0; k < nslots; k++) {
        int u = c + F80_SLOT_STAGE1 + k * DSX_SLOT;
        int src = (int)dsx_ru32(mem, u);
        int dst = (int)dsx_ru32(mem, u - 4);
        int buf = src + 8 * n8;
        float *gathered = dsx_pf(mem, buf);
        for (i = 0; i < n_in_ch; i++)
            gathered[i] = pcm[i * nslots + k];
        if (path != DSX_PATH_BYPASS && dsx_ru32(mem, c + F80_OFF_SKIP_UP1) == 0) {
            int n = (int)dsx_ru32(mem, c + F80_OFF_N8);
            if (do_detail)
                t0 = f80_now();
            dsee_upsample(dsx_pf(mem, (int)dsx_ru32(mem, c + F80_OFF_UP1_COEF)), n,
                          up, dsx_pf(mem, src), dsx_pf(mem, dst + 4 * off_up), 0,
                          0);
            if (do_detail)
                sec_up1 += f80_now() - t0;
        }
        if (do_detail)
            t0 = f80_now();
        dsee_biquad(dsx_pf(mem, (int)dsx_ru32(mem, c + F80_OFF_BQ_COEF)),
                    dsx_rf32(mem, c + F80_OFF_BQ_GAIN), dsx_pf(mem, buf + 592),
                    dsx_pf(mem, buf), dsx_pf(mem, u + 8),
                    (int)dsx_ru32(mem, c + F80_OFF_BQ_NSEC) << 7);
        if (do_detail)
            sec_bq += f80_now() - t0;
        dsx_energy4(dsx_pf(mem, (int)dsx_ru32(mem, c + F80_OFF_ENERGY4_COEF)),
                    dsx_pf(mem, buf + 512), dsx_pf(mem, u + 16));
        dsx_energy16(dsx_pf(mem, buf + 592), dsx_pf(mem, u + 32));
        dsx_agc_f80(mem, c, dsx_pf(mem, u + 16),
                    dsx_pf(mem, (int)dsx_ru32(mem, u + 4)));
        if (do_detail)
            t0 = f80_now();
        dsee_fir21(dsx_pf(mem, (int)dsx_ru32(mem, u + 4)), 4,
                   dsx_pf(mem, buf + 512),
                   dsx_pf(mem, u + 4 * (2 * n12 + 9)));
        if (do_detail)
            sec_fir += f80_now() - t0;
    }
    if (do_detail)
        t0 = f80_now();
    dsx_band_gain_f80(mem, c);
    dsx_input_decode(mem, c);
    if (do_detail)
        sec_misc += f80_now() - t0;
#ifdef _OPENMP
#pragma omp parallel for private(i) if (nslots > 1 && !do_detail)
#endif
    for (k = 0; k < nslots; k++) {
        int base = c + F80_SLOT_STAGE2 + k * DSX_SLOT;
        int src = (int)dsx_ru32(mem, base);
        if (path != DSX_PATH_COPY) {
            int s = 4 * up;
            int hdr = c + F80_OFF_UP2_HDR0 + 8 * k;
            float *y = dsx_pf(mem, src + 4 * off_low);
            int n = (int)dsx_ru32(mem, hdr + 4);
            int n2 = (int)dsx_ru32(mem, hdr + 20);
            if (do_detail)
                t0 = f80_now();
            dsee_upsample(dsx_pf(mem, (int)dsx_ru32(mem, hdr)), n, s,
                          dsx_pf(mem, base + 40), y, 1, 0);
            dsee_upsample(dsx_pf(mem, (int)dsx_ru32(mem, hdr + 16)), n2, s,
                          dsx_pf(mem, base + 280), y, 1, 0);
            if (do_detail)
                sec_up2 += f80_now() - t0;
        }
        for (i = 0; i < n_out_ch; i++)
            pcm[i * stride + k] = dsx_rf32(mem, src + 4 * i);
        if (gate != 2 && gate != 4)
            continue;
        {
            int p1 = (int)dsx_ru32(mem, base + 4);
            int hs = src + (up << 9);
            int ncopy =
                (dsx_ru32(mem, c + F80_OFF_SKIP_UP1) &&
                 dsx_ru32(mem, c + F80_OFF_HIST_FLAG))
                    ? off_up + 256
                    : off_up;
            dsx_copy_f(dsx_pf(mem, p1), dsx_pf(mem, p1 + 512), 2 * n8);
            dsx_copy_f(dsx_pf(mem, p1 + 8 * n8 + 512),
                       dsx_pf(mem, p1 + 8 * n8 + 1024), 20);
            dsx_copy_f(dsx_pf(mem, base + 40), dsx_pf(mem, base + 168),
                       2 * n12);
            dsx_copy_f(dsx_pf(mem, src), dsx_pf(mem, hs), ncopy);
        }
    }
    if (do_detail) {
        fprintf(stderr,
                "F80_DETAIL up1(S=up)=%.4fs biquad=%.4fs fir21=%.4fs "
                "up2(S=4up)=%.4fs gain+decode=%.4fs\n",
                sec_up1, sec_bq, sec_fir, sec_up2, sec_misc);
        fflush(stderr);
    }
}
