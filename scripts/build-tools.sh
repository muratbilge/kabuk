#!/usr/bin/env bash
# Build the Phone tab's tools (bionic executables) and put them where Gradle packs them:
#   app/src/main/jniLibs/<abi>/lib<name>.so   (gitignored; extracted at install to nativeLibraryDir, the one
#                                             place a targetSdk-35 app may exec its own files, see docs/LOCAL-TOOLS.md)
#   scripts/build-tools.sh            -> all tools, arm64-v8a + x86_64
#   ABIS="arm64-v8a" scripts/build-tools.sh
# Sources are pinned (version + SHA-256 below) and built with the SDK's NDK. Results are cached in
# ~/.cache/kabuk-tools/<tool>-<ver>-<abi>/ together with a stamp of that tool's own part of this script (its build
# function and settings, see tool_stamp), so a re-run is a copy and an edit of a tool's config or flags rebuilds
# that tool only (rclone takes minutes; a busybox change must not rebuild it).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${NDK:-$HOME/Android/Sdk/ndk/29.0.14206865}
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CACHE=${KABUK_TOOLS_CACHE:-$HOME/.cache/kabuk-tools}
ABIS=${ABIS:-"arm64-v8a x86_64"}
JOBS=$(nproc 2>/dev/null || echo 4)
# busybox is linked dynamically against bionic (libc.so via /system/bin/linker64): only then does getaddrinfo go
# through Android's resolver (netd), so wget/nc/telnet can resolve names; a static binary has no DNS on a phone.
# 28 because busybox needs glob/getrandom/syncfs (API 28): busybox needs Android 9+, the app's minSdk 26 stays
# (on 8.x the self-test reports busybox FAILED and the Phone tab uses /system/bin/sh, see docs/LOCAL-TOOLS.md).
BUSYBOX_API=28

BUSYBOX_VER=1.37.0
BUSYBOX_URL="https://busybox.net/downloads/busybox-$BUSYBOX_VER.tar.bz2"
BUSYBOX_SHA256=3311dff32e746499f4df0d5df04d7eb396382d7e108bb9250e7b519b837043a4

# rclone: the release's source tarball plus its vendor tarball (all Go modules), both listed in the signed
# https://downloads.rclone.org/v$RCLONE_VER/SHA256SUMS, so the build needs no module downloads (-mod=vendor).
RCLONE_VER=1.76.0
RCLONE_URL="https://downloads.rclone.org/v$RCLONE_VER/rclone-v$RCLONE_VER.tar.gz"
RCLONE_SHA256=fa2b2e4ae56c4f1b91102285b736596e2a028be77cb2aaa5a0df53f3a7fc5902
RCLONE_VENDOR_URL="https://downloads.rclone.org/v$RCLONE_VER/rclone-v$RCLONE_VER-vendor.tar.gz"
RCLONE_VENDOR_SHA256=7706e9d6b11971a096406a1e876db3946c73f11a64327a7457545443b13f4cc0
# rclone is linked dynamically against bionic (GOOS=android + cgo): only then does name resolution go through
# Android's resolver (netd); a static linux build has no /etc/resolv.conf on a phone. 26 = the app's minSdk.
RCLONE_API=26
GO=${GO:-$HOME/.local/go/bin/go}

# Applets switched off on top of busybox's defconfig: they do not build against bionic, or need root/setuid,
# a console, utmp or kernel modules, none of which an Android app has. Plus every applet in the source dirs below.
# USE_BB_PWD_GRP off: bionic's getpwuid knows Android's ids (u0_a150), busybox's own reads a missing /etc/passwd.
# SHA*_HWACCEL off: 1.37.0's x86 SHA-NI code does not build for arm64.
BUSYBOX_OFF="HUSH SHELL_HUSH MOUNT UMOUNT SWAPON SWAPOFF FSCK_MINIX MKFS_MINIX IPCS IPCRM TC IFCONFIG ARP IFENSLAVE ETHER_WAKE ADJTIMEX
  RUNLEVEL CONSPY HOSTID LOGNAME WHO W USERS LAST WALL FEATURE_UTMP FEATURE_WTMP FEATURE_SUID NSLOOKUP
  SHA1_HWACCEL SHA256_HWACCEL USE_BB_PWD_GRP USE_BB_SHADOW FEATURE_SHADOWPASSWDS
  FDISK MKSWAP SWITCH_ROOT PIVOT_ROOT CHROOT RDATE HWCLOCK RTCWAKE LOSETUP FREERAMDISK FBSET FBSPLASH
  MDEV UEVENT ACPID NAMEIF ARPING ZCIP UDHCPC UDHCPD DHCPRELAY DUMPLEASES UDHCPC6 BRCTL VCONFIG
  INETD HTTPD FTPD TFTPD TELNETD DNSD NTPD SENDMAIL POPMAILDIR REFORMIME MAKEMIME CROND CRONTAB
  LPD LPR LPQ BEEP CHAT MICROCOM SETSERIAL STTY_EXTRAS UBIATTACH UBIDETACH UBIMKVOL UBIRMVOL UBIRSVOL
  UBIUPDATEVOL UBIRENAME NANDWRITE NANDDUMP FLASHCP FLASH_ERASEALL FLASH_LOCK FLASH_UNLOCK"
