#include "ext80.h"

#include <math.h>
#include <string.h>

#define P 64
#define EMIN (-16382)
#define EMAX 16383
#define NL 8

typedef struct {
    uint64_t w[NL];
} Big;

static int bitlen64(uint64_t x)
{
    if (x == 0)
        return 0;
    return 64 - __builtin_clzll(x);
}

Ext80 ext80_zero(int sign)
{
    Ext80 z;
    z.sign = sign ? 1 : 0;
    z.mant = 0;
    z.exp = EMIN;
    return z;
}

static int is_zero(Ext80 v)
{
    return v.mant == 0;
}

static void finish_range(int sign, uint64_t mant, int exp, Ext80 *o)
{
    if (mant == 0) {
        *o = ext80_zero(sign);
        return;
    }
    if (exp > EMAX) {
        o->sign = sign;
        o->mant = 0x8000000000000000ull;
        o->exp = 0x7fff;
        return;
    }
    if (exp < EMIN) {
        int shift = EMIN - exp;
        int ovf = 0;
        if (shift > P + 1) {
            *o = ext80_zero(sign);
            return;
        }
        if (shift > 0) {
            uint64_t keep, rem, half;
            if (shift >= 64) {
                keep = 0;
                rem = mant;
                half = 1ull << 63;
                if (shift > 64) {
                    *o = ext80_zero(sign);
                    return;
                }
            } else {
                keep = mant >> shift;
                rem = mant & ((1ull << shift) - 1ull);
                half = 1ull << (shift - 1);
            }
            if (rem > half || (rem == half && (keep & 1ull))) {
                keep += 1ull;
                if (keep == 0) {
                    ovf = 1;
                    keep = 1ull << 63;
                }
            }
            mant = keep;
        }
        exp = EMIN;
        if (ovf) {
            mant >>= 1;
            exp += 1;
        }
        if (mant == 0) {
            *o = ext80_zero(sign);
            return;
        }
    }
    o->sign = sign;
    o->mant = mant;
    o->exp = exp;
}

static void norm_u64(int sign, uint64_t mant, int exp, Ext80 *o)
{
    int bl;
    if (mant == 0) {
        *o = ext80_zero(sign);
        return;
    }
    bl = bitlen64(mant);
    if (bl < P) {
        int sh = P - bl;
        mant <<= sh;
        exp -= sh;
    }
    finish_range(sign, mant, exp, o);
}

static int big_bitlen(const Big *a)
{
    int i;
    for (i = NL - 1; i >= 0; i--) {
        if (a->w[i])
            return i * 64 + bitlen64(a->w[i]);
    }
    return 0;
}

static uint64_t adc(uint64_t *dst, uint64_t v, uint64_t c)
{
    uint64_t s = *dst + v;
    uint64_t c1 = s < *dst;
    uint64_t s2 = s + c;
    uint64_t c2 = s2 < s;
    *dst = s2;
    return c1 | c2;
}

static void big_clear(Big *a)
{
    memset(a->w, 0, sizeof a->w);
}

static void big_add_shift(Big *a, uint64_t mant, int sh)
{
    int li, b, i;
    uint64_t lo, hi, c;
    if (mant == 0 || sh < 0 || sh >= NL * 64)
        return;
    li = sh >> 6;
    b = sh & 63;
    if (b == 0) {
        lo = mant;
        hi = 0;
    } else {
        lo = mant << b;
        hi = mant >> (64 - b);
    }
    c = 0;
    if (li < NL)
        c = adc(&a->w[li], lo, 0);
    if (li + 1 < NL)
        c = adc(&a->w[li + 1], hi, c);
    for (i = li + 2; c && i < NL; i++)
        c = adc(&a->w[i], 0, c);
}

