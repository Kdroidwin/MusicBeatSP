#pragma once
#include <math.h>
#include <stdint.h>

#include "dsx_layout.h"

/*
 * Soft Ext80 wrappers → IEEE f32 / fmaf / double where probed ≡ on audio normals.
 * Kept as static inline so FIR/biquad/envelope call sites avoid call+Ext80 overhead.
 */
static inline float x87_mul(float a, float b) { return a * b; }
static inline float x87_add(float a, float b) { return a + b; }
static inline float x87_sub(float a, float b) { return a - b; }
static inline float x87_mac(float acc, float coef, float x)
{
    return fmaf(coef, x, acc);
}
static inline float x87_sqadd(float acc, float x) { return fmaf(x, x, acc); }
static inline float x87_addmul(float c, float d, float e)
{
    return (float)(((double)c + (double)d) * (double)e);
}
static inline float x87_diff25(float mid, float neu, float old)
{
    return (float)((double)mid * 0.5 - ((double)neu + (double)old) * 0.25);
}
static inline float x87_env(float env, float x, float a)
{
    return (float)((1.0 - (double)a) * (double)x * (double)x +
                   (double)env * (double)a);
}
static inline float x87_midside(float h1, float h3, float g)
{
    float side = (h1 - h3) * g;
    return (float)((double)h1 + (double)h3 + (double)side);
}

/* Four parallel MACs sharing one tap sum — energy_tap22 / synth_fir22. */
static inline void x87_mac4(float *a0, float *a1, float *a2, float *a3,
                            const float *c, float s)
{
    *a0 = x87_mac(*a0, c[0], s);
    *a1 = x87_mac(*a1, c[1], s);
    *a2 = x87_mac(*a2, c[2], s);
    *a3 = x87_mac(*a3, c[3], s);
}

static inline uint32_t f76_cbase(void) { return DSX_CTX; }
static inline uint32_t f76_slot_base(uint32_t cbase, int ch)
{
    return cbase + (uint32_t)ch * (uint32_t)DSX_SLOT;
}
static inline uint32_t f76_slot_ptrs(uint32_t cbase, int ch)
{
    return f76_slot_base(cbase, ch) + F76_SLOT_PTRS;
}
static inline uint32_t f76_slot_ptrs2(uint32_t cbase, int ch)
{
    return f76_slot_base(cbase, ch) + F76_SLOT_PTRS2;
}
static inline uint32_t f76_slot_work(uint32_t cbase, int ch)
{
    return f76_slot_base(cbase, ch) + F76_SLOT_WORK;
}

void dsx_gather(const float *src, float *dst, int n, int ch, int nslots);
void dsx_clear_f(float *p, int n);
void dsx_copy_f(float *dst, const float *src, int n);
void dsx_biquad_cascade(uint8_t *mem, uint32_t block, float *src, float *dst,
                        float *state);
void dsx_energy_tap22(uint8_t *mem, uint32_t coef, uint32_t buf, uint32_t out,
                      int nout);
/*
 * Mid/side envelope (shared across channels).
 *   coef_off / state_off — shared tables in F76 ctx
 *   loudness_bias — f32 from F76_OFF_ENV_LOUDNESS
 *   src_offs[ch] / dst_offs[ch] — per-channel buffer absolute offsets
 */
void dsx_envelope_ms(uint8_t *mem, int nslots, int nframe, uint32_t coef_off,
                     uint32_t state_off, float loudness_bias,
                     const uint32_t *src_offs, const uint32_t *dst_offs);
/*
 * Per-channel band gains. Ownership:
 *   slot_off — writable per-channel slot (energies → linear gains)
 *   meta_off — SHARED gain meta (band count / index map); read-only here
 *   coefs_off — SHARED FIR/energy coef header (src/dst ptrs); read + write out
 *   ctx_off — SHARED F76 context (limits / curve tables); read-only
 */
void dsx_band_gain8(uint8_t *mem, uint32_t slot_off, uint32_t meta_off,
                    uint32_t coefs_off, int use_static_curve, uint32_t ctx_off);
/*
 * Synthesis FIR. coef — FIR taps; meta_off — SHARED (mix matrix ptr + nch).
 * src/dst — per-channel buffer absolute offsets.
 */
void dsx_synth_fir22(uint8_t *mem, uint32_t coef, uint32_t meta_off,
                     uint32_t src, uint32_t dst);
void dsx_writeback(float *src, float *dst, int n, int upmode, int ch,
                   int nslots);