BUSYBOX_OFF_DIRS="console-tools init loginutils modutils sysklogd runit mailutils printutils selinux klibc-utils"
BUSYBOX_KEEP="CLEAR RESET"   # from console-tools, plain escape sequences

die() { echo "build-tools: $*" >&2; exit 1; }
[ -x "$TC/clang" ] || die "NDK clang not found at $TC (set NDK=)"

triple() {
  case $1 in
    arm64-v8a) echo aarch64-linux-android ;;
    x86_64) echo x86_64-linux-android ;;
    *) die "unknown ABI $1" ;;
  esac
}

# fetch <url> <sha256>: download once into the cache and verify; prints the local path
fetch() {
  local f="$CACHE/src/$(basename "$1")"
  mkdir -p "$CACHE/src"
  if [ ! -f "$f" ]; then curl -fsSL -o "$f.part" "$1" && mv "$f.part" "$f"; fi
  echo "$2  $f" | sha256sum -c --quiet - >&2 || { rm -f "$f"; die "SHA-256 mismatch for $1"; }
  echo "$f"
}

# tool_stamp <build function> <setting var>...: hash of the function's code, the named settings and the NDK path
tool_stamp() {
  local f=$1 v; shift
  { declare -f "$f" triple; for v in "$@"; do echo "$v=${!v}"; done; echo "NDK=$NDK"; } | sha256sum | cut -c1-16
}

build_busybox() {
  local abi=$1 out="$CACHE/busybox-$BUSYBOX_VER-$1"
  local stamp; stamp=$(tool_stamp build_busybox BUSYBOX_VER BUSYBOX_URL BUSYBOX_SHA256 BUSYBOX_API BUSYBOX_OFF BUSYBOX_OFF_DIRS BUSYBOX_KEEP)
  if [ "$(cat "$out/stamp" 2>/dev/null)" = "$stamp" ] && [ -f "$out/libbusybox.so" ]; then return; fi
  local tar; tar=$(fetch "$BUSYBOX_URL" "$BUSYBOX_SHA256")
  local work="$CACHE/work/busybox-$abi"
  rm -rf "$work" "$out"; mkdir -p "$work" "$out"
  tar xjf "$tar" -C "$work" --strip-components=1
  local mk=(make -C "$work" -j"$JOBS" HOSTCC=gcc "CC=$TC/clang --target=$(triple "$abi")$BUSYBOX_API"
    "AR=$TC/llvm-ar" "NM=$TC/llvm-nm" "STRIP=$TC/llvm-strip" "OBJCOPY=$TC/llvm-objcopy" "OBJDUMP=$TC/llvm-objdump"
    KCONFIG_NOTIMESTAMP=1 SOURCE_DATE_EPOCH=0 KBUILD_BUILD_USER=kabuk KBUILD_BUILD_HOST=kabuk)
  "${mk[@]}" defconfig >/dev/null
  local off=$BUSYBOX_OFF d
  for d in $BUSYBOX_OFF_DIRS; do
    off+=" $(sed -n 's,^//config:config \([A-Z0-9_]*\).*,\1,p' "$work/$d"/*.c 2>/dev/null | tr '\n' ' ')"
  done
  local c
  for c in $off; do
    case " $BUSYBOX_KEEP " in *" $c "*) continue ;; esac
    sed -i "s/^CONFIG_$c=y$/# CONFIG_$c is not set/" "$work/.config"
  done
  sed -i 's/^CONFIG_STATIC=y$/# CONFIG_STATIC is not set/' "$work/.config"
  "${mk[@]}" oldconfig </dev/null >/dev/null
  grep -q '^CONFIG_STATIC=y' "$work/.config" && die "busybox: must link dynamically (DNS through netd)"
  "${mk[@]}" busybox >"$work/build.log" 2>&1 || { tail -30 "$work/build.log" >&2; die "busybox $abi failed (log: $work/build.log)"; }
  "$TC/llvm-strip" -o "$out/libbusybox.so" "$work/busybox_unstripped"
  cp "$work/.config" "$out/busybox.config"
  echo "$stamp" >"$out/stamp"
  rm -rf "$work"
}

