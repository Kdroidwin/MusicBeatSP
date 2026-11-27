#include "band_frame.h"
#include "dsx.h"
#include "mem_io.h"

/*
 * One hop, one pipeline. Channel slots are disjoint except envelope_ms and
 * band_gain8/synth (shared meta/coefs) — those stay serial.
 *
 * OpenMP: only when nslots > 2. Stereo (2) pays more in fork/barrier per hop
 * than it gains; measured slower than serial after x87/biquad opts.
 *
 * n_in_ch / n_out_ch: samples per channel this hop.
 * wb_f32_off: writeback start index into the work buffer (normally 0).
 */
__attribute__((visibility("default"))) void dsee_f76_frame(uint8_t *mem, float *pcm, int n_in_ch,
                                          int n_out_ch, int wb_f32_off)
{
    uint8_t *ctx = mem + DSX_CTX;
    int nslots = (int)*(uint32_t *)(mem + DSX_OFF_NSLOTS);
    int ch;
    int path = (int)*(uint32_t *)(ctx + DSX_OFF_PATH_MODE);
    uint32_t cbase = f76_cbase();
    int omp_ch = nslots > 2;

    *(uint32_t *)(ctx + F76_OFF_LEVEL_MIRROR) =
        *(uint32_t *)(mem + DSX_OFF_LEVEL);

#ifdef _OPENMP
#pragma omp parallel if (omp_ch)
#endif
    {
#ifdef _OPENMP
#pragma omp for
#endif
        for (ch = 0; ch < nslots; ch++) {
            uint32_t u = f76_slot_ptrs(cbase, ch);
            dsx_gather(pcm, dsx_F(mem, *(uint32_t *)(mem + u)), n_in_ch, ch,
                       nslots);
            dsx_clear_f(dsx_F(mem, *(uint32_t *)(mem + u) + 4 * n_in_ch),
                        128 - n_in_ch);
        }

        if (path != DSX_PATH_BYPASS) {
#ifdef _OPENMP
#pragma omp for
#endif
            for (ch = 0; ch < nslots; ch++) {
                uint32_t u = f76_slot_ptrs(cbase, ch);
                dsx_biquad_cascade(
                    mem, cbase + F76_OFF_BQ_ANALYSIS,
                    dsx_F(mem, *(uint32_t *)(mem + u)),
                    dsx_F(mem, *(uint32_t *)(mem + u + 4) + 832),
                    dsx_F(mem, u - F76_REL_ST_ANAL));
                dsx_energy_tap22(mem, *(uint32_t *)(mem + cbase + F76_OFF_ENERGY_COEF_A),
                                 *(uint32_t *)(mem + u + 4) + 672,
                                 u - F76_REL_ENERGY_OUT, 4);
            }

#ifdef _OPENMP
#pragma omp for
#endif
            for (ch = 0; ch < nslots; ch++) {
                uint32_t u = f76_slot_ptrs(cbase, ch);
                dsx_biquad_cascade(mem, cbase + F76_OFF_BQ_SIDE,
                                   dsx_F(mem, *(uint32_t *)(mem + u)),
                                   dsx_F(mem, u - F76_REL_BQ_DST_SIDE),
                                   dsx_F(mem, u - F76_REL_ST_SIDE));
            }

#ifdef _OPENMP
#pragma omp single
#endif
            {
                uint32_t b0 = cbase + F76_OFF_ENV_BUF0;
                uint32_t b1 =
                    (nslots == 1) ? b0 : (cbase + F76_OFF_ENV_BUF1);
                uint32_t src_offs[2], dst_offs[2];
                int i;
                for (i = 0; i < nslots && i < 2; i++) {
                    src_offs[i] = (i == 0) ? b0 : b1;
                    dst_offs[i] = (i == 0) ? b0 : b1;
                }
                dsx_envelope_ms(
                    mem, nslots, n_in_ch, cbase + F76_OFF_ENV_COEF,
                    cbase + F76_OFF_ENV_STATE,
                    *(float *)(mem + cbase + F76_OFF_ENV_LOUDNESS), src_offs,
                    dst_offs);
            }

#ifdef _OPENMP
#pragma omp for
#endif
            for (ch = 0; ch < nslots; ch++) {
                uint32_t u2 = f76_slot_ptrs2(cbase, ch);
                dsx_biquad_cascade(
                    mem, cbase + F76_OFF_BQ_POST_ENV,
                    dsx_F(mem, u2 - F76_REL_BQ_SRC_POST),
                    dsx_F(mem, *(uint32_t *)(mem + u2) + 160),
                    dsx_F(mem, u2 - F76_REL_ST_POST_ENV));
            }

#ifdef _OPENMP
#pragma omp for
#endif
            for (ch = 0; ch < nslots; ch++) {
                uint32_t slot = f76_slot_base(cbase, ch);
                uint32_t hist = slot + F76_SLOT_HIST_TAIL;
                int n;
                for (n = 0; n < 7; n++) {
                    dsx_copy_f(dsx_F(mem, hist + 20), dsx_F(mem, hist), 5);
                    hist -= 20;
                }
                dsx_energy_tap22(
                    mem, *(uint32_t *)(mem + cbase + F76_OFF_ENERGY_COEF_B),
                    *(uint32_t *)(mem + slot + F76_SLOT_PTRS2),
                    slot + F76_SLOT_ENERGY_OUT_B, 5);
            }
        }
    }

    /* Shared meta/coefs — serial, still before the post-mix biquad. */
    if (path != DSX_PATH_BYPASS) {
        for (ch = 0; ch < nslots; ch++) {
            uint32_t u2 = f76_slot_ptrs2(cbase, ch);
            dsx_band_gain8(mem, f76_slot_base(cbase, ch),
                           cbase + F76_OFF_GAIN_META, cbase + F76_OFF_ENERGY_COEF_B,
                           (int)*(uint32_t *)(mem + cbase + F76_OFF_GAIN_FLAG),
                           cbase);
            dsx_synth_fir22(mem, *(uint32_t *)(mem + cbase + F76_OFF_SYNTH_COEF_PTR),
                            cbase + F76_OFF_GAIN_META,
                            *(uint32_t *)(mem + u2),
                            *(uint32_t *)(mem + u2 - 4));
        }
    }

#ifdef _OPENMP
#pragma omp parallel for if (omp_ch)
#endif
    for (ch = 0; ch < nslots; ch++) {
        uint32_t u = f76_slot_ptrs(cbase, ch);
        dsx_biquad_cascade(mem, cbase + F76_OFF_BQ_ANALYSIS,
                           dsx_F(mem, *(uint32_t *)(mem + u)),
                           dsx_F(mem, *(uint32_t *)(mem + u)),
                           dsx_F(mem, u - F76_REL_ST_POST));
    }

#ifdef _OPENMP
#pragma omp parallel for if (omp_ch)
#endif
    for (ch = 0; ch < nslots; ch++) {
        uint32_t work = f76_slot_work(cbase, ch);
        uint32_t buf = *(uint32_t *)(mem + work + F76_WORK_BUF_PTR);
        int i;
        if (path == DSX_PATH_COPY) {
            float *d = dsx_F(mem, buf + 8);
            float *s = dsx_F(mem, work);
            for (i = 0; i < 16; i++) {
                d[8 * i - 2] = s[8 * i - 1];
                d[8 * i - 1] = s[8 * i + 0];
                d[8 * i + 0] = s[8 * i + 1];
                d[8 * i + 1] = s[8 * i + 2];
                d[8 * i + 2] = s[8 * i + 3];
                d[8 * i + 3] = s[8 * i + 4];
                d[8 * i + 4] = s[8 * i + 5];
                d[8 * i + 5] = s[8 * i + 6];
            }
        } else if (path != DSX_PATH_BYPASS) {
            float *d = dsx_F(mem, buf + 8);
            float *s = dsx_F(mem, work);
            for (i = 0; i < 16; i++) {
                d[8 * i - 2] = x87_add(s[8 * i - 1], d[8 * i - 2]);
                d[8 * i - 1] = x87_add(s[8 * i + 0], d[8 * i - 1]);
                d[8 * i + 0] = x87_add(s[8 * i + 1], d[8 * i + 0]);
                d[8 * i + 1] = x87_add(s[8 * i + 2], d[8 * i + 1]);
                d[8 * i + 2] = x87_add(s[8 * i + 3], d[8 * i + 2]);
                d[8 * i + 3] = x87_add(s[8 * i + 4], d[8 * i + 3]);
                d[8 * i + 4] = x87_add(s[8 * i + 5], d[8 * i + 4]);
                d[8 * i + 5] = x87_add(s[8 * i + 6], d[8 * i + 5]);
            }
        }

        dsx_writeback(dsx_F(mem, buf + 4 * wb_f32_off), pcm, n_out_ch,
                      (int)*(uint32_t *)(mem + DSX_OFF_GATE), ch, nslots);

        dsx_copy_f(dsx_F(mem, work - 4), dsx_F(mem, work + 508), 26);
        {
            uint32_t hist = *(uint32_t *)(mem + work + F76_WORK_HIST_A);
            dsx_copy_f(dsx_F(mem, hist), dsx_F(mem, hist + 512), 40);
            dsx_copy_f(dsx_F(mem, hist + 672), dsx_F(mem, hist + 1184), 40);
        }
    }
}
