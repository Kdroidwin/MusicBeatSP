#include "biquad_df1.h"
#include "ext80.h"

#include <math.h>
#include <string.h>
#ifndef _OPENMP
#include <pthread.h>
#endif

#if defined(__AVX__) && defined(__FMA__)
#include <immintrin.h>
#define DSEE_UP_AVX 1
#else
#define DSEE_UP_AVX 0
#endif

#define EXPORT __attribute__((visibility("default")))

typedef struct {
    int m, u0, u1, pair;
} Item;

typedef struct {
    Item it[96];
    int n;
} Phase;

static Phase g_plan[17][17];
static int g_ready[17][17];
#ifndef _OPENMP
static pthread_mutex_t g_plan_mutex = PTHREAD_MUTEX_INITIALIZER;
#endif

static void build_plan(int S, int N, Phase *ph)
{
    int p, u, m, a, b;
    for (p = 0; p < S; p++) {
        int us[256][4];
        int nu[256];
        memset(nu, 0, sizeof nu);
        for (u = -N; u <= N; u++) {
            int mm = p - S * u;
            if (mm < 0)
                mm = -mm;
            if (mm > S * N || mm >= 256)
                continue;
            if (nu[mm] < 4)
                us[mm][nu[mm]++] = u;
        }
        ph[p].n = 0;
        for (m = 0; m <= S * N; m++) {
            int n = nu[m];
            int tmp[4];
            if (n <= 0)
                continue;
            for (a = 0; a < n; a++)
                tmp[a] = us[m][a];
            for (a = 0; a < n; a++) {
                for (b = a + 1; b < n; b++) {
                    int aa = tmp[a] < 0 ? -tmp[a] : tmp[a];
                    int bb = tmp[b] < 0 ? -tmp[b] : tmp[b];
                    if (bb < aa || (bb == aa && tmp[b] < tmp[a])) {
                        int s = tmp[a];
                        tmp[a] = tmp[b];
                        tmp[b] = s;
                    }
                }
            }
            a = 0;
            while (a + 1 < n && ph[p].n < 96) {
                Item *it = &ph[p].it[ph[p].n++];
                it->m = m;
                it->u0 = tmp[a];
                it->u1 = tmp[a + 1];
                it->pair = 1;
                a += 2;
            }
            if (a < n && ph[p].n < 96) {
                Item *it = &ph[p].it[ph[p].n++];
                it->m = m;
                it->u0 = tmp[a];
                it->pair = 0;
            }
        }
    }
}

static const Phase *plan_for(int S, int N)
{
    /* OpenMP: two slots may enter here together — build must be serialized. */
    if (!g_ready[S][N]) {
#ifdef _OPENMP
#pragma omp critical(dsee_up_plan)
#else
        pthread_mutex_lock(&g_plan_mutex);
#endif
        {
            if (!g_ready[S][N]) {
                build_plan(S, N, g_plan[S]);
                g_ready[S][N] = 1;
            }
        }
#ifndef _OPENMP
        pthread_mutex_unlock(&g_plan_mutex);
#endif
    }
    return g_plan[S];
}

/*
 * up_low: Ext80 MAC ≡ double fold for binary32 audio/FIR (probed + golden).
 * Pair: RN((x0+x1)*h [+acc]); fuse: RN(h0*x0+h1*x1 [+acc]); lone: fmaf.
 */
static void up_low(const float *h, int N, int S, const float *x, float *y,
                   int accumulate, int niter)
{
    const Phase *ph = plan_for(S, N);
    float Sf = (float)S;
    int t, p, q;
    for (t = 0; t < niter; t++) {
        int k = N + t;
        for (p = 0; p < S; p++) {
            float acc = 0.f;
            int first = 1;
            q = 0;
            while (q < ph[p].n) {
                const Item *it = &ph[p].it[q];
                int step = 1;
                if (it->pair) {
                    double term = ((double)x[k + it->u0] + (double)x[k + it->u1]) *
                                  (double)h[it->m];
                    if (!first)
                        term += (double)acc;
                    acc = (float)term;
                    first = 0;
                } else if (q + 1 < ph[p].n && !ph[p].it[q + 1].pair) {
                    const Item *it2 = &ph[p].it[q + 1];
                    double term = (double)h[it->m] * (double)x[k + it->u0] +
                                  (double)h[it2->m] * (double)x[k + it2->u0];
                    if (!first)
                        term += (double)acc;
                    acc = (float)term;
                    first = 0;
                    step = 2;
                } else {
                    float hm = h[it->m];
                    float xv = x[k + it->u0];
                    if (first) {
                        acc = hm * xv;
                        first = 0;
                    } else {
                        acc = fmaf(hm, xv, acc);
                    }
                }
                q += step;
            }
            {
                int i = t * S + p;
                float scaled = Sf * acc;
                y[i] = accumulate ? (y[i] + scaled) : scaled;
            }
        }
    }
}

