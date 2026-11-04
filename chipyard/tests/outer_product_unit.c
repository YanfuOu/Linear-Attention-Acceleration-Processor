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
#define DECAY_VEC 0x0C0 // RW, 16 x 32-bit
#define CMD       0x100 // W,  8-bit
#define BUSY      0x104 // R,  1-bit
#define RETIRED   0x108 // R,  32-bit
#define OUT_VEC   0x10C // R,  16 x 32-bit

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

int main(void) {
  // k, v, q all non-negative so ReLU is a no-op on them -- keeps the
  // expected-value arithmetic below trivial (no need to reimplement ReLU's
  // branch in C). decay = 0 everywhere, so the decay term in the state
  // update is exactly zero regardless of S's prior contents.
  const int32_t k[LANES]     = {1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16};
  const int32_t v[LANES]     = {2,4,6,8,10,12,14,16,18,20,22,24,26,28,30,32};
  const int32_t q[LANES]     = {1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1};
  const int32_t decay[LANES] = {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};

  do_cmd(CMD_RESET);

  write_vec(DECAY_VEC, decay);
  write_vec(K_VEC, k);
  write_vec(V_VEC, v);
  do_cmd(CMD_UPDATE); // S := outer(k, v)   (decay=0, S started at 0)

  write_vec(Q_VEC, q);
  do_cmd(CMD_QUERY);  // OUT_VEC := S^T q

  int64_t dot_kq = 0;
  for (int i = 0; i < LANES; i++) dot_kq += (int64_t)k[i] * q[i];

  int pass = 1;
  for (int j = 0; j < LANES; j++) {
    int32_t got = (int32_t)REG32(OUT_VEC + 4 * j);
    int32_t expected = (int32_t)(dot_kq * v[j]); // out[j] = v[j] * dot(k, q)
    int ok = got == expected;
    pass &= ok;
    printf("out[%2d] = %d (expected %d) %s\n", j, got, expected, ok ? "OK" : "FAIL");
  }

  printf("retired = %u\n", REG32(RETIRED));
  printf(pass ? "PASS\n" : "FAIL\n");
  return pass ? 0 : 1;
}
