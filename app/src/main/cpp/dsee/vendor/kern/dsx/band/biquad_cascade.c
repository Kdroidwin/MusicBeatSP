#include "band_frame.h"
#include "biquad_df1.h"
#include "mem_io.h"

void dsx_biquad_cascade(uint8_t *mem, uint32_t block, float *src, float *dst,
                        float *state)
{
    float *cp = dsx_F(mem, *(uint32_t *)(mem + block));
    float gain = *(float *)(mem + block + 4);
    int n = (int)(*(uint32_t *)(mem + block + 12) << 7);
    int secs = (int)(*(uint32_t *)(mem + block + 8) / 4);
    int s;
    dsee_biquad_apply_gain(dst, src, gain, n);
    for (s = 0; s < secs; s++) {
        Ext80 ec0 = ext80_from_f32(cp[4 * s + 0]);
        Ext80 ec1 = ext80_from_f32(cp[4 * s + 1]);
        Ext80 ec2 = ext80_from_f32(cp[4 * s + 2]);
        Ext80 ec3 = ext80_from_f32(cp[4 * s + 3]);
        dsee_biquad_df1_section(dst, n, ec0, ec1, ec2, ec3, &state[2 * s],
                                &state[2 * s + 1]);
    }
}
