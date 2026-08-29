#!/usr/bin/env bash
# 空库时用项目自己的公开 API 建两个演示媒体库并触发扫描。幂等。
# 由 compose 的 demo-seed 服务在 app 健康之后运行一次。
set -euo pipefail

BASE="${BASE_URL:-http://app:8080}"
USER="${ADMIN_USER:-admin}"
PASS="${ADMIN_PASS:-admin}"

api() { curl -fsS -u "$USER:$PASS" -H 'Content-Type: application/json' "$@"; }

echo "等待 $BASE 就绪…"
for i in $(seq 1 60); do
  if curl -fsS "$BASE/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 2
done

existing="$(api "$BASE/api/libraries")"

create_and_scan() {   # $1=库名 $2=域 $3=根路径
  # 没有 jq 也要能判断：库名是我们自己定的固定字符串，grep 足够可靠
  if printf '%s' "$existing" | grep -q "\"name\":\"$1\""; then
    echo "跳过（已存在）：$1"
    return 0
  fi
  echo "建库：$1（$2 → $3）"
  local created
  created="$(api -X POST "$BASE/api/libraries" \
    -d "{\"name\":\"$1\",\"domain\":\"$2\",\"rootPath\":\"$3\"}")"
  # 响应形如 {"id":1,"name":"演示视频库","domain":"VIDEO","rootPath":"/media/video","enabled":true}
  local id
  id="$(printf '%s' "$created" | sed -n 's/.*"id":\([0-9]\+\).*/\1/p')"
  [ -n "$id" ] || { echo "没能从响应里解析出库 id：$created" >&2; exit 1; }
  # 必须在触发扫描之前启用本地元数据刮削器：MetadataEventListener 在条目创建的
  # 那一刻就判定"库没配刮削器 → NOT_APPLICABLE"（libraries.metadata_providers
  # 默认是空数组，见 V3__libraries.sql），而 NOT_APPLICABLE 是终态——之后再补配
  # 刮削器、哪怕再扫一遍，itemsPendingScrape 也只捞 PENDING，不会回头认领已经
  # 定型的条目。顺序反了，.nfo / metadata.json 里写的中文简介就永远进不了条目详情。
  echo "启用本地元数据刮削（.nfo / metadata.json）：库 id=$id"
  api -X PUT "$BASE/api/libraries/$id/metadata-providers" \
    -d '{"providers":["LocalNfo"]}' >/dev/null
  echo "触发扫描：库 id=$id"
  api -X POST "$BASE/api/libraries/$id/scan" >/dev/null
}

create_and_scan "演示视频库" "VIDEO" "/media/video"
create_and_scan "演示图片库" "IMAGE" "/media/image"

echo "引导完成。"
