#!/usr/bin/env python3
"""builds out/termux.tar.gz: the official termux bootstrap plus a package set.

runs on the ci runner, which is x86 linux. nothing here executes android
binaries, it only downloads them and unpacks the archives, so a plain python,
no docker and no qemu.
"""

import io
import os
import shutil
import stat
import sys
import tarfile
import urllib.request
import zipfile

BOOTSTRAP = "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip"

INDEXES = [
    "https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages",
    "https://packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages",
]

# what a bot farm needs to be useful out of the box. dependencies come along
# on their own.
WANT = [
    "bash",
    "coreutils",
    "curl",
    "git",
    "jq",
    "less",
    "nano",
    "nodejs",
    "openssh",
    "procps",
    "python",
    "ripgrep",
    "tar",
    "tmux",
    "unzip",
    "wget",
    "xz-utils",
]

WORK = "work"
ROOT = os.path.join(WORK, "rootfs")
PREFIX = os.path.join(ROOT, "usr")

# the bootstrap is built for the termux app id, and every binary has that path
# compiled in. our own id is the same length, so repointing them is a straight
# byte swap with nothing to pad.
OLD_PREFIX = b"/data/data/com.termux"
NEW_PREFIX = b"/data/data/io.botfarm"


def log(*bits):
    print(*bits, flush=True)


def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": "botfarm"})
    with urllib.request.urlopen(request, timeout=180) as response:
        return response.read()


# ------------------------------------------------------------------ bootstrap


def install_bootstrap():
    log("bootstrap: downloading")
    data = get(BOOTSTRAP)
    log(f"bootstrap: {len(data) // 1048576}mb")

    os.makedirs(PREFIX, exist_ok=True)

    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        manifest = None
        for info in archive.infolist():
            target = os.path.join(PREFIX, info.filename)
            if not os.path.abspath(target).startswith(os.path.abspath(PREFIX) + os.sep):
                continue

            mode = info.external_attr >> 16

            if info.is_dir():
                os.makedirs(target, exist_ok=True)
                continue

            os.makedirs(os.path.dirname(target), exist_ok=True)

            if info.filename.endswith("SYMLINKS.txt"):
                manifest = archive.read(info).decode()
                continue

            with archive.open(info) as source, open(target, "wb") as sink:
                shutil.copyfileobj(source, sink)
            if stat.S_IMODE(mode):
                os.chmod(target, stat.S_IMODE(mode))

    # the zip cannot carry symlinks, termux ships a manifest of them instead.
    # each line is target<arrow>link, both relative to the prefix.
    links = 0
    for line in (manifest or "").splitlines():
        if "\u2190" not in line:
            continue
        payload, link = line.split("\u2190", 1)
        payload = payload.strip().removeprefix("./")
        link = link.strip().removeprefix("./")
        if not payload or not link:
            continue

        path = os.path.join(PREFIX, link)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        if os.path.lexists(path):
            os.remove(path)
        os.symlink(payload, path)
        links += 1

    log(f"bootstrap: {links} symlinks restored")


# -------------------------------------------------------------------- packages


def load_index():
    for url in INDEXES:
        try:
            text = get(url).decode("utf8", "replace")
        except Exception as error:  # noqa: BLE001
            log(f"index failed {url}: {error}")
            continue

        packages = {}
        for block in text.split("\n\n"):
            entry = {}
            for line in block.splitlines():
                if line.startswith(" ") or ":" not in line:
                    continue
                key, value = line.split(":", 1)
                entry[key.strip()] = value.strip()
            name = entry.get("Package")
            if name:
                packages[name] = entry

        if packages:
            log(f"index: {len(packages)} packages from {url.split('/')[2]}")
            return packages

    raise SystemExit("no package index reachable")


def dependencies(entry):
    names = []
    for key in ("Pre-Depends", "Depends"):
        for group in entry.get(key, "").split(","):
            group = group.strip()
            if not group:
                continue
            # alternatives: take the first one that exists in the index
            for alternative in group.split("|"):
                alternative = alternative.strip().split("(")[0].strip()
                if alternative:
                    names.append(alternative)
                    break
    return names


