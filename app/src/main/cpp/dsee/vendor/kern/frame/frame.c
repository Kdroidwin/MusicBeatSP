#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
static double dsee_now(void)
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
static double dsee_now(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec * 1e-9;
}
#endif

#include "dsx.h"
#include "mem_io.h"

#define EXPORT __attribute__((visibility("default")))

void dsee_heq(float *ring0, float *ring1, float *hist0, float *hist1, int *lag,
              int *hidx, const int *delay, double gain, const int32_t *in,
              int32_t *out, int nframe);
void dsee_frame(uint8_t *mem, const int32_t *in, int n_in, int n_out,
                int n_in_ch, int n_out_ch, float *pcm);

/* Soft clamp to Q31 then back to f32 — shared by hop loop and dsee_frame. */
static void dsee_quant_frame(float *frame, int n)
{
    float hi;
    uint32_t hib = 0x3f7fffffu;
    int i;
    memcpy(&hi, &hib, 4);
    for (i = 0; i < n; i++) {
        float v = frame[i];
        int iv;
        if (v >= hi)
            iv = 2147483520;
        else if (v <= -1.f)
            iv = -2147483647 - 1;
        else
            iv = (int)((double)v * 2147483648.0);
        frame[i] = (float)((double)iv / 2147483648.0);
    }
}

static void heq_i32_to_f32(const int32_t *heq, float *frame, int n_in, int n_out)
{
    int i;
    for (i = 0; i < n_out; i++)
        frame[i] = 0.f;
    for (i = 0; i < n_in; i++)
        frame[i] = (float)((double)heq[i] * 0x1p-31);
}

/* 1 = HEQ on (default); 0 = dry pass-through (advanced bypass). */
static int g_heq_enabled = 1;

__attribute__((visibility("default"))) void dsee_set_heq_enabled(int enabled)
{
    g_heq_enabled = enabled ? 1 : 0;
}

static int process_i32_with_scratch(uint8_t *mem, float *ring0, float *ring1,
                                    float *hist0, float *hist1, int *lag,
                                    int *hidx, const int *delay, double gain,
                                    const int32_t *pcm, int hops, int n_in,
                                    int n_out, int n_in_ch, int n_out_ch,
                                    float *out, int32_t *heq, float *frame)
{
    int h;
    if (hops < 0 || n_in < 2 || (n_in & 1) || n_out < 1)
        return 1;
    if (!heq || !frame)
        return 2;
    {
        /* DSEE_PROFILE = hop totals only. F80 sub-timers use F80_DETAIL. */
        int do_prof = getenv("DSEE_PROFILE") != NULL;
        double sec_heq = 0, sec_f76 = 0, sec_f80 = 0, sec_quant = 0, t0;
        int f76_on = (int)dsx_ru32(mem, 76);
        int f80_on = (int)dsx_ru32(mem, 80);
        for (h = 0; h < hops; h++) {
            const int32_t *hop_pcm = pcm + (size_t)h * n_in;
            if (do_prof)
                t0 = dsee_now();
            if (g_heq_enabled) {
                dsee_heq(ring0, ring1, hist0, hist1, lag, hidx, delay, gain,
                         hop_pcm, heq, n_in / 2);
            } else {
                memcpy(heq, hop_pcm, (size_t)n_in * sizeof(int32_t));
            }
            if (do_prof)
                sec_heq += dsee_now() - t0;

            heq_i32_to_f32(heq, frame, n_in, n_out);

            if (f76_on) {
                if (do_prof)
                    t0 = dsee_now();
                dsee_f76_frame(mem, frame, n_in_ch, n_in_ch, 0);
                if (do_prof)
                    sec_f76 += dsee_now() - t0;
            }
            if (f80_on) {
                if (do_prof)
                    t0 = dsee_now();
                dsee_f80_pipeline(mem, frame, n_in_ch, n_out_ch);
                if (do_prof)
                    sec_f80 += dsee_now() - t0;
            }

            if (do_prof)
                t0 = dsee_now();
            dsee_quant_frame(frame, n_out);
            if (do_prof)
                sec_quant += dsee_now() - t0;
            memcpy(out + (size_t)h * n_out, frame, (size_t)n_out * sizeof(float));
        }
        if (do_prof) {
            fprintf(stderr,
                    "DSEE_PROFILE hops=%d heq=%.4fs f76=%.4fs%s f80=%.4fs%s "
                    "quant=%.4fs\n",
                    hops, sec_heq, sec_f76, f76_on ? "" : "(off)", sec_f80,
                    f80_on ? "" : "(off)", sec_quant);
            fflush(stderr);
        }
    }
    return 0;
}

EXPORT int dsee_process_i32_with_scratch(
    uint8_t *mem, float *ring0, float *ring1, float *hist0, float *hist1,
    int *lag, int *hidx, const int *delay, double gain, const int32_t *pcm,
    int hops, int n_in, int n_out, int n_in_ch, int n_out_ch, float *out,
    int32_t *heq_scratch, float *frame_scratch)
{
    return process_i32_with_scratch(mem, ring0, ring1, hist0, hist1, lag, hidx,
                                    delay, gain, pcm, hops, n_in, n_out,
                                    n_in_ch, n_out_ch, out, heq_scratch,
                                    frame_scratch);
}

EXPORT int dsee_process_i32(uint8_t *mem, float *ring0, float *ring1,
                            float *hist0, float *hist1, int *lag, int *hidx,
                            const int *delay, double gain, const int32_t *pcm,
                            int hops, int n_in, int n_out, int n_in_ch,
                            int n_out_ch, float *out)
{
    int32_t *heq = (int32_t *)malloc((size_t)n_in * sizeof(int32_t));
    float *frame = (float *)malloc((size_t)n_out * sizeof(float));
    int rc;
    if (!heq || !frame) {
        free(heq);
        free(frame);
        return 2;
    }
    rc = process_i32_with_scratch(mem, ring0, ring1, hist0, hist1, lag, hidx,
                                  delay, gain, pcm, hops, n_in, n_out,
                                  n_in_ch, n_out_ch, out, heq, frame);
    free(heq);
    free(frame);
    return rc;
}

EXPORT void dsee_frame(uint8_t *mem, const int32_t *in, int n_in, int n_out,
                       int n_in_ch, int n_out_ch, float *pcm)
{
    heq_i32_to_f32(in, pcm, n_in, n_out);
    dsee_dsp(mem, pcm, n_in_ch, n_out_ch);
    dsee_quant_frame(pcm, n_out);
}
