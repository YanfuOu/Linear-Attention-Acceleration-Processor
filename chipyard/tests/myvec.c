#include <stdio.h>
#include <stdint.h>

// Addresses must match MyVecParams in MyVec.scala
#define CTRL_BASE 0x5000
#define SRAM_BASE 0x10050000
#define REG32(off) (*(volatile uint32_t *)(CTRL_BASE + (off)))
#define SRAM32     ((volatile uint32_t *)SRAM_BASE)
#define SRAM8      ((volatile uint8_t  *)SRAM_BASE)

// Register map
#define REG_CMD     0x00  // W,  push one instruction (stalls while FIFO full)
#define REG_IDLE    0x04  // R,  1 when FIFO empty and engine idle
#define REG_ERROR   0x08  // RW, sticky illegal-instruction flag, write 0 to clear
#define REG_RETIRED 0x0C  // R,  instructions executed
#define REG_QCOUNT  0x10  // R,  instructions waiting in FIFO

// Opcodes (MyVecISA in MyVec.scala)
#define OP_NOP     0x00
#define OP_VLD     0x01
#define OP_VST     0x02
#define OP_VSPLAT  0x03
#define OP_VADD    0x08
#define OP_VSUB    0x09
#define OP_VMUL    0x0A
#define OP_VAND    0x0B
#define OP_VOR     0x0C
#define OP_VXOR    0x0D
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

#define VLD(vd, off)          ENC_I(OP_VLD, vd, off)
#define VST(vs, off)          ENC_I(OP_VST, vs, off)
#define VSPLAT(vd, imm)       ENC_I(OP_VSPLAT, vd, imm)
#define VALU(op, vd, vs1, vs2) ENC_R(op, vd, vs1, vs2)
#define VREDSUM(vd, vs1)      ENC_R(OP_VREDSUM, vd, vs1, 0)
#define DMALD(off, n)         ENC_D(OP_DMALD, off, n)
#define DMAST(off, n)         ENC_D(OP_DMAST, off, n)

#define LANES  16
#define ROW    (LANES * 4)     // bytes per SRAM row / vector register
#define LG_ROW 6
#define N      256             // elements per operand
#define NROWS  (N / LANES)
#define A_OFF  0x000           // byte offsets inside the SRAM
#define B_OFF  0x400
#define D_OFF  0x800
#define E_OFF  0xC00           // spare, runs to the end of the 4 KiB SRAM

// Addresses for the DMA error cases, from the generated device tree
#define UNMAPPED_ADDR 0x70000000u
#define ROM_ADDR      0x00010000u

// Buffers in cacheable DRAM. The CPU never flushes these, so the DMA checks
// below also check coherence with the L1.
static uint32_t dram_a[N] __attribute__((aligned(ROW)));
static uint32_t dram_b[N] __attribute__((aligned(ROW)));
static uint32_t dram_d[N + LANES] __attribute__((aligned(ROW))); // last row is a guard
static uint32_t a[N], b[N];

static uint32_t issued;        // instructions pushed, to check RETIRED

static inline void fence(void) { asm volatile ("fence" ::: "memory"); }

static void issue(uint32_t inst) {
  REG32(REG_CMD) = inst;
  issued++;
}

static void dma_base(uintptr_t addr) {
  issue(ENC_I(OP_DBASEL, 0, addr >> LG_ROW));
  issue(ENC_I(OP_DBASEH, 0, addr >> (LG_ROW + 21)));
}

static int wait_idle(const char *what) {
  fence();
  uint32_t timeout = 100000;
  while (!REG32(REG_IDLE)) {
    if (--timeout == 0) {
      printf("FAIL: timeout waiting for idle (%s)\n", what);
      return 1;
    }
  }
  fence();
  return 0;
}

static const struct { int op; const char *name; } alu_ops[] = {
  { OP_VADD, "vadd" }, { OP_VSUB, "vsub" }, { OP_VMUL, "vmul" },
  { OP_VAND, "vand" }, { OP_VOR,  "vor"  }, { OP_VXOR, "vxor" },
};

static uint32_t golden(int op, uint32_t x, uint32_t y) {
  switch (op) {
    case OP_VADD: return x + y;
    case OP_VSUB: return x - y;
    case OP_VMUL: return x * y;
    case OP_VAND: return x & y;
    case OP_VOR:  return x | y;
    case OP_VXOR: return x ^ y;
  }
  return 0;
}

