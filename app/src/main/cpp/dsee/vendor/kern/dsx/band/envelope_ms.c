#include "band_frame.h"
#include <math.h>
#include "mem_io.h"

/* State float layout (relative to state_off as float*):
 *   [1+ch]     — smoothed energies used for gain
 *   [3+ch]     — env pair (diff / mid)
 *   [8+ch*5]   — 5-tap delay for analysis
 *   [18+ch*5]  — 5-tap delay for mid/side reshape
 *   [27]       — shared mid/side gain (min across ch, then linear)
 *   [28..31]   — env time constants
 * Header u32 at state_off: frame counter; +132: warm-up limit.
 */
void dsx_envelope_ms(uint8_t *mem, int nslots, int nframe, uint32_t coef_off,
                     uint32_t state_off, float loudness_bias,
                     const uint32_t *src_offs, const uint32_t *dst_offs)
{
    float *st = dsx_F(mem, state_off);
    float *cf = dsx_F(mem, coef_off);
    int ch, i;

    /* Pass 1: per-channel envelope trackers. */
    for (ch = 0; ch < nslots; ch++) {
        float *in = dsx_F(mem, src_offs[ch]);
        float *env = st + 3 + ch;
        float *delay = st + 8 + ch * 5;
        for (i = 0; i < nframe; i++) {
            float neu, dev;
            delay[3] = delay[2];
            delay[2] = delay[1];
            delay[1] = delay[0];
            delay[0] = delay[-1];
            neu = in[i];
            delay[-1] = neu;

            dev = x87_diff25(delay[1], neu, delay[3]);
            env[-2] = x87_env(env[-2], dev, st[29]);
            env[0] = x87_env(env[0], delay[1], st[31]);
        }
    }

    /* Pass 2: pick the tightest mid/side gain across channels (dB domain). */
    st[27] = 100000.0f;
    for (ch = 0; ch < nslots; ch++) {
        float *ener = st + 1 + ch;
        float mid_db =
            x87_add(x87_mul((float)log10((double)ener[2]), 10.f), loudness_bias);
        float headroom = x87_sub(0.f, x87_add(mid_db, cf[20]));
        float side_db, low_db, cand;
        if (headroom < 0.f)
            headroom = 0.f;
        side_db = x87_add(x87_mul(mid_db, cf[7]), cf[8]);
        low_db =
            x87_add(x87_mul((float)log10((double)ener[0]), 10.f), loudness_bias);
        if (cf[9] > low_db)
            low_db = cf[9];
        cand = x87_sub(side_db, low_db);
        if (cf[10] < cand)
            cand = cf[10];
        if (headroom < cand)
            cand = headroom;
        if (st[27] > cand)
            st[27] = cand;
    }
    {
        float g = st[27];
        if (g < 0.f)
            g = 0.f;
        st[27] = (float)pow(10.0, (double)g / 20.0);
    }

    /* Pass 3: apply mid/side reshape into dst buffers. */
    for (ch = 0; ch < nslots; ch++) {
        float *src = dsx_F(mem, src_offs[ch]);
        float *dst = dsx_F(mem, dst_offs[ch]);
        float *delay = st + 18 + ch * 5;
        for (i = 0; i < nframe; i++) {
            float neu, h1, h3;
            delay[3] = delay[2];
            delay[2] = delay[1];
            delay[1] = delay[0];
            delay[0] = delay[-1];
            neu = src[i];
            delay[-1] = neu;

            h1 = x87_mul(delay[1], 0.5f);
            h3 = x87_addmul(neu, delay[3], 0.25f);
            dst[i] = x87_midside(h1, h3, st[27]);
        }
    }

    /* Warm-up: after lim frames, freeze env time constants. */
    {
        uint32_t cnt = *(uint32_t *)(mem + state_off);
        uint32_t lim = *(uint32_t *)(mem + state_off + 132);
        if (cnt > lim) {
            st[29] = st[28];
            st[31] = st[30];
        } else {
            *(uint32_t *)(mem + state_off) = cnt + (uint32_t)nframe;
        }
    }
}
