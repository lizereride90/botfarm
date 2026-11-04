#!/bin/bash
# pulls the termux proot binaries out of the apt repo and repacks them as
# out/proot.tar.gz with a lib/ layout the app unpacks straight into filesDir.
set -euo pipefail

mkdir -p out work/deb
rm -rf work/deb/* work/proot work/talloc out/lib

PACKAGES="work/Packages"
ROOT=""

for url in \
	"https://packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages" \
	"https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages" \
	"https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/dists/stable/main/binary-aarch64/Packages"
do
	echo "trying $url"
	if curl -fsSL "$url" -o "$PACKAGES"; then
		ROOT="${url%%/dists/*}"
		echo "index ok, root $ROOT"
		break
	fi
done

if [ -z "$ROOT" ]; then
	echo "no termux index reachable" >&2
	exit 1
fi

field() {
	awk -v want="$1" -v key="$2" '
		/^Package: / { pkg = $2 }
		pkg == want && $1 == key { print $2; exit }
	' "$PACKAGES"
}

fetch() {
	local pkg="$1" dest="$2"
	local file
	file=$(field "$pkg" "Filename:")
	if [ -z "$file" ]; then
		echo "package $pkg is not in the index" >&2
		return 1
	fi
	echo "$pkg -> $file"
	curl -fsSL "$ROOT/$file" -o "work/deb/$pkg.deb"
	mkdir -p "$dest"
	dpkg-deb -x "work/deb/$pkg.deb" "$dest"
}

fetch proot work/proot
fetch libtalloc work/talloc || fetch talloc work/talloc
fetch libandroid-shmem work/shmem || true

mkdir -p out/lib

cp work/proot/data/data/com.termux/files/usr/bin/proot out/lib/proot

find work/talloc work/shmem work/proot -name 'lib*.so*' -type f -exec cp -a {} out/lib/ \; 2>/dev/null || true

# the debs carry libfoo.so.1.2.3 plus a symlink to the soname. a plain copy
# drops the symlink, so recreate the soname as a real file for every lib.
for f in out/lib/lib*.so.*; do
	[ -e "$f" ] || continue
	base=$(basename "$f")
	if [[ "$base" =~ ^(lib[^.]+\.so)\.([0-9]+) ]]; then
		cp -f "$f" "out/lib/${BASH_REMATCH[1]}.${BASH_REMATCH[2]}"
	fi
done

chmod 755 out/lib/*

chmod 755 out/lib/proot

echo "proot: $(stat -c %s out/lib/proot) bytes"
ls -l out/lib

tar czf out/proot.tar.gz -C out lib
ls -l out/proot.tar.gz
