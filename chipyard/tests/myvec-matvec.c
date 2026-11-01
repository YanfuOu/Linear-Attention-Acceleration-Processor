// One 16x16 matrix-vector multiply (y = A * x) on the MyVec engine. A and x live
// in DRAM: each matrix row is DMA'd in, dotted with x, and its result kept in
// the SRAM. All the results go back to DRAM at the end.
//
// SRAM layout (64-byte rows):
//   X_OFF  x
//   W_OFF  the current matrix row
//   Y_OFF  one row per result, with y[i] in lane 0 and the other lanes zero

#include <stdio.h>
#include <stdint.h>

// Addresses must match MyVecParams in MyVec.scala
#define CTRL_BASE 0x5000
#define REG32(off) (*(volatile uint32_t *)(CTRL_BASE + (off)))

// Register map
#define REG_CMD     0x00  // W,  push one instruction (stalls while FIFO full)
#define REG_IDLE    0x04  // R,  1 when FIFO empty and engine idle
#define REG_ERROR   0x08  // RW, sticky illegal-instruction flag, write 0 to clear
#define REG_RETIRED 0x0C  // R,  instructions executed

// Opcodes (MyVecISA in MyVec.scala)
#define OP_VLD     0x01
#define OP_VST     0x02
#define OP_VSPLAT  0x03
#define OP_VMUL    0x0A
#define OP_VREDSUM 0x10
#define OP_DBASEL  0x20
#define OP_DBASEH  0x21
#define OP_DMALD   0x22
#define OP_DMAST   0x23

// Encodings
//   R-type: [31:26] op | [25:21] vd | [20:16] vs1 | [15:11] vs2
//   I-type: [31:26] op | [25:21] vd | [20:0] imm
//   D-type: [31:26] op | [25:16] nrows | [15:0] SRAM byte offset
#define ENC_R(op, vd, vs1, vs2) \
  (((uint32_t)(op) << 26) | ((uint32_t)(vd) << 21) | ((uint32_t)(vs1) << 16) | ((uint32_t)(vs2) << 11))
#define ENC_I(op, vd, imm) \
  (((uint32_t)(op) << 26) | ((uint32_t)(vd) << 21) | ((uint32_t)(imm) & 0x1FFFFF))
#define ENC_D(op, off, n) \
  (((uint32_t)(op) << 26) | ((uint32_t)(n) << 16) | ((uint32_t)(off) & 0xFFFF))

#define LANES  16
#define ROW    (LANES * 4)     // bytes per SRAM row / vector register
#define LG_ROW 6

#define M      16
#define N      16              // one SRAM row per matrix row
#define X_OFF  0x000
#define W_OFF  0x100
#define Y_OFF  0x400

// Buffers in cacheable DRAM, ROW aligned as the DMA requires
static uint32_t dram_a[M][N] __attribute__((aligned(ROW)));
static uint32_t dram_x[N] __attribute__((aligned(ROW)));
static uint32_t dram_y[(M + 1) * LANES] __attribute__((aligned(ROW))); // +1: guard row
static uint32_t golden[M];

static uint32_t issued;        // instructions pushed, to check RETIRED

static inline void fence(void) { asm volatile ("fence" ::: "memory"); }

static inline uint64_t rdcycle(void) {
  uint64_t c;
  asm volatile ("rdcycle %0" : "=r"(c));
  return c;
}

static void issue(uint32_t inst) {
  REG32(REG_CMD) = inst;
  issued++;
}

static void dma_base(uintptr_t addr) {
  issue(ENC_I(OP_DBASEL, 0, addr >> LG_ROW));
  issue(ENC_I(OP_DBASEH, 0, addr >> (LG_ROW + 21)));
}

static int wait_idle(void) {
  fence();
  uint32_t timeout = 100000;
  while (!REG32(REG_IDLE)) {
    if (--timeout == 0) {
      printf("FAIL: timeout waiting for idle\n");
      return 1;
    }
  }
  fence();
  return 0;
}

static uint32_t rng_state = 0x12345678;

static uint32_t rng(void) {  // xorshift32
  rng_state ^= rng_state << 13;
  rng_state ^= rng_state >> 17;
  rng_state ^= rng_state << 5;
  return rng_state;
}

int main(void) {
  int errors = 0;
  uint32_t retired0 = REG32(REG_RETIRED);

  for (int i = 0; i < M; i++)
    for (int j = 0; j < N; j++) dram_a[i][j] = rng();
  for (int j = 0; j < N; j++) dram_x[j] = rng();
  for (int i = 0; i < (M + 1) * LANES; i++) dram_y[i] = 0xcafef00d;

  // The same computation on the CPU gives the expected answer
  uint64_t t0 = rdcycle();
  for (int i = 0; i < M; i++) {
    uint32_t sum = 0;
    for (int j = 0; j < N; j++) sum += dram_a[i][j] * dram_x[j];
    golden[i] = sum;
  }
  uint64_t t1 = rdcycle();

  // y = A * x on the engine
  fence();
  dma_base((uintptr_t)dram_x);
  issue(ENC_D(OP_DMALD, X_OFF, 1));
  dma_base((uintptr_t)dram_a);         // the base steps through A row by row
  for (int i = 0; i < M; i++) {
    issue(ENC_D(OP_DMALD, W_OFF, 1));
    issue(ENC_I(OP_VLD, 1, W_OFF));
    issue(ENC_I(OP_VLD, 2, X_OFF));
    issue(ENC_R(OP_VMUL, 3, 1, 2));
    issue(ENC_R(OP_VREDSUM, 4, 3, 0));
    issue(ENC_I(OP_VST, 4, Y_OFF + ROW * i));
  }
  dma_base((uintptr_t)dram_y);
  issue(ENC_D(OP_DMAST, Y_OFF, M));
  if (wait_idle()) return 1;
  uint64_t t2 = rdcycle();

  // Each y[i] is in lane 0, the other lanes are zero
  for (int i = 0; i < M; i++) {
    for (int l = 0; l < LANES; l++) {
      uint32_t got = dram_y[i * LANES + l];
      uint32_t exp = l == 0 ? golden[i] : 0;
      if (got != exp) {
        printf("  y[%d] lane %d: got 0x%08lx expected 0x%08lx\n", i, l,
               (unsigned long)got, (unsigned long)exp);
        errors++;
      }
    }
  }

  // The row past the results is untouched
  for (int l = 0; l < LANES; l++) {
    if (dram_y[M * LANES + l] != 0xcafef00d) {
      printf("  guard row lane %d overwritten\n", l);
      errors++;
    }
  }

  // Everything retired and nothing flagged an error
  uint32_t retired = REG32(REG_RETIRED) - retired0;
  uint32_t err = REG32(REG_ERROR);
  if (retired != issued || err != 0) {
    printf("  retired %lu of %lu, error=%lu\n", (unsigned long)retired,
           (unsigned long)issued, (unsigned long)err);
    errors++;
  }

  printf("16x16 cycles: engine %lu, cpu %lu\n",
         (unsigned long)(t2 - t1), (unsigned long)(t1 - t0));
  printf("matvec 16x16: %s (%d errors)\n", errors ? "FAILED" : "PASSED", errors);
  return errors ? 1 : 0;
}
