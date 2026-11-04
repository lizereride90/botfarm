#!/bin/sh
# runs inside an arm64 alpine container. produces /out/rootfs.tar.gz.
set -eu

apk update
apk add --no-cache \
	bash \
	busybox \
	ca-certificates \
	coreutils \
	curl \
	git \
	less \
	nodejs \
	npm \
	openssh-client \
	procps \
	python3 \
	py3-pip \
	ripgrep \
	tar \
	tmux \
	unzip \
	xz \
	zip

echo "node:  $(node -v)"
echo "npm:   $(npm -v)"
echo "python: $(python3 -V)"
echo "git:   $(git --version)"

npm install -g npm@latest >/dev/null 2>&1 || true

rm -rf /var/cache/apk/* /tmp/* /root/.npm /root/.cache /usr/share/man /usr/share/doc

mkdir -p /out

tar czf /out/rootfs.tar.gz \
	--numeric-owner \
	-C / \
	--exclude=proc \
	--exclude=sys \
	--exclude=dev \
	--exclude=out \
	--exclude=tmp \
	--exclude=run \
	.

ls -l /out/rootfs.tar.gz
