FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

# System git (only needed to clone), plus basics Conda's installer and the build need
RUN apt-get update && apt-get install -y --no-install-recommends \
        git curl ca-certificates bzip2 build-essential \
    && rm -rf /var/lib/apt/lists/*

# Install Miniforge (Conda) inside the image
RUN curl -L -o /tmp/miniforge.sh \
        https://github.com/conda-forge/miniforge/releases/latest/download/Miniforge3-Linux-x86_64.sh \
    && bash /tmp/miniforge.sh -b -p /opt/miniforge3 \
    && rm /tmp/miniforge.sh

ENV PATH=/opt/miniforge3/bin:$PATH

# Use bash so `source` works in RUN commands
SHELL ["/bin/bash", "-c"]

# Clone and set up Chipyard. `conda activate` is a shell function, so this
# sources Conda first. build-setup.sh step 1 calls $CONDA_EXE, which is unset
# until `conda activate base` has run.
WORKDIR /opt
RUN git clone https://github.com/ucb-bar/chipyard.git
WORKDIR /opt/chipyard

# FireMarshal checks for /usr/bin/sudo before it builds images. Step 9 then
# mounts those images with guestmount, which needs /dev/fuse. docker build
# does not provide that device, so the precompile is skipped. sudo and
# guestmount stay installed for a later privileged container.
RUN apt-get update && apt-get install -y --no-install-recommends \
        sudo \
        libguestfs-tools \
    && rm -rf /var/lib/apt/lists/*

RUN source /opt/miniforge3/etc/profile.d/conda.sh \
    && conda activate base \
    && ./build-setup.sh riscv-tools -s 9

# Activate the Chipyard environment for interactive shells
RUN echo 'source /opt/miniforge3/etc/profile.d/conda.sh' >> /root/.bashrc \
    && echo 'source /opt/chipyard/env.sh' >> /root/.bashrc

CMD ["/bin/bash"]