/*
 * up_high math (bit-exact with soft Ext80 for normal-range audio/FIR):
 *   Each term is RN_f32(h*x) or RN_f32(h*x + acc).
 *   For binary32 h,x: h*x is exact in ≤48 significand bits, so
 *   Ext80 mul+to_f32 ≡ IEEE f32 mul, and Ext80 mul+add+to_f32 ≡ fmaf,
 *   except subnormal edge cases (not hit by DSEE PCM/coeff paths; gated by
 *   golden WAV). Final mix: accumulate∈{0,1}, S∈{8,16} power-of-two ⇒
 *   RN(acc_flag*y + S*acc) ≡ (accumulate ? y : 0) + (S * acc) in f32.
 */
static void up_high(const float *h, int N, int S, const float *x, float *y,
                    int accumulate, int niter)
{
    float Sf = (float)S;
    int t, r, q;
    for (t = 0; t < niter; t++) {
        int k = N + t;
        float acc[16];
        float xa = x[k];
        float xb = x[k + 1];
#if DSEE_UP_AVX
        if (S == 8 || S == 16) {
            __m256 vxa = _mm256_set1_ps(xa);
            for (r = 0; r < S; r += 8)
                _mm256_storeu_ps(acc + r,
                                 _mm256_mul_ps(_mm256_loadu_ps(h + r), vxa));
            for (r = S - 1; r >= 0; r--)
                acc[r] = fmaf(h[S - r], xb, acc[r]);
            for (q = 1; q < N; q++) {
                float aq = x[k - q];
                float bq = x[k + 1 + q];
                __m256 vaq = _mm256_set1_ps(aq);
                for (r = 0; r < S; r += 8) {
                    __m256 a = _mm256_loadu_ps(acc + r);
                    a = _mm256_fmadd_ps(_mm256_loadu_ps(h + S * q + r), vaq, a);
                    _mm256_storeu_ps(acc + r, a);
                }
                for (r = S - 1; r >= 0; r--)
                    acc[r] = fmaf(h[S * q + S - r], bq, acc[r]);
            }
            acc[0] = fmaf(h[S * N], x[k - N], acc[0]);
            {
                __m256 vs = _mm256_set1_ps(Sf);
                for (r = 0; r < S; r += 8) {
                    __m256 sc = _mm256_mul_ps(_mm256_loadu_ps(acc + r), vs);
                    if (accumulate) {
                        __m256 yy = _mm256_loadu_ps(y + t * S + r);
                        sc = _mm256_add_ps(yy, sc);
                    }
                    _mm256_storeu_ps(y + t * S + r, sc);
                }
            }
            continue;
        }
#endif
        for (r = 0; r < S; r++)
            acc[r] = h[r] * xa;
        for (r = S - 1; r >= 0; r--)
            acc[r] = fmaf(h[S - r], xb, acc[r]);
        for (q = 1; q < N; q++) {
            float aq = x[k - q];
            float bq = x[k + 1 + q];
            for (r = 0; r < S; r++)
                acc[r] = fmaf(h[S * q + r], aq, acc[r]);
            for (r = S - 1; r >= 0; r--)
                acc[r] = fmaf(h[S * q + S - r], bq, acc[r]);
        }
        acc[0] = fmaf(h[S * N], x[k - N], acc[0]);
        for (r = 0; r < S; r++) {
            int i = t * S + r;
            float scaled = Sf * acc[r];
            y[i] = accumulate ? (y[i] + scaled) : scaled;
        }
    }
}

EXPORT void dsee_upsample(const float *h, int N, int S, const float *x,
                          float *y, int accumulate, int niter)
{
    if (S != 2 && S != 4 && S != 8 && S != 16)
        return;
    if (niter <= 0)
        niter = (S <= 4) ? 128 : 32;
    if (S > 4)
        up_high(h, N, S, x, y, accumulate, niter);
    else
        up_low(h, N, S, x, y, accumulate, niter);
}

EXPORT void dsee_biquad(const float *coef, float gain, float *buf,
                        const float *src, float *st, int n)
{
    Ext80 ec0, ec1, ec2, ec3;
    dsee_biquad_apply_gain(buf, src, gain, n);
    /* coef[] are f32; from_f32 ≡ from_f64((double)coef). */
    ec0 = ext80_from_f32(coef[0]);
    ec1 = ext80_from_f32(coef[1]);
    ec2 = ext80_from_f32(coef[2]);
    ec3 = ext80_from_f32(coef[3]);
    dsee_biquad_df1_section(buf, n, ec0, ec1, ec2, ec3, &st[0], &st[1]);
}

EXPORT void dsee_fir21(const float *c, int stride, const float *in, float *out)
{
    int n, k, t;
    if (stride <= 0)
        stride = 1;
    n = 128 / stride;
    for (k = 0; k < n; k++) {
        int r = k * stride + 10;
        float acc = c[0] * in[r];
        for (t = 1; t < 11; t++) {
            double term = ((double)in[r - t] + (double)in[r + t]) * (double)c[t] +
                          (double)acc;
            acc = (float)term;
        }
        out[k] = acc;
    }
}
