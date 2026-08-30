#!/usr/bin/env bash
# 按 seed/manifest.json 获取演示媒体。幂等：已存在且校验和相符的条目直接跳过。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/seed/manifest.json"
MEDIA="$ROOT/data/media"
NFO="$ROOT/seed/nfo"
UA="MyMedia-seed/0.1 (+https://github.com/kisima0225/MyMedia)"
INTERVAL=2

command -v jq >/dev/null || { echo "需要 jq：https://jqlang.github.io/jq/"; exit 1; }

fetch_one() {
  local url="$1" sha="$2" target="$3"
  local dest="$MEDIA/$target"
  if [ -f "$dest" ] && [ "$(sha256sum "$dest" | cut -d' ' -f1)" = "$sha" ]; then
    echo "跳过（已存在且校验和相符）：$target"
    return 0
  fi
  mkdir -p "$(dirname "$dest")"
  echo "下载：$target"
  curl -fL --retry 3 --retry-delay 5 -A "$UA" -o "$dest.part" "$url"
  local actual
  actual="$(sha256sum "$dest.part" | cut -d' ' -f1)"
  if [ "$actual" != "$sha" ]; then
    rm -f "$dest.part"
    echo "校验和不符：$target" >&2
    echo "  期望 $sha" >&2
    echo "  实际 $actual" >&2
    exit 1
  fi
  mv "$dest.part" "$dest"
  sleep "$INTERVAL"
}

# 1. 逐条下载
while IFS=$'\t' read -r url sha target; do
  fetch_one "$url" "$sha" "$target"
done < <(jq -r '.assets[] | [.url, .sha256, .target] | @tsv' "$MANIFEST")

# 2. 打 CBZ：用已经下载好的公有领域图片现打，仓库里不存归档
while IFS=$'\t' read -r target pages; do
  dest="$MEDIA/$target"
  if [ -f "$dest" ]; then echo "跳过（已存在）：$target"; continue; fi
  mkdir -p "$(dirname "$dest")"
  tmp="$(mktemp -d)"
  i=1
  for id in $pages; do
    src="$MEDIA/$(jq -r --arg id "$id" '.assets[] | select(.id==$id) | .target' "$MANIFEST")"
    cp "$src" "$(printf '%s/%03d.jpg' "$tmp" "$i")"
    i=$((i + 1))
  done
  if command -v zip >/dev/null; then
    (cd "$tmp" && zip -q -r "$dest" .)
  else
    (cd "$tmp" && python3 -c "import shutil,sys; shutil.make_archive(sys.argv[1],'zip','.')" "${dest%.cbz}")
    mv "${dest%.cbz}.zip" "$dest"
  fi
  rm -rf "$tmp"
  echo "已打包：$target"
done < <(jq -r '.archives[] | [.target, (.pages | join(" "))] | @tsv' "$MANIFEST")

# 3. 把我们自己写的 .nfo 复制到媒体目录里（它们必须和媒体文件放在一起）
if [ -d "$NFO" ]; then
  (cd "$NFO" && find . -type f -exec sh -c 'mkdir -p "$0/$(dirname "$1")" && cp "$1" "$0/$1"' "$MEDIA" {} \;)
  echo "已复制 .nfo"
fi

# 以 root 身份跑时（容器里获取种子就是这种情况），把媒体树的属主交给应用容器里的
# uid 1000。不这么做，Web 上传的最后一步会以
#   AccessDeniedException: /media/video/xxx
# 失败——目录是 root:root 755，而 app 以 uid 1000 运行。README 部署清单原本把这条
# 只写成「Linux 宿主上」，实测 Windows + Docker Desktop 同样会踩到，因为决定属主的
# 是"谁创建了这些目录"，不是宿主的操作系统。
if [ "$(id -u)" = "0" ]; then
  chown -R 1000:1000 "$MEDIA" 2>/dev/null || true
fi

echo
echo "完成。媒体目录："
find "$MEDIA" -type f | sort