static uint64_t big_round_shr(const Big *a, int shift, int *ovf)
{
    int li, b, half, sticky, j, rem_gt, rem_eq;
    uint64_t keep, low, halfbit, hi;
    *ovf = 0;
    if (shift <= 0)
        return a->w[0];
    li = shift >> 6;
    b = shift & 63;
    rem_gt = 0;
    rem_eq = 0;
    if (b == 0) {
        keep = (li < NL) ? a->w[li] : 0;
        if (li > 0) {
            uint64_t top = a->w[li - 1];
            half = (int)((top >> 63) & 1ull);
            sticky = (top & ((1ull << 63) - 1ull)) != 0;
            for (j = 0; j < li - 1; j++)
                if (a->w[j])
                    sticky = 1;
            if (half && sticky)
                rem_gt = 1;
            else if (half)
                rem_eq = 1;
        }
    } else {
        low = (li < NL) ? a->w[li] : 0;
        hi = (li + 1 < NL) ? a->w[li + 1] : 0;
        keep = (low >> b) | (hi << (64 - b));
        halfbit = 1ull << (b - 1);
        low &= (1ull << b) - 1ull;
        half = (low & halfbit) != 0;
        sticky = (low & (halfbit - 1ull)) != 0;
        for (j = 0; j < li; j++)
            if (a->w[j])
                sticky = 1;
        if (half && sticky)
            rem_gt = 1;
        else if (half)
            rem_eq = 1;
    }
    if (rem_gt || (rem_eq && (keep & 1ull))) {
        keep += 1ull;
        if (keep == 0) {
            *ovf = 1;
            return 1ull << 63;
        }
    }
    return keep;
}

static void norm_big(int sign, const Big *mag, int exp, Ext80 *o)
{
    int bl = big_bitlen(mag);
    int shift, ovf;
    uint64_t mant;
    if (bl == 0) {
        *o = ext80_zero(sign);
        return;
    }
    if (bl > P) {
        shift = bl - P;
        ovf = 0;
        mant = big_round_shr(mag, shift, &ovf);
        exp += shift;
        if (ovf) {
            mant >>= 1;
            exp += 1;
        }
    } else if (bl < P) {
        mant = mag->w[0] << (P - bl);
        exp -= (P - bl);
    } else {
        mant = mag->w[0];
    }
    finish_range(sign, mant, exp, o);
}

/* Same rounding as norm_big for magnitudes that fit in 128 bits (mul + common add). */
static int bitlen_u128(unsigned __int128 x)
{
    if (x == 0)
        return 0;
    if ((uint64_t)(x >> 64))
        return 64 + bitlen64((uint64_t)(x >> 64));
    return bitlen64((uint64_t)x);
}

static void norm_u128(int sign, unsigned __int128 mag, int exp, Ext80 *o)
{
    int bl = bitlen_u128(mag);
    int shift;
    uint64_t mant;
    if (bl == 0) {
        *o = ext80_zero(sign);
        return;
    }
    if (bl > P) {
        unsigned __int128 half, rem, mask;
        uint64_t keep;
        int ovf = 0;
        shift = bl - P;
        keep = (uint64_t)(mag >> shift);
        mask = ((unsigned __int128)1 << shift) - 1;
        rem = mag & mask;
        half = (unsigned __int128)1 << (shift - 1);
        if (rem > half || (rem == half && (keep & 1ull))) {
            keep += 1ull;
            if (keep == 0) {
                ovf = 1;
                keep = 1ull << 63;
            }
        }
        mant = keep;
        exp += shift;
        if (ovf) {
            mant >>= 1;
            exp += 1;
        }
    } else if (bl < P) {
        mant = (uint64_t)mag << (P - bl);
        exp -= (P - bl);
    } else {
        mant = (uint64_t)mag;
    }
    finish_range(sign, mant, exp, o);
}

Ext80 ext80_from_f64(double x)
{
    int sign, e, bl;
    double m;
    uint64_t mi;
    Ext80 o;
    if (isnan(x)) {
        o.sign = 0;
        o.mant = 0xC000000000000000ull;
        o.exp = 0x7fff;
        return o;
    }
    if (x == 0.0)
        return ext80_zero(signbit(x) ? 1 : 0);
    if (isinf(x)) {
        o.sign = x < 0.0;
        o.mant = 0x8000000000000000ull;
        o.exp = 0x7fff;
        return o;
    }
    sign = signbit(x) ? 1 : 0;
    m = frexp(fabs(x), &e);
    mi = (uint64_t)(m * (double)(1ull << 53));
    bl = bitlen64(mi);
    if (bl < P && bl > 0) {
        mi <<= (P - bl);
        e = (e + 10) - (P - bl);
    } else {
        e = e + 10;
    }
    finish_range(sign, mi, e, &o);
    return o;
}

