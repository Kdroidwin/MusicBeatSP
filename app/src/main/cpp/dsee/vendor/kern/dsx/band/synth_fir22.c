#include "band_frame.h"
#include "mem_io.h"

void dsx_synth_fir22(uint8_t *mem, uint32_t coef, uint32_t meta_off, uint32_t src,
                     uint32_t dst)
{
    float *taps = dsx_F(mem, coef);
    /* meta: +0 → mix matrix (shared), +4 → channel count in matrix. */
    float *mix = dsx_F(mem, *(uint32_t *)(mem + meta_off));
    int nch = (int)*(uint32_t *)(mem + meta_off + 4);
    int pos = 20;

    for (;;) {
        float *x = dsx_F(mem, src + 4 * pos);
        int lane;
        for (lane = 0; lane < 2 * nch; lane++) {
            float *left = x - 3;
            float *tap = taps + 4;
            float *right = x + 1;
            float acc_lo = x87_mul(x[0], taps[0]);
            float acc_hi = x87_mul(x[0], taps[1]);
            int half;
            for (half = 0; half < 2; half++) {
                float s;
                s = x87_add(left[2], right[0]);
                acc_lo = x87_mac(acc_lo, tap[-2], s);
                acc_hi = x87_mac(acc_hi, tap[-1], s);
                s = x87_add(right[1], left[1]);
                acc_lo = x87_mac(acc_lo, tap[0], s);
                acc_hi = x87_mac(acc_hi, tap[1], s);
                s = x87_add(right[2], left[0]);
                acc_lo = x87_mac(acc_lo, tap[2], s);
                acc_hi = x87_mac(acc_hi, tap[3], s);
                s = x87_add(right[3], left[-1]);
                acc_lo = x87_mac(acc_lo, tap[4], s);
                acc_hi = x87_mac(acc_hi, tap[5], s);
                s = x87_add(right[4], left[-2]);
                acc_lo = x87_mac(acc_lo, tap[6], s);
                acc_hi = x87_mac(acc_hi, tap[7], s);
                s = x87_add(right[5], left[-3]);
                acc_lo = x87_mac(acc_lo, tap[8], s);
                acc_hi = x87_mac(acc_hi, tap[9], s);
                right += 10;
                left -= 10;
                tap += 20;
                s = x87_add(right[-4], left[6]);
                acc_lo = x87_mac(acc_lo, tap[-10], s);
                acc_hi = x87_mac(acc_hi, tap[-9], s);
                s = x87_add(right[-3], left[5]);
                acc_lo = x87_mac(acc_lo, tap[-8], s);
                acc_hi = x87_mac(acc_hi, tap[-7], s);
                s = x87_add(right[-2], left[4]);
                acc_lo = x87_mac(acc_lo, tap[-6], s);
                acc_hi = x87_mac(acc_hi, tap[-5], s);
                s = x87_add(right[-1], left[3]);
                acc_lo = x87_mac(acc_lo, tap[-4], s);
                acc_hi = x87_mac(acc_hi, tap[-3], s);
            }
            dsx_F(mem, dst + 4 * pos - 80)[lane] = x87_add(
                x87_mul(mix[lane + 2 * nch], acc_hi), x87_mul(mix[lane], acc_lo));
            x += 1;
        }
        if (pos + 2 * nch >= 148)
            break;
        pos += 2 * nch;
    }
}
