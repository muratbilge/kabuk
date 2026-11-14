#!/usr/bin/env bash
# Prints the newest Termux bootstrap (apt, android-7) from github.com/termux/termux-packages releases with the
# SHA-256 of each zip Kabuk uses, as Kotlin lines for TermuxEnv.BOOTSTRAP_TAG / TermuxEnv.SHA256
# (app/src/main/java/app/kabuk/term/TermuxEnv.kt). Read-only: it edits nothing.
#   scripts/termux-bootstrap-pin.sh            -> newest tag, digests from the GitHub API
#   VERIFY=1 scripts/termux-bootstrap-pin.sh   -> also downloads both zips (~66 MB, cached in
#                                                 ~/.cache/kabuk-tools/termux/<tag>/) and checks the digests
#   TAG=bootstrap-2026.11.08-r1+apt.android-7 scripts/termux-bootstrap-pin.sh   -> a given tag
set -euo pipefail
ARCHES="aarch64 x86_64"
API=https://api.github.com/repos/termux/termux-packages/releases
CACHE=${KABUK_TOOLS_CACHE:-$HOME/.cache/kabuk-tools}/termux
auth=()
[ -n "${GITHUB_TOKEN:-}" ] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")

json=$(curl -fsSL "${auth[@]}" -H "Accept: application/vnd.github+json" "$API?per_page=30")
lines=$(python3 -I -c '
import json, sys, os
want = os.environ.get("TAG", "")
arches = sys.argv[1].split()
for r in json.loads(sys.stdin.read()):
    tag = r["tag_name"]
    if r.get("draft") or r.get("prerelease"):
        continue
    if want and tag != want:
        continue
    if not want and not (tag.startswith("bootstrap-") and tag.endswith("+apt.android-7")):
        continue
    assets = {a["name"]: a for a in r["assets"]}
    names = ["bootstrap-%s.zip" % a for a in arches]
    if not all(n in assets for n in names):
        continue
    print(tag)
    for a, n in zip(arches, names):
        d = assets[n].get("digest") or ""
        print(a, d.removeprefix("sha256:"), assets[n]["size"], assets[n]["browser_download_url"])
    break
' "$ARCHES" <<<"$json")
[ -n "$lines" ] || { echo "no matching bootstrap release found" >&2; exit 1; }
tag=$(head -1 <<<"$lines")
echo "// $tag ($(date -u +%F), scripts/termux-bootstrap-pin.sh)"
echo "const val BOOTSTRAP_TAG = \"$tag\""
echo "val SHA256 = mapOf("
tail -n +2 <<<"$lines" | while read -r arch sha size url; do
  if [ -z "$sha" ] || [ "${VERIFY:-0}" = 1 ]; then
    d="$CACHE/$tag"; mkdir -p "$d"
    f="$d/bootstrap-$arch.zip"
    if [ ! -s "$f" ]; then curl -fsSL -o "$f.part" "$url"; mv "$f.part" "$f"; fi
    got=$(sha256sum "$f" | cut -d' ' -f1)
    if [ -n "$sha" ] && [ "$got" != "$sha" ]; then echo "SHA-256 mismatch for $arch: API $sha, file $got" >&2; exit 1; fi
    sha=$got
  fi
  echo "    \"$arch\" to \"$sha\", // $((size / 1048576)) MB"
done
echo ")"
