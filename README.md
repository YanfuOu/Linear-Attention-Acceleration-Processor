# Linear-Attention-Acceleration-Processor

## Chipyard container

The image `yanfuou2/senior-design:chipyard-v1` has Chipyard and the `riscv-tools` toolchain installed under `/opt/chipyard`. An interactive shell sources `/opt/chipyard/env.sh`, which activates that environment.

[Docker Hub Registry Link](https://hub.docker.com/r/yanfuou2/senior-design/tags)

Pull the image:

```bash
docker pull yanfuou2/senior-design:chipyard-v1
```

Start a shell. The open-file limit is required by the Verilator build.

```bash
docker run -it --ulimit nofile=1048576:1048576 --rm yanfuou2/senior-design:chipyard-v1
```

## Validate the toolchain

Inside the container, confirm the RISC-V toolchain and CIRCT are on `PATH` by running the following commands:

Command:
```bash
echo "$RISCV"
```
Expected results:
```bash
(/opt/chipyard/.conda-env) root@0149e7e3108e:/opt/chipyard# echo "$RISCV"
/opt/chipyard/.conda-env/riscv-tools
```

Command:
```bash
riscv64-unknown-elf-gcc --version
```
Expected results:
```
riscv64-unknown-elf-gcc (g6afcc4f6d-dirty) 16.1.0
Copyright (C) 2026 Free Software Foundation, Inc.
This is free software; see the source for copying conditions.  There is NO
warranty; not even for MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
```

Command:
```bash
firtool --version
```
Expected results:
```
(/opt/chipyard/.conda-env) root@0149e7e3108e:/opt/chipyard# firtool --version
LLVM (http://llvm.org/):
  LLVM version 19.0.0git
  Optimized build.
CIRCT firtool-1.75.0
```

## Validate simulation

Run the following [Chipyard Verilator](https://chipyard.readthedocs.io/en/latest/simulation/software-rtl-simulation/) using the following commands:

```bash
cd /opt/chipyard/sims/verilator
make
./simulator-chipyard.harness-RocketConfig $RISCV/riscv64-unknown-elf/share/riscv-tests/isa/rv64ui-p-simple
```
A passing simulator looks like this

```bash
(/opt/chipyard/.conda-env) root@6544b6a00714:/opt/chipyard/sims/verilator# ./simulator-chipyard.harness-RocketConfig $RISCV/riscv64-unknown-elf/share/riscv-tests/isa/rv64ui-p-simple
[UART] UART0 is here (stdin/stdout).
- /opt/chipyard/sims/verilator/generated-src/chipyard.harness.TestHarness.RocketConfig/gen-collateral/TestDriver.v:158: Verilog $finish
(/opt/chipyard/.conda-env) root@6544b6a00714:/opt/chipyard/sims/verilator# ./simulator-chipyard.harness-RocketConfig $RISCV/riscv64-unknown-elf/share/riscv-tests/isa/rv64ui-p-simple
[UART] UART0 is here (stdin/stdout).
- /opt/chipyard/sims/verilator/generated-src/chipyard.harness.TestHarness.RocketConfig/gen-collateral/TestDriver.v:158: Verilog $finish
```