#include "band_frame.h"
#include <math.h>
#include "mem_io.h"

/* Slot float indices (per-channel). */
#define SLOT_E5 184 /* 5 analysis energies → dB */
#define SLOT_E4 224 /* 4 side energies → dB */
#define SLOT_STATIC_G 188
#define SLOT_GAINS 228 /* 8 linear band gains (written) */

/* Shared ctx float indices (read-only). */
#define CTX_CURVE_ODD 4649
#define CTX_CURVE_EVEN 4650
#define CTX_LIM_A 4753
#define CTX_LIM_B 4757
#define CTX_LIM_C 1126
#define CTX_FLOOR 1127
#define CTX_GAIN_CAP 1113
#define CTX_OFF_MODE 4496 /* u32 */
#define CTX_OFF_FLOOR_EN 4460 /* u32 */
#define CTX_SHAPE_TABLE 18436

static float clamp_energy_for_db(float e)
{
    float v = x87_mul(e, 16.f);
    if (v >= 1.0e-12f) {
        if (v > 16.f)
            v = 16.f;
    } else {
        v = 1.0e-12f;
    }
    return x87_mul((float)log10((double)v), 10.f);
}

static void clamp_band_window(float *bands, float *slot, float *ctx, uint8_t *mem,
                              uint32_t ctx_off)
{
    uint32_t off_mode = *(uint32_t *)(mem + ctx_off + CTX_OFF_MODE);
    uint32_t floor_en = *(uint32_t *)(mem + ctx_off + CTX_OFF_FLOOR_EN);
    float lim = x87_sub(-2.f, ctx[CTX_LIM_A]);
    float off = x87_add(ctx[CTX_LIM_B], ctx[CTX_LIM_C]);
    float *band = &bands[1];
    float *side = slot + SLOT_E4 + 1; /* a1+225 */
    int idx = 2;
    do {
        if (off_mode == 1)
            band[-1] = x87_add(off, band[-1]);
        if (band[-1] > lim)
            band[-1] = lim;
        if (floor_en != 0 && idx - 2 < 4) {
            float g = x87_add(side[-1], ctx[CTX_FLOOR]);
            if (band[-1] < g)
                band[-1] = g;
        }
        if (off_mode != 0) {
            if (off_mode == 1)
                band[0] = x87_add(off, band[0]);
        } else {
            band[-1] = x87_add(off, band[-1]);
        }
        if (band[0] > lim)
            band[0] = lim;
        if (floor_en != 0 && idx - 1 < 4) {
            float g = x87_add(ctx[CTX_FLOOR], side[0]);
            if (band[0] < g)
                band[0] = g;
        }
        if (off_mode != 0) {
            if (off_mode == 1)
                band[1] = x87_add(off, band[1]);
        } else {
            band[0] = x87_add(off, band[0]);
        }
        if (band[1] > lim)
            band[1] = lim;
        if (floor_en != 0 && idx < 4) {
            float g = x87_add(side[1], ctx[CTX_FLOOR]);
            if (band[1] < g)
                band[1] = g;
        }
        if (off_mode != 0) {
            if (off_mode == 1)
                band[2] = x87_add(off, band[2]);
        } else {
            band[1] = x87_add(off, band[1]);
        }
        if (band[2] > lim)
            band[2] = lim;
        if (floor_en != 0 && idx + 1 < 4) {
            float g = x87_add(side[2], ctx[CTX_FLOOR]);
            if (band[2] < g)
                band[2] = g;
        }
        if (off_mode == 0)
            band[2] = x87_add(off, band[2]);
        idx += 4;
        side += 4;
        band += 4;
    } while (idx - 2 < 8);
}

void dsx_band_gain8(uint8_t *mem, uint32_t slot_off, uint32_t meta_off,
                    uint32_t coefs_off, int use_static_curve, uint32_t ctx_off)
{
    float *slot = dsx_F(mem, slot_off);
    float *ctx = dsx_F(mem, ctx_off);
    float bands[9];
    int i, k;

    for (k = 0; k < 9; k++)
        bands[k] = 0.0f;

    for (i = 0; i < 5; i++)
        slot[SLOT_E5 + i] = clamp_energy_for_db(slot[SLOT_E5 + i]);
    for (i = 0; i < 4; i++)
        slot[SLOT_E4 + i] = clamp_energy_for_db(slot[SLOT_E4 + i]);

    if (use_static_curve != 0) {
        float g = slot[SLOT_STATIC_G];
        for (i = 0; i < 8; i++)
            bands[i] = x87_add(x87_mul(g, ctx[CTX_CURVE_EVEN + 2 * i]),
                               ctx[CTX_CURVE_ODD + 2 * i]);
    } else {
        float *shape = dsx_F(mem, ctx_off + CTX_SHAPE_TABLE);
        k = 0;
        bands[0] = 0.0f;
        while (k < 8) {
            float anchor = shape[0];
            float *rest = shape + 1;
            float w0, acc;
            bands[k] = anchor;
            k++;
            w0 = rest[0];
            shape = rest + 4;
            acc = x87_mac(bands[k - 1], w0, slot[SLOT_E5 + 0]);
            acc = x87_mac(acc, shape[-3], slot[SLOT_E5 + 1]);
            acc = x87_mac(acc, shape[-2], slot[SLOT_E5 + 2]);
            bands[k - 1] = x87_mac(acc, shape[-1], slot[SLOT_E5 + 3]);
        }
    }

    clamp_band_window(bands, slot, ctx, mem, ctx_off);

    /* meta: +12 = band count, +16 = split, +20 = index map; gains → slot[228+]. */
    {
        int n = (int)*(uint32_t *)(mem + meta_off + 12);
        for (i = 0; i < n; i++) {
            uint32_t idx = *(uint32_t *)(mem + meta_off + 20 + 4 * i);
            float g = (float)pow(
                10.0, (double)x87_sub(bands[i], slot[idx + SLOT_E5]) / 20.0);
            slot[SLOT_GAINS + i] = g;
            if (ctx[CTX_GAIN_CAP] < g)
                slot[SLOT_GAINS + i] = ctx[CTX_GAIN_CAP];
        }
        for (i = n; i < 8; i++)
            slot[SLOT_GAINS + i] = 0.0f;
    }

    /* coefs header: +0 src ptr, +4 dst ptr — apply gains to shared FIR path. */
    {
        float *out = dsx_F(mem, *(uint32_t *)(mem + coefs_off + 4));
        float *src = dsx_F(mem, *(uint32_t *)(mem + coefs_off));
        int split = (int)*(uint32_t *)(mem + meta_off + 16);
        int total = (int)*(uint32_t *)(mem + meta_off + 12);
        int src_i = 0, byte_i = 0;
        do {
            float acc0 = 0.0f, acc1 = 0.0f;
            for (i = 0; i < split; i++)
                acc0 = x87_mac(acc0, src[src_i + i], slot[SLOT_GAINS + i]);
            out[0] = acc0;
            for (i = 0; i < total - split; i++)
                acc1 = x87_mac(acc1, src[src_i + i],
                               slot[SLOT_GAINS + split + i]);
            out[1] = acc1;
            src_i += 4;
            byte_i += 16;
            out += 2;
        } while (byte_i < 336);
    }
}
