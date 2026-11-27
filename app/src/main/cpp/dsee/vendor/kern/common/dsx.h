#pragma once
#include <stdint.h>

/* n_in_ch / n_out_ch: samples per channel per hop (rs_in / rs_out).
 * wb_f32_off: writeback start index into the work buffer (normally 0). */
void dsee_f76_frame(uint8_t *mem, float *pcm, int n_in_ch, int n_out_ch,
                    int wb_f32_off);
void dsee_f80_pipeline(uint8_t *mem, float *pcm, int n_in_ch, int n_out_ch);
void dsee_dsp(uint8_t *mem, float *pcm, int n_in_ch, int n_out_ch);

int dsee_pick_level(int channels, int vbr, int content_hz, int src_hz,
                    int codec, unsigned energy, int bit_override, int *level);
