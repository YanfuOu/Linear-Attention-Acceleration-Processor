#include <stdio.h>
#include <stdint.h>

// Base address must match OuterProductUnitParams(address = 0x6000) in
// OuterProductUnit.scala
#define BASE 0x6000
#define LANES 16
#define REG32(off) (*(volatile uint32_t *)(BASE + (off)))

// Register map
#define K_VEC     0x000 // RW, 16 x 32-bit
#define V_VEC     0x040 // RW, 16 x 32-bit
#define Q_VEC     0x080 // RW, 16 x 32-bit
#define DECAY_VEC 0x0C0 // RW, 16 x 32-bit, unsigned Q0.32 fraction
#define B_VEC     0x100 // RW, 16 x 32-bit, gate for k
#define W_VEC     0x140 // RW, 16 x 32-bit, gate for v
#define CMD       0x180 // W,  8-bit
#define BUSY      0x184 // R,  1-bit
#define RETIRED   0x188 // R,  32-bit
#define OUT_VEC   0x18C // R,  16 x 32-bit

#define CMD_NOP    0
#define CMD_RESET  1
#define CMD_UPDATE 2
#define CMD_QUERY  3

static void write_vec(uint32_t base_off, const int32_t *v) {
  for (int i = 0; i < LANES; i++) {
    REG32(base_off + 4 * i) = (uint32_t)v[i];
  }
}

static void do_cmd(uint8_t cmd) {
  REG32(CMD) = cmd;
  while (REG32(BUSY)) ;
}

// Bit-exact replicas of OuterProductUnit.scala's two truncating primitives,
// so the expected value here is computed the same way the hardware computes
// it, not approximated.
static int32_t mul_trunc(int32_t a, int32_t b) {
  int64_t p = (int64_t)a * (int64_t)b;
  return (int32_t)(uint32_t)p; // low 32 bits of the product
}

static int32_t decay_mul(uint32_t alpha_raw, int32_t s) {
  int64_t p = (int64_t)(uint64_t)alpha_raw * (int64_t)s; // alpha_raw is nonnegative
  int64_t shifted = p >> 32; // arithmetic shift, matches Chisel SInt >>
  return (int32_t)(uint32_t)shifted;
}

int main(void) {
  const int32_t ones[LANES] = {1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1};
  const int32_t zeros[LANES] = {0};

  // --- Step A: seed S1 = k1 (x) v1 with decay=0 (so S1 doesn't depend on
  // whatever was in S before -- RESET isn't even load-bearing here, but we
  // call it anyway for a clean, deterministic starting point). b1=w1=1
  // everywhere, so e1=k1 and z1=v1 (gates are no-ops) -- this step only
  // exercises the outer-product half of the recurrence, same as the
  // original (pre-delta-rule) version of this test. k1/v1 are chosen even
  // so step B's exact-0.5 decay halves them with no rounding loss.
  int32_t k1[LANES], v1[LANES];
  for (int i = 0; i < LANES; i++) { k1[i] = 2 * (i + 1); v1[i] = 2 * (i + 1); }

  do_cmd(CMD_RESET);
  write_vec(DECAY_VEC, zeros);
  write_vec(B_VEC, ones);
  write_vec(W_VEC, ones);
  write_vec(K_VEC, k1);
  write_vec(V_VEC, v1);
  do_cmd(CMD_UPDATE); // S1[i][j] = k1[i]*v1[j]

  // --- Step B: the real test -- a second UPDATE with a nonzero decay, so
  // this actually exercises Sbar_t = D_t S_{t-1} and the correction term
  // sigma_t = z_t - Sbar_t^T e_t, not just the degenerate decay=0 case.
  // decay2 = 2^31 represents exactly 0.5 in Q0.32, so halving S1 (whose
  // entries are all divisible by 4, by construction above) is exact --
  // no fixed-point rounding ambiguity to worry about in the check below.
  // b2=w2=1 again (e2=k2, z2=v2) -- the gate multiply itself is the same
  // mul_trunc primitive already exercised by the outer product in step A,
  // so leaving the gates as a no-op here is a deliberate simplification,
  // not a gap in what matters: it isolates the new behavior (decay +
  // correction) this step is meant to test.
  const uint32_t decay2 = 0x80000000u; // 0.5 exactly
  int32_t k2[LANES], v2[LANES];
  for (int i = 0; i < LANES; i++) { k2[i] = 1; v2[i] = 10 * (i + 1); }

  for (int i = 0; i < LANES; i++) REG32(DECAY_VEC + 4 * i) = decay2;
  write_vec(B_VEC, ones);
  write_vec(W_VEC, ones);
  write_vec(K_VEC, k2);
  write_vec(V_VEC, v2);
  do_cmd(CMD_UPDATE);

  // Query with a one-hot q (1 at index 0) to read back row 0 of S2 exactly:
  // o[j] = sum_i S2[i][j] * q[i] = S2[0][j].
  int32_t q2[LANES] = {0};
  q2[0] = 1;
  write_vec(Q_VEC, q2);
  do_cmd(CMD_QUERY);

  // Replicate the hardware's math in C to get the expected row 0 of S2.
  int32_t sbar_row0[LANES], e2[LANES];
  for (int i = 0; i < LANES; i++) e2[i] = mul_trunc(1 /*b2*/, k2[i]);
  for (int j = 0; j < LANES; j++) sbar_row0[j] = decay_mul(decay2, mul_trunc(k1[0], v1[j]));

  int32_t dot[LANES];
  for (int j = 0; j < LANES; j++) {
    int64_t acc = 0; // accumulate full sbar column j against e2, then truncate once
    for (int i = 0; i < LANES; i++) {
      int32_t sbar_ij = decay_mul(decay2, mul_trunc(k1[i], v1[j]));
      acc += (int64_t)(uint32_t)mul_trunc(sbar_ij, e2[i]);
    }
    dot[j] = (int32_t)(uint32_t)acc;
  }

  int32_t z2[LANES];
  for (int j = 0; j < LANES; j++) z2[j] = mul_trunc(1 /*w2*/, v2[j]);

  int32_t sigma[LANES];
  for (int j = 0; j < LANES; j++) sigma[j] = (int32_t)((uint32_t)z2[j] - (uint32_t)dot[j]);

  int32_t expected_row0[LANES];
  for (int j = 0; j < LANES; j++) {
    expected_row0[j] = (int32_t)((uint32_t)sbar_row0[j] + (uint32_t)mul_trunc(sigma[j], k2[0]));
  }

  int pass = 1;
  for (int j = 0; j < LANES; j++) {
    int32_t got = (int32_t)REG32(OUT_VEC + 4 * j);
    int ok = got == expected_row0[j];
    pass &= ok;
    printf("out[%2d] = %d (expected %d) %s\n", j, got, expected_row0[j], ok ? "OK" : "FAIL");
  }

  printf("retired = %u\n", REG32(RETIRED));
  printf(pass ? "PASS\n" : "FAIL\n");
  return pass ? 0 : 1;
}
