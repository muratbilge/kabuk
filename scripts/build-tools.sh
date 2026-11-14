#!/usr/bin/env bash
# Build the Phone tab's tools (static binaries) and put them where Gradle packs them:
#   app/src/main/jniLibs/<abi>/lib<name>.so   (gitignored; extracted at install to nativeLibraryDir, the one
#                                             place a targetSdk-35 app may exec its own files, see docs/LOCAL-TOOLS.md)
#   scripts/build-tools.sh            -> all tools, arm64-v8a + x86_64
#   ABIS="arm64-v8a" scripts/build-tools.sh
# Sources are pinned (version + SHA-256 below) and built with the SDK's NDK. Results are cached in
# ~/.cache/kabuk-tools/<tool>-<ver>-<abi>/ together with a stamp of this script, so a re-run is a copy
# and an edit of this script (config, flags) rebuilds.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${NDK:-$HOME/Android/Sdk/ndk/29.0.14206865}
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CACHE=${KABUK_TOOLS_CACHE:-$HOME/.cache/kabuk-tools}
ABIS=${ABIS:-"arm64-v8a x86_64"}
JOBS=$(nproc 2>/dev/null || echo 4)
# Static binaries carry their own libc.a, so the API level only selects which bionic headers are visible; it does
# not raise the app's minSdk (26). 35 is the newest platform the NDK ships and has glob/getrandom/syncfs.
API=35

BUSYBOX_VER=1.37.0
BUSYBOX_URL="https://busybox.net/downloads/busybox-$BUSYBOX_VER.tar.bz2"
BUSYBOX_SHA256=3311dff32e746499f4df0d5df04d7eb396382d7e108bb9250e7b519b837043a4

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

STAMP=$(sha256sum "$0" | cut -c1-16)

build_busybox() {
  local abi=$1 out="$CACHE/busybox-$BUSYBOX_VER-$1"
  if [ "$(cat "$out/stamp" 2>/dev/null)" = "$STAMP" ] && [ -f "$out/libbusybox.so" ]; then return; fi
  local tar; tar=$(fetch "$BUSYBOX_URL" "$BUSYBOX_SHA256")
  local work="$CACHE/work/busybox-$abi"
  rm -rf "$work" "$out"; mkdir -p "$work" "$out"
  tar xjf "$tar" -C "$work" --strip-components=1
  # busybox defines getsid/sethostname/adjtimex and strchrnul itself for old Android; bionic has them (API 21/24)
  # and a static link then sees them twice. Limit busybox's copies to older APIs (our only source changes).
  sed -i -e 's,^# include <sys/syscall.h>$,&\n# if __ANDROID_API__ < 21,' \
    -e 's,^int pivot_root(,# endif\n&,' "$work/libbb/missing_syscalls.c"
  sed -i 's,^# undef HAVE_STRCHRNUL$,# if __ANDROID_API__ < 24\n&\n# endif,' "$work/include/platform.h"
  local mk=(make -C "$work" -j"$JOBS" HOSTCC=gcc "CC=$TC/clang --target=$(triple "$abi")$API"
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
  sed -i 's/^# CONFIG_STATIC is not set$/CONFIG_STATIC=y/' "$work/.config"
  "${mk[@]}" oldconfig </dev/null >/dev/null
  grep -q '^CONFIG_STATIC=y' "$work/.config" || die "busybox: static build not configured"
  "${mk[@]}" busybox >"$work/build.log" 2>&1 || { tail -30 "$work/build.log" >&2; die "busybox $abi failed (log: $work/build.log)"; }
  "$TC/llvm-strip" -o "$out/libbusybox.so" "$work/busybox_unstripped"
  cp "$work/.config" "$out/busybox.config"
  echo "$STAMP" >"$out/stamp"
  rm -rf "$work"
}

for abi in $ABIS; do
  build_busybox "$abi"
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  cp "$CACHE/busybox-$BUSYBOX_VER-$abi/libbusybox.so" "$ROOT/app/src/main/jniLibs/$abi/libbusybox.so"
  echo "build-tools: $abi busybox $BUSYBOX_VER ($(stat -c %s "$ROOT/app/src/main/jniLibs/$abi/libbusybox.so") bytes)"
done
