#!/usr/bin/env bash
# 诊断日志三区间分列：先取 GitHub 最新正式版的 versionCode 当基线，
# 再把 D1 里的记录盖成 below / release / dev 三档。
#
# 用法：./tools/bands.sh [--include-dev]
set -euo pipefail
cd "$(dirname "$0")/.."

INCLUDE_DEV=0
[ "${1:-}" = "--include-dev" ] && INCLUDE_DEV=1

REPO="Xposed-Modules-Repo/io.github.wumisc.safewalk"
BASELINE=$(gh release list --repo "$REPO" --limit 1 --json tagName --jq '.[0].tagName' | sed 's/^\([0-9]*\).*/\1/')
[ -z "$BASELINE" ] && { echo "取不到基线（GitHub 上没有正式 Release？）" >&2; exit 1; }
echo "基线（最新正式版 versionCode）：$BASELINE"

WHERE=""
[ "$INCLUDE_DEV" = "0" ] && WHERE="WHERE COALESCE(is_dev,0) = 0"

SQL=$(cat <<SQL2
SELECT id, ts, version_name, version_code, manufacturer, model,
       COALESCE(is_dev,0) AS is_dev,
       CASE
         WHEN version_code < $BASELINE THEN 'below'
         WHEN COALESCE(is_dev,0) = 1 THEN 'dev'
         ELSE 'release'
       END AS band,
       substr(log_hash,1,8) AS code
  FROM diag_reports
  $WHERE
 ORDER BY model, version_code, ts;
SQL2
)

export CLOUDFLARE_ACCOUNT_ID=${CLOUDFLARE_ACCOUNT_ID:-9e9db96a0aa466895dcaeb223d3176a7}
npx wrangler d1 execute safewalk-diag --remote --json --command "$SQL" 2>/dev/null \
 | python3 -c '
import json,sys
rows=json.load(sys.stdin)[0]["results"]
for r in rows:
    print("%-14s v%-4s %-8s %-10s %s" % (r["model"][:14], r["version_code"], r["band"], r["code"], r["version_name"]))
print("共 %d 条" % len(rows))
'
