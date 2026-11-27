#pragma once
/*
 * Shared DSX context layout (byte offsets).
 * Context base = mem + DSX_CTX. Per-channel stride = DSX_SLOT.
 */
#define DSX_CTX 84
#define DSX_SLOT 2164

/* mem+4: channel / slot count; mem+8: level; mem+32: upsample gate; mem+76/80: enables */
#define DSX_OFF_NSLOTS 4
#define DSX_OFF_LEVEL 8
#define DSX_OFF_GATE 32
#define DSX_OFF_F76_ENABLE 76
#define DSX_OFF_F80_ENABLE 80

/*
 * Path mode at ctx+19324 (written by open_source from bitstream).
 *   FULL  — run band / upsample synthesis
 *   COPY  — F76 remaps without additive mix; F80 skips 2nd upsample stage
 *   BYPASS — skip F76 band body
 */
#define DSX_OFF_PATH_MODE 19324
enum {
    DSX_PATH_FULL = 0,
    DSX_PATH_COPY = 1,
    DSX_PATH_BYPASS = 2
};

/* ---- F76 (offsets from DSX_CTX) ---- */
#define F76_OFF_LEVEL_MIRROR 4440
#define F76_OFF_BQ_ANALYSIS 4344
#define F76_OFF_BQ_SIDE 4328
#define F76_OFF_BQ_POST_ENV 4360
#define F76_OFF_ENERGY_COEF_A 4384
#define F76_OFF_ENERGY_COEF_B 4376
#define F76_OFF_ENV_COEF 18660
#define F76_OFF_ENV_STATE 18744
#define F76_OFF_ENV_LOUDNESS 19012 /* f32 */
#define F76_OFF_ENV_BUF0 208
#define F76_OFF_ENV_BUF1 2372
#define F76_OFF_GAIN_META 4388 /* shared; read by gain8 + synth */
#define F76_OFF_GAIN_FLAG 4444
#define F76_OFF_SYNTH_COEF_PTR 4380 /* u32 → FIR coef block */

/* Per-slot (from ctx + ch * DSX_SLOT) */
#define F76_SLOT_WORK 108 /* mix / writeback workspace */
#define F76_SLOT_PTRS 944 /* gather src/dst pointer block */
#define F76_SLOT_PTRS2 948

/* Relative to F76_SLOT_PTRS (u) */
#define F76_REL_ST_ANAL 880 /* u - 880 */
#define F76_REL_ST_SIDE 944 /* u - 944; also used with ptrs2 */
#define F76_REL_ENERGY_OUT 48 /* u - 48 */
#define F76_REL_BQ_DST_SIDE 736 /* u - 736 */
#define F76_REL_ST_POST 912 /* u5 - 912 */

/* Relative to F76_SLOT_PTRS2 (u2) */
#define F76_REL_BQ_SRC_POST 740 /* u2 - 740 */
#define F76_REL_ST_POST_ENV 852 /* u2 - 852 */

/* Relative to slot base (u3 = ctx + ch*SLOT) */
#define F76_SLOT_HIST_TAIL 856
#define F76_SLOT_ENERGY_OUT_B 736

/* Relative to F76_SLOT_WORK (v28) */
#define F76_WORK_BUF_PTR 836
#define F76_WORK_HIST_A 840

/* ---- F80 (offsets from DSX_CTX) ---- */
#define F80_OFF_UP_FACTOR 18892
#define F80_OFF_N8 18940
#define F80_OFF_N12 18904
#define F80_OFF_SKIP_UP1 18992
#define F80_OFF_OFF_UP 18996
#define F80_OFF_OFF_LOW 19000
#define F80_OFF_UP1_COEF 18936
#define F80_OFF_BQ_COEF 18944
#define F80_OFF_BQ_GAIN 18948
#define F80_OFF_BQ_NSEC 18956
#define F80_OFF_ENERGY4_COEF 18920
#define F80_OFF_UP2_HDR0 18960 /* + 8*ch */
#define F80_OFF_HIST_FLAG 18900

/* Per-slot from ctx (F80) */
#define F80_SLOT_STAGE1 956
#define F80_SLOT_STAGE2 952
