# Linear-Attention-Acceleration-Processor

# Workflow V2 - Running LARP Container Using docker compose 
Goal: Automate the manual docker volume attatchment and docker runs with docker compse! Additionally, all the docker file copying from the volume mountshould be automated. Docker container should persist created files after exit and present after resuming. Everything should be setup using command in Step 1 and ready to go by running command in Step 2. 

Step 1: Create the LARP container defined in the docker-compose.yml file, but it does not start it. You should only need to run step 1 once to set everything up. 
```bash
docker compose create
```
After running the command, you should see terminal confirmation showing " ✔ Container linear-attention-acceleration-processor-chipyard-1 Created " 

Step 2: Wake up the created/stopped container and immediately drops you back into its interactive terminal session. After exiting the container, you can rerun this command to reuse your stopped container. The files you have created in your container <em>should(?)</em> remain in the container. ALWAYS copy your files to the mounted volume so it will persist after exiting the container.    
```bash
docker start -ai linear-attention-acceleration-processor-chipyard-1
```

### Notes: 
1. Your Scala files are copied to /opt/chipyard/generators/chipyard/src/main/scala/
2. Your tests are copied to /opt/chipyard/tests
3. To compile the test: 
```bash
cd /opt/chipyard/tests && mkdir build
cd build && cmake .. 
make
```
4. To run your test
```bash
cd /opt/chipyard/sims/verilator
make CONFIG=MyMultRocketConfig run-binary-debug BINARY=/opt/chipyard/tests/build/mymult.riscv
```
5. If you feel lazy, run the following automation script that does steps 3 and 4 for you.
```bash
bash /opt/mounted-chipyard/automation-scripts/run-mymult.sh
```
6. The outer product unit (state update S = decay*S + k⊗v, query o = S^T q) has its own test and automation script, same pattern:
```bash
bash /opt/mounted-chipyard/automation-scripts/run-outer-product-unit.sh
```

# Workflow V1 - Pulling the Build LARP Container and Running it Using Docker Commands  
## Chipyard container

The image `yanfuou2/senior-design:chipyard-v1` has Chipyard and the `riscv-tools` toolchain installed under `/opt/chipyard`. An interactive shell sources `/opt/chipyard/env.sh`, which activates that environment.

[Docker Hub Registry Link](https://hub.docker.com/r/yanfuou2/senior-design/tags)

Pull the image:

```bash
docker pull yanfuou2/senior-design:chipyard-v1
```

Start a shell. The open-file limit is required by the Verilator build.

```bash
docker run -it --ulimit nofile=1048576:1048576 yanfuou2/senior-design:chipyard-v1
```

Run the container with the volume mounted

```bash
docker run -it -v ./chipyard:/opt/mounted-chipyard/ --ulimit nofile=1048576:1048576 yanfuou2/senior-design:chipyard-v1
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