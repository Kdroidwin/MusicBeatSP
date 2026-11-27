#ifndef DSEE_KERN_H
#define DSEE_KERN_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#ifdef DSEE_BUILDING
#define DSEE_API __attribute__((visibility("default")))
#else
#define DSEE_API __attribute__((visibility("default")))
#endif

typedef struct {
    int f76;
    int f80;
    int upmode;
    int src;
    int dst;
    int step_src;
    int step_dst;
    int n12;
    int f18992;
    int nblk;
    int off_up;
    int off_low;
    int channels;
    int nch_gate;
    int st1;
    int active;
    int rs_in;
    int rs_out;
    int rate;
    float gain_a, gain_b, gain_c, gain_e;
} DseeCfg;

typedef struct {
    int mode;
    int channels;
    int codec;
    int input_fs;
    int output_fs;
    int input_bits;
    int output_bits;
    int vbr;
    int abr;

    int sb;
    int head;
    int dcr;
    int gain;
    int offset;
} DseeSource;

DSEE_API int dsee_mem_size(void);

DSEE_API int dsee_mode(int enable, int device_rate, int codec);

DSEE_API int dsee_open_source(uint8_t *mem, int mem_size,
                              const DseeSource *s, DseeCfg *out);

DSEE_API int dsee_heq_delays(int rate, int *delay );

DSEE_API int dsee_heq_gain(int bits, float *gain);

DSEE_API int dsee_process_i32(
    uint8_t *mem, float *ring0, float *ring1, float *hist0, float *hist1,
    int *lag, int *hidx, const int *delay, double gain, const int32_t *pcm,
    int hops, int n_in, int n_out, int n_in_ch, int n_out_ch, float *out);

/** Same DSP as dsee_process_i32, with caller-owned scratch to avoid audio-thread heap allocation. */
DSEE_API int dsee_process_i32_with_scratch(
    uint8_t *mem, float *ring0, float *ring1, float *hist0, float *hist1,
    int *lag, int *hidx, const int *delay, double gain, const int32_t *pcm,
    int hops, int n_in, int n_out, int n_in_ch, int n_out_ch, float *out,
    int32_t *heq_scratch, float *frame_scratch);

/** 0 = auto; 8..16 force F76 level (16 = off). */
DSEE_API void dsee_set_level_override(int level);
/** 1 = HEQ on (default); 0 = bypass HEQ. */
DSEE_API void dsee_set_heq_enabled(int enabled);
/** 0 = auto; 1 = force F80 off; 2/4 = force F80 on. */
DSEE_API void dsee_set_f80_override(int up);

#ifdef __cplusplus
}
#endif
#endif
