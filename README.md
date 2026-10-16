# Linear-Attention-Acceleration-Processor

## Chipyard container

The image `yanfuou2/senior-design:chipyard-v1` has Chipyard and the `riscv-tools` toolchain installed under `/opt/chipyard`. An interactive shell sources `/opt/chipyard/env.sh`, which activates that environment.

Pull the image:

```bash
docker pull yanfuou2/senior-design:chipyard-v1
```

Start a shell. The open-file limit is required by the Verilator build.

```bash
docker run -it --ulimit nofile=1048576:1048576 --rm yanfuou2/senior-design:chipyard-v1
```

## Validate the toolchain

Inside the container, confirm the RISC-V toolchain and CIRCT are on `PATH`:

```bash
echo "$RISCV"
riscv64-unknown-elf-gcc --version
firtool --version
```

`$RISCV` should be `/opt/chipyard/.conda-env/riscv-tools`. Both version commands should print a version.

## Validate simulation

Build the default Verilator simulator (`RocketConfig`) and run a RISC-V instruction test. Elaboration needs about 6.5 GB of RAM. This follows the [Chipyard Verilator instructions](https://chipyard.readthedocs.io/en/latest/simulation/software-rtl-simulation/).

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