Ext80 ext80_from_f32(float x)
{
    uint32_t u;
    int sign, e;
    uint32_t frac;
    Ext80 o;
    memcpy(&u, &x, 4);
    sign = (int)(u >> 31);
    e = (int)((u >> 23) & 0xffu);
    frac = u & 0x7fffffu;
    if (e == 0 || e == 255)
        return ext80_from_f64((double)x);
    o.sign = sign;
    o.mant = ((uint64_t)(frac | 0x800000u)) << 40;
    o.exp = e - 127;
    return o;
}

Ext80 ext80_from_int(int v)
{
    Ext80 o;
    uint64_t m;
    int bl;
    if (v == 0)
        return ext80_zero(0);
    m = (uint64_t)(v < 0 ? -v : v);
    bl = bitlen64(m);
    if (bl < P)
        m <<= (P - bl);
    norm_u64(v < 0 ? 1 : 0, m, 63 - (P - bl), &o);
    return o;
}

float ext80_to_f32(Ext80 v)
{
    int shift = P - 24;
    int ovf = 0;
    uint64_t keep, rem, half;
    int e, be;
    uint32_t bits;
    float out;
    if (v.mant == 0)
        return v.sign ? -0.f : 0.f;
    keep = v.mant >> shift;
    rem = v.mant & ((1ull << shift) - 1ull);
    half = 1ull << (shift - 1);
    if (rem > half || (rem == half && (keep & 1ull))) {
        keep += 1ull;
        if (keep == 0) {
            ovf = 1;
            keep = 1ull << 63;
        }
    }
    e = v.exp;
    if (ovf) {
        keep >>= 1;
        e += 1;
    }
    if (keep == 0)
        return v.sign ? -0.f : 0.f;
    /* value = keep * 2^(e-23); pack IEEE for normals (probed ≡ ldexp path). */
    while (keep < (1ull << 23) && e > -150) {
        keep <<= 1;
        e--;
    }
    if (keep >= (1ull << 24)) {
        keep >>= 1;
        e++;
    }
    be = e + 127;
    if (be >= 255)
        return v.sign ? (float)(-INFINITY) : (float)INFINITY;
    if (be <= 0) {
        double r = ldexp((double)keep, e - 23);
        if (v.sign)
            r = -r;
        return (float)r;
    }
    bits = ((uint32_t)v.sign << 31) | ((uint32_t)be << 23) |
           ((uint32_t)keep & 0x7fffffu);
    memcpy(&out, &bits, 4);
    return out;
}

