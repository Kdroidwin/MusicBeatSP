#pragma once
#include "ext80.h"

/*
 * Soft Ext80 direct-form I biquad — shared by F76 cascade and F80.
 * Implemented in ext80.c (same TU as Ext80 ops) so mul/add/from/to inline.
 */
void dsee_biquad_apply_gain(float *dst, const float *src, float gain, int n);
void dsee_biquad_df1_section(float *buf, int n, Ext80 ec0, Ext80 ec1, Ext80 ec2,
                             Ext80 ec3, float *w1, float *w2);
