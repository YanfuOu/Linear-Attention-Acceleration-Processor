#include <stdio.h>
#include <stdint.h>

// Base address must match MyMultParams(address = 0x4000) in MyMult.scala
#define BASE 0x4000
#define REG32(off) (*(volatile uint32_t *)(BASE + (off)))
#define REG64(off) (*(volatile uint64_t *)(BASE + (off)))

// Register map
#define REG_A      0x00  // RW, operand a
#define REG_B      0x04  // RW, operand b
#define REG_START  0x08  // W,  write anything to start
#define REG_DONE   0x0C  // R,  1 when idle / result valid
#define REG_RESULT 0x10  // R,  64-bit a*b

int main(void) {
  const uint32_t a = 123456;
  const uint32_t b = 7890;
  const uint64_t expected = (uint64_t)a * b;

  REG32(REG_A) = a;
  REG32(REG_B) = b;
  REG32(REG_START) = 1;            // start
  while (!REG32(REG_DONE)) ;       // poll done

  uint64_t r = REG64(REG_RESULT);
  printf("result = %llu (expected %llu)\n", r, expected);

  return r == expected ? 0 : 1;
}