def resolve(packages, wanted):
    order = []
    seen = set()
    missing = set()

    def walk(name):
        if name in seen:
            return
        seen.add(name)
        entry = packages.get(name)
        if entry is None:
            missing.add(name)
            return
        for dependency in dependencies(entry):
            walk(dependency)
        order.append(name)

    for name in wanted:
        walk(name)

    if missing:
        log("missing from the index:", ", ".join(sorted(missing)))
    return order


def unpack_deb(path, prefix):
    with open(path, "rb") as handle:
        data = handle.read()

    if data[:8] != b"!<arch>\n":
        raise SystemExit(f"{path} is not a deb")

    offset = 8
    payload = None
    while offset + 60 <= len(data):
        header = data[offset:offset + 60]
        name = header[0:16].decode().strip()
        size = int(header[48:58].decode().strip())
        offset += 60
        body = data[offset:offset + size]
        offset += size + (size % 2)
        if name.startswith("data.tar"):
            payload = body
            break

    if payload is None:
        raise SystemExit(f"{path} has no data member")

    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:*") as archive:
        members = archive.getmembers()
        archive.extractall(path=WORK, members=members, filter="fully_trusted")

    source = os.path.join(WORK, "data", "data", "com", "termux", "files", "usr")
    if not os.path.isdir(source):
        return

    for root, directories, files in os.walk(source):
        relative = os.path.relpath(root, source)
        destination = os.path.join(prefix, relative) if relative != "." else prefix
        os.makedirs(destination, exist_ok=True)
        for entry in files + directories:
            src = os.path.join(root, entry)
            dst = os.path.join(destination, entry)
            if os.path.lexists(dst):
                os.remove(dst) if not os.path.isdir(dst) or os.path.islink(dst) else None
            shutil.move(src, dst)

    shutil.rmtree(os.path.join(WORK, "data"), ignore_errors=True)


def install_packages(packages):
    order = resolve(packages, WANT)
    log(f"packages: {len(order)} including dependencies")

    os.makedirs(os.path.join(WORK, "deb"), exist_ok=True)

    for name in order:
        entry = packages[name]
        filename = entry.get("Filename")
        if not filename:
            log(f"skip {name}, no filename")
            continue

        root = "https://packages.termux.dev/apt/termux-main"
        url = f"{root}/{filename}"
        deb = os.path.join(WORK, "deb", name + ".deb")

        if not os.path.exists(deb):
            try:
                with open(deb, "wb") as sink:
                    sink.write(get(url))
            except Exception as error:  # noqa: BLE001
                log(f"download failed {name}: {error}")
                continue

        unpack_deb(deb, PREFIX)
        log(f"  {name} {entry.get('Version', '')}")


# ----------------------------------------------------------------------- main


def patch_prefix():
    assert len(OLD_PREFIX) == len(NEW_PREFIX)

    touched = 0
    for root, _, files in os.walk(PREFIX):
        for name in files:
            path = os.path.join(root, name)
            if os.path.islink(path):
                continue
            try:
                with open(path, "rb") as handle:
                    data = handle.read()
            except OSError:
                continue
            if OLD_PREFIX not in data:
                continue
            with open(path, "wb") as handle:
                handle.write(data.replace(OLD_PREFIX, NEW_PREFIX))
            touched += 1

    log(f"patched {touched} files to {NEW_PREFIX.decode()}")


def main():
    shutil.rmtree(WORK, ignore_errors=True)
    os.makedirs(PREFIX, exist_ok=True)
    os.makedirs("out", exist_ok=True)

    install_bootstrap()
    install_packages(load_index())
    patch_prefix()

    for check in ("bin/sh", "bin/bash", "bin/node", "bin/git", "bin/python3"):
        path = os.path.join(PREFIX, check)
        log(f"{check}: {'ok' if os.path.exists(path) else 'MISSING'}")

    archive = os.path.join("out", "termux.tar.gz")
    with tarfile.open(archive, "w:gz", compresslevel=6) as tar:
        tar.add(ROOT, arcname=".")

    log(f"wrote {archive}, {os.path.getsize(archive) // 1048576}mb")


if __name__ == "__main__":
    main()