Ext80 ext80_add(Ext80 a, Ext80 b)
{
    Big mag;
    int e, da, db, sign;
    Ext80 o;
    if (is_zero(a) && is_zero(b))
        return ext80_zero((a.sign == 1 && b.sign == 1) ? 1 : 0);
    if (is_zero(a))
        return b;
    if (is_zero(b))
        return a;
    e = a.exp < b.exp ? a.exp : b.exp;
    da = a.exp - e;
    db = b.exp - e;
    if (da >= NL * 64)
        return a;
    if (db >= NL * 64)
        return b;
    /* Hot path: both aligned into 128 bits — identical to the Big path. */
    if (da < 64 && db < 64) {
        unsigned __int128 ua = (unsigned __int128)a.mant << da;
        unsigned __int128 ub = (unsigned __int128)b.mant << db;
        unsigned __int128 um;
        if (a.sign == b.sign) {
            um = ua + ub;
            sign = a.sign;
        } else if (ua > ub) {
            um = ua - ub;
            sign = a.sign;
        } else if (ub > ua) {
            um = ub - ua;
            sign = b.sign;
        } else {
            return ext80_zero(0);
        }
        norm_u128(sign, um, e, &o);
        return o;
    }
    big_clear(&mag);
    if (a.sign == b.sign) {
        big_add_shift(&mag, a.mant, da);
        big_add_shift(&mag, b.mant, db);
        sign = a.sign;
    } else if (da == db) {
        if (a.mant == b.mant)
            return ext80_zero(0);
        if (a.mant > b.mant) {
            big_add_shift(&mag, a.mant - b.mant, da);
            sign = a.sign;
        } else {
            big_add_shift(&mag, b.mant - a.mant, db);
            sign = b.sign;
        }
    } else if (da > db) {
        Big sb;
        big_add_shift(&mag, a.mant, da);
        big_clear(&sb);
        big_add_shift(&sb, b.mant, db);

        {
            uint64_t br = 0;
            int i;
            for (i = 0; i < NL; i++) {
                uint64_t bi = sb.w[i];
                uint64_t s = mag.w[i] - bi;
                uint64_t br1 = mag.w[i] < bi;
                uint64_t s2 = s - br;
                uint64_t br2 = s < br;
                mag.w[i] = s2;
                br = br1 | br2;
            }
        }
        sign = a.sign;
    } else {
        Big sa;
        big_clear(&sa);
        big_add_shift(&sa, a.mant, da);
        big_add_shift(&mag, b.mant, db);
        {
            uint64_t br = 0;
            int i;
            for (i = 0; i < NL; i++) {
                uint64_t bi = sa.w[i];
                uint64_t s = mag.w[i] - bi;
                uint64_t br1 = mag.w[i] < bi;
                uint64_t s2 = s - br;
                uint64_t br2 = s < br;
                mag.w[i] = s2;
                br = br1 | br2;
            }
        }
        sign = b.sign;
    }
    norm_big(sign, &mag, e, &o);
    return o;
}

Ext80 ext80_sub(Ext80 a, Ext80 b)
{
    b.sign = b.sign ? 0 : 1;
    return ext80_add(a, b);
}

Ext80 ext80_mul(Ext80 a, Ext80 b)
{
    unsigned __int128 p;
    int sign;
    Ext80 o;
    if (is_zero(a) || is_zero(b))
        return ext80_zero(a.sign ^ b.sign);
    sign = a.sign ^ b.sign;
    p = (unsigned __int128)a.mant * b.mant;
    norm_u128(sign, p, a.exp + b.exp - 63, &o);
    return o;
}

void dsee_biquad_apply_gain(float *dst, const float *src, float gain, int n)
{
    int i;
    for (i = 0; i < n; i++)
        dst[i] = (float)((double)src[i] * (double)gain);
}

/*
 * DF1 section: hoist from_f32(x1/x2) once per sample; Ext80 ops live in this
 * TU so the compiler can inline them into the loop.
 */
__attribute__((hot)) void dsee_biquad_df1_section(float *buf, int n, Ext80 ec0,
                                                  Ext80 ec1, Ext80 ec2,
                                                  Ext80 ec3, float *w1,
                                                  float *w2)
{
    float x1 = *w1, x2 = *w2;
    int i;
    for (i = 0; i < n; i++) {
        Ext80 ex1 = ext80_from_f32(x1);
        Ext80 ex2 = ext80_from_f32(x2);
        Ext80 w = ext80_sub(ext80_sub(ext80_from_f32(buf[i]), ext80_mul(ec2, ex1)),
                            ext80_mul(ec3, ex2));
        float wf = ext80_to_f32(w);
        Ext80 y;
        if ((double)wf > -1.0e-40 && (double)wf < 1.0e-40)
            wf = 0.f;
        y = ext80_add(ext80_add(ext80_mul(ec0, ex1), ext80_from_f32(wf)),
                      ext80_mul(ec1, ex2));
        buf[i] = ext80_to_f32(y);
        x2 = x1;
        x1 = wf;
    }
    *w1 = x1;
    *w2 = x2;
}