static int check(const char *name, uint32_t idx, uint32_t got, uint32_t exp, int *errs) {
  if (got != exp) {
    if (*errs < 4)
      printf("  %s[%lu]: got 0x%08lx expected 0x%08lx\n", name, (unsigned long)idx,
             (unsigned long)got, (unsigned long)exp);
    (*errs)++;
  }
  return got != exp;
}

static int report(const char *name, int errs) {
  printf("%s: %s\n", name, errs ? "FAIL" : "PASS");
  return errs;
}

int main(void) {
  int errors = 0;
  uint32_t retired0 = REG32(REG_RETIRED);

  // 1. SRAM sanity: word write/read and byte (partial) write/read
  SRAM32[0] = 0xdeadbeef;
  SRAM8[1]  = 0x55;
  if (SRAM32[0] != 0xdead55ef) {
    printf("FAIL: SRAM readback 0x%08lx (expected 0xdead55ef)\n", (unsigned long)SRAM32[0]);
    errors++;
  }

  for (int i = 0; i < N; i++) {
    a[i] = 0x01000000u * (i & 0xff) + 3u * i + 7u;
    b[i] = 0x9e3779b9u * (i + 1);          // varied bits, includes b > a
    SRAM32[A_OFF / 4 + i] = a[i];
    SRAM32[B_OFF / 4 + i] = b[i];
  }
  fence();

  // 2. Every ALU op over N elements: load, op, store per 16-lane row
  for (unsigned k = 0; k < sizeof(alu_ops) / sizeof(alu_ops[0]); k++) {
    for (int v = 0; v < NROWS; v++) {
      issue(VLD(1, A_OFF + ROW * v));
      issue(VLD(2, B_OFF + ROW * v));
      issue(VALU(alu_ops[k].op, 3, 1, 2));
      issue(VST(3, D_OFF + ROW * v));
    }
    if (wait_idle(alu_ops[k].name)) return 1;

    int errs = 0;
    for (int i = 0; i < N; i++)
      check(alu_ops[k].name, i, SRAM32[D_OFF / 4 + i], golden(alu_ops[k].op, a[i], b[i]), &errs);
    errors += report(alu_ops[k].name, errs);
  }

  // 3. VSPLAT with a negative immediate (sign extension), then add to A
  for (int v = 0; v < NROWS; v++) {
    issue(VSPLAT(4, -5));
    issue(VLD(1, A_OFF + ROW * v));
    issue(VALU(OP_VADD, 5, 1, 4));
    issue(VST(5, D_OFF + ROW * v));
  }
  if (wait_idle("vsplat")) return 1;
  {
    int errs = 0;
    for (int i = 0; i < N; i++)
      check("vsplat", i, SRAM32[D_OFF / 4 + i], a[i] - 5u, &errs);
    errors += report("vsplat + vadd", errs);
  }

  // 4. Dot product: accumulate in v0, then reduce across lanes
  {
    uint32_t dot = 0;
    for (int i = 0; i < N; i++) dot += a[i] * b[i];

    issue(VSPLAT(0, 0));
    for (int v = 0; v < NROWS; v++) {
      issue(VLD(1, A_OFF + ROW * v));
      issue(VLD(2, B_OFF + ROW * v));
      issue(VALU(OP_VMUL, 3, 1, 2));
      issue(VALU(OP_VADD, 0, 0, 3));
    }
    issue(VREDSUM(6, 0));
    issue(VST(6, D_OFF));
    if (wait_idle("dot")) return 1;

    int errs = 0;
    check("dot", 0, SRAM32[D_OFF / 4 + 0], dot, &errs);
    for (int i = 1; i < LANES; i++) check("dot", i, SRAM32[D_OFF / 4 + i], 0, &errs);
    errors += report("dot product (vmul/vadd/vredsum)", errs);
  }

  // 5. DMA load: DRAM -> SRAM, replacing what the CPU wrote there
  for (int i = 0; i < N; i++) {
    dram_a[i] = ~a[i];
    dram_b[i] = ~b[i];
  }
  fence();
  dma_base((uintptr_t)dram_a);
  issue(DMALD(A_OFF, NROWS));
  dma_base((uintptr_t)dram_b);
  issue(DMALD(B_OFF, NROWS));
  if (wait_idle("dma load")) return 1;
  {
    int errs = 0;
    for (int i = 0; i < N; i++) {
      check("dma load A", i, SRAM32[A_OFF / 4 + i], ~a[i], &errs);
      check("dma load B", i, SRAM32[B_OFF / 4 + i], ~b[i], &errs);
    }
    errors += report("dma load", errs);
  }

  // 6. Full pipeline with the CPU out of the data path: DMA in (done above),
  //    compute, DMA out. dram_d was just written by the CPU, so its lines are
  //    dirty in the L1 when the DMA overwrites them.
  for (int i = 0; i < N + LANES; i++) dram_d[i] = 0xcafef00d;
  fence();
  for (int v = 0; v < NROWS; v++) {
    issue(VLD(1, A_OFF + ROW * v));
    issue(VLD(2, B_OFF + ROW * v));
    issue(VALU(OP_VMUL, 3, 1, 2));
    issue(VST(3, D_OFF + ROW * v));
  }
  dma_base((uintptr_t)dram_d);
  issue(DMAST(D_OFF, NROWS));
  if (wait_idle("dma store")) return 1;
  {
    int errs = 0;
    for (int i = 0; i < N; i++)
      check("dma store", i, dram_d[i], ~a[i] * ~b[i], &errs);
    for (int i = N; i < N + LANES; i++)        // the row past the end is untouched
      check("dma guard", i, dram_d[i], 0xcafef00d, &errs);
    errors += report("dma compute + store", errs);
  }

  // 7. Base auto-increments: two one-row loads continue from where the last ended
  dma_base((uintptr_t)dram_a);
  issue(DMALD(E_OFF, 1));
  issue(DMALD(E_OFF + ROW, 1));
  if (wait_idle("dma increment")) return 1;
  {
    int errs = 0;
    for (int i = 0; i < 2 * LANES; i++)
      check("dma increment", i, SRAM32[E_OFF / 4 + i], ~a[i], &errs);
    errors += report("dma base auto-increment", errs);
  }

  // 8. A DMA that ends exactly at the end of the SRAM is legal
  dma_base((uintptr_t)dram_b);
  issue(DMALD(E_OFF, NROWS));
  if (wait_idle("dma sram end")) return 1;
  {
    int errs = 0;
    for (int i = 0; i < N; i++)
      check("dma sram end", i, SRAM32[E_OFF / 4 + i], ~b[i], &errs);
    errors += report("dma to end of sram", errs);
  }

  // 9. RETIRED matches what we issued, and no error so far
  {
    int errs = 0;
    check("retired", 0, REG32(REG_RETIRED) - retired0, issued, &errs);
    check("error", 0, REG32(REG_ERROR), 0, &errs);
    errors += report("retired count", errs);
  }

  // 10. Illegal instructions set ERROR, are skipped, and don't retire
  {
    static const struct { uint32_t inst; uint32_t base; const char *name; } bad[] = {
      { ENC_I(0x3F, 0, 0),          0,             "unknown opcode" },
      { VLD(1, 0x020),              0,             "misaligned vld" },
      { VST(1, 0x1000),             0,             "out-of-range vst" },
      { VALU(OP_VADD, 9, 1, 2),     0,             "vreg out of range" },
      { DMALD(0x004, 1),            0,             "misaligned dma" },
      { DMALD(0x000, 0),            0,             "zero-length dma" },
      { DMALD(E_OFF, NROWS + 1),    0,             "dma past end of sram" },
      { DMALD(A_OFF, 1),            UNMAPPED_ADDR, "dma load from unmapped" },
      { DMAST(A_OFF, 1),            UNMAPPED_ADDR, "dma store to unmapped" },
      { DMAST(A_OFF, 1),            ROM_ADDR,      "dma store to rom" },
    };
    int errs = 0;
    for (unsigned k = 0; k < sizeof(bad) / sizeof(bad[0]); k++) {
      if (bad[k].base) dma_base(bad[k].base);
      if (wait_idle(bad[k].name)) return 1;
      uint32_t r0 = REG32(REG_RETIRED);
      REG32(REG_CMD) = bad[k].inst;
      if (wait_idle(bad[k].name)) return 1;
      if (REG32(REG_ERROR) != 1 || REG32(REG_RETIRED) != r0) {
        printf("  %s: error=%lu retired delta=%lu\n", bad[k].name,
               (unsigned long)REG32(REG_ERROR), (unsigned long)(REG32(REG_RETIRED) - r0));
        errs++;
      }
      REG32(REG_ERROR) = 0;
      if (REG32(REG_ERROR) != 0) errs++;
    }
    errors += report("illegal instructions", errs);
  }

  printf("%s (%d errors)\n", errors ? "FAILED" : "ALL PASSED", errors);
  return errors ? 1 : 0;
}