goarch() {
  case $1 in
    arm64-v8a) echo arm64 ;;
    x86_64) echo amd64 ;;
  esac
}

build_rclone() {
  local abi=$1 out="$CACHE/rclone-$RCLONE_VER-$1"
  local stamp; stamp=$(tool_stamp build_rclone RCLONE_VER RCLONE_URL RCLONE_SHA256 RCLONE_VENDOR_URL RCLONE_VENDOR_SHA256 RCLONE_API GO)
  if [ "$(cat "$out/stamp" 2>/dev/null)" = "$stamp" ] && [ -f "$out/librclone.so" ]; then return; fi
  [ -x "$GO" ] || die "go not found at $GO (set GO=)"
  local tar vendor; tar=$(fetch "$RCLONE_URL" "$RCLONE_SHA256"); vendor=$(fetch "$RCLONE_VENDOR_URL" "$RCLONE_VENDOR_SHA256")
  local work="$CACHE/work/rclone-$abi"
  rm -rf "$work" "$out"; mkdir -p "$work" "$out"
  tar xzf "$tar" -C "$work" --strip-components=1
  tar xzf "$vendor" -C "$work"
  # -trimpath + empty buildid + fixed prefix map: the same inputs give the same bytes.
  ( cd "$work" && env -u GOFLAGS GOOS=android GOARCH="$(goarch "$abi")" CGO_ENABLED=1 \
      CC="$TC/clang --target=$(triple "$abi")$RCLONE_API" CGO_CFLAGS="-O2 -ffile-prefix-map=$work=." \
      GOCACHE="$CACHE/gocache" GOTOOLCHAIN=local GOPROXY=off SOURCE_DATE_EPOCH=0 \
      "$GO" build -mod=vendor -trimpath -buildvcs=false -o "$out/librclone.so" \
      -ldflags "-s -w -buildid= -X github.com/rclone/rclone/fs.Version=v$RCLONE_VER" . ) \
    >"$work/build.log" 2>&1 || { tail -30 "$work/build.log" >&2; die "rclone $abi failed (log: $work/build.log)"; }
  cp "$work/COPYING" "$out/COPYING"
  echo "$stamp" >"$out/stamp"
  rm -rf "$work"
}

# kabuk-open (xdg-open/open/termux-open-url): Kabuk's own few lines of C, scripts/tools/kabuk-open.c. A program, not a
# #!/system/bin/sh script, because the app may not exec a script it wrote into filesDir either (docs/LOCAL-TOOLS.md).
build_kabuk_open() {
  local abi=$1 out="$CACHE/kabuk-open-$1"
  local src="$ROOT/scripts/tools/kabuk-open.c"
  local stamp; stamp="$(tool_stamp build_kabuk_open RCLONE_API) $(sha256sum "$src" | cut -c1-16)"
  if [ "$(cat "$out/stamp" 2>/dev/null)" = "$stamp" ] && [ -f "$out/libkabuk-open.so" ]; then return; fi
  rm -rf "$out"; mkdir -p "$out"
  "$TC/clang" --target="$(triple "$abi")$RCLONE_API" -O2 -Wall -Werror -fPIE -pie -s \
    -ffile-prefix-map="$ROOT=." -o "$out/libkabuk-open.so" "$src"
  echo "$stamp" >"$out/stamp"
}

# The notices of the Go modules rclone is built with, shown in Settings -> Licences (same for every ABI).
rclone_notices() {
  local vendor work="$CACHE/work/rclone-notices"
  vendor=$(fetch "$RCLONE_VENDOR_URL" "$RCLONE_VENDOR_SHA256")
  rm -rf "$work"; mkdir -p "$work"
  tar xzf "$vendor" -C "$work"
  python3 -I "$ROOT/scripts/tools/third-party-notices.py" rclone "$work/vendor" \
    "$ROOT/app/src/main/assets/licenses/rclone-THIRD-PARTY.txt"
  rm -rf "$work"
}

for abi in $ABIS; do
  build_busybox "$abi"
  build_rclone "$abi"
  build_kabuk_open "$abi"
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  cp "$CACHE/busybox-$BUSYBOX_VER-$abi/libbusybox.so" "$ROOT/app/src/main/jniLibs/$abi/libbusybox.so"
  cp "$CACHE/rclone-$RCLONE_VER-$abi/librclone.so" "$ROOT/app/src/main/jniLibs/$abi/librclone.so"
  cp "$CACHE/kabuk-open-$abi/libkabuk-open.so" "$ROOT/app/src/main/jniLibs/$abi/libkabuk-open.so"
  for t in busybox rclone kabuk-open; do
    echo "build-tools: $abi $t ($(stat -c %s "$ROOT/app/src/main/jniLibs/$abi/lib$t.so") bytes)"
  done
done
rclone_notices
