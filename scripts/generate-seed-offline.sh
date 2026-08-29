#!/usr/bin/env bash
# 离线兜底：不联网，用 ffmpeg 现场合成演示媒体。
# 可以在容器里跑（镜像里已经有 ffmpeg）：
#   docker compose run --rm --user root --entrypoint bash app /seed/generate-seed-offline.sh
# 也可以在本机跑（要求本机有 ffmpeg）。
set -euo pipefail

MEDIA="${MEDIA_ROOT:-/media}"
command -v ffmpeg >/dev/null || { echo "找不到 ffmpeg"; exit 1; }

make_clip() {   # $1=输出路径 $2=秒数 $3=左上角文字
  mkdir -p "$(dirname "$1")"
  [ -f "$1" ] && { echo "跳过（已存在）：$1"; return 0; }
  ffmpeg -y -loglevel error \
    -f lavfi -i "testsrc=size=640x360:rate=24:duration=$2" \
    -f lavfi -i "sine=frequency=440:duration=$2" \
    -vf "drawtext=text='$3':fontcolor=white:fontsize=28:x=24:y=24" \
    -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$1"
  echo "已生成：$1"
}

# 一部"电影"
make_clip "$MEDIA/video/电影/演示影片 (2026)/演示影片.mp4" 20 "DEMO MOVIE"

# 一部三集"剧集"——文件名刻意用 S01E01 形式，用来演示解析与分组
for e in 1 2 3; do
  make_clip "$MEDIA/video/剧集/Blender 演示剧集/Season 01/S01E0${e} - 第 ${e} 集.mp4" 15 "S01E0${e}"
done

# 一个散图目录（不打 CBZ：容器里没有 zip，而图片域本来就原生支持散图目录）
DIR="$MEDIA/image/图集/演示图集"
mkdir -p "$DIR"
made_any=0
for p in $(seq -w 1 12); do
  [ -f "$DIR/$p.jpg" ] && continue
  ffmpeg -y -loglevel error -f lavfi -i "testsrc=size=800x1200:rate=1:duration=1" \
    -vf "drawtext=text='PAGE $p':fontcolor=white:fontsize=64:x=(w-tw)/2:y=(h-th)/2" \
    -frames:v 1 "$DIR/$p.jpg"
  made_any=1
done
if [ "$made_any" = 1 ]; then
  echo "已生成：$DIR（12 页散图）"
else
  echo "跳过（已存在）：$DIR（12 页散图）"
fi

echo
echo "完成。离线演示数据就绪。"
