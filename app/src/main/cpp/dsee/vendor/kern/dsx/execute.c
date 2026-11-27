#include "dsx.h"
#include "dsx_layout.h"
#include "mem_io.h"

__attribute__((visibility("default"))) void dsee_dsp(uint8_t *mem, float *pcm, int n_in_ch,
                                    int n_out_ch)
{
    if (dsx_ru32(mem, DSX_OFF_F76_ENABLE) != 0)
        dsee_f76_frame(mem, pcm, n_in_ch, n_in_ch, 0);
    if (dsx_ru32(mem, DSX_OFF_F80_ENABLE) != 0)
        dsee_f80_pipeline(mem, pcm, n_in_ch, n_out_ch);
}
