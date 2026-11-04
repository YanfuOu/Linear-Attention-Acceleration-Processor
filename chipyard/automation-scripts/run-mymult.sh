#!/usr/bin/env bash
# Compile the Chipyard tests and run mymult on MyMultRocketConfig.
# Run this inside the container, from the interactive shell started by
# `docker start -ai`. The repo is mounted at /opt/mounted-chipyard, so:
#   bash /opt/mounted-chipyard/automation-scripts/run-mymult.sh

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
make

# this runs the test
cd "${CHIPYARD_ROOT}/sims/verilator"
make CONFIG=MyMultRocketConfig run-binary-debug \
  BINARY="${CHIPYARD_ROOT}/tests/build/mymult.riscv"
