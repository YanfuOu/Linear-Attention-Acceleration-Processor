#!/usr/bin/env bash
# Compile the Chipyard tests and run outer-product-unit on
# OuterProductUnitRocketConfig. Run this inside the container, from the
# interactive shell started by `docker start -ai`. The repo is mounted at
# /opt/mounted-chipyard, so:
#   bash /opt/mounted-chipyard/automation-scripts/run-outer-product-unit.sh

set -euo pipefail

CHIPYARD_ROOT=/opt/chipyard

if [[ -z "${RISCV:-}" && -f "${CHIPYARD_ROOT}/env.sh" ]]; then
  set +u
  # shellcheck disable=SC1091
  source "${CHIPYARD_ROOT}/env.sh"
  set -u
fi

# this builds the test
mkdir -p "${CHIPYARD_ROOT}/tests/build"
cd "${CHIPYARD_ROOT}/tests/build"
cmake ..
make outer-product-unit

# this runs the test
#
# CXXFLAGS=-march=x86-64-v2 works around a Verilator-binary runtime failure
# ("CPU ISA level is lower than required") seen under Docker Desktop's
# Rosetta-based amd64 emulation on Apple Silicon: Rosetta only exposes up to
# SSE4.2 (no AVX/AVX2), while the conda toolchain's default -march baseline
# is higher. x86-64-v2 only needs SSE4.2 + POPCNT, which Rosetta does
# expose. Harmless (and unnecessary, but also harmless) on a real x86_64
# host.
cd "${CHIPYARD_ROOT}/sims/verilator"
make CONFIG=OuterProductUnitRocketConfig CXXFLAGS=-march=x86-64-v2 run-binary-debug \
  BINARY="${CHIPYARD_ROOT}/tests/build/outer-product-unit.riscv"
