#!/usr/bin/env bash
# =====================================================================
# 数据库恢复（M12）：zcat 备份包 → mysql 管道导入
# 用法：lf-restore.sh /path/lf_2026-11-12_0300.sql.gz
#
# 管道导入（不落盘）：备份包含用户散列等敏感数据，能不过磁盘就不过。
# 显式选库不可省：mysqldump 单库模式不生成 USE 语句，导入端不选库
# 第一张表就 ERROR 1046（博客 M12 影子库实跑抓出的 bug，同型预防）。
# =====================================================================
set -euo pipefail

if [ $# -ne 1 ] || [ ! -f "$1" ]; then
  echo "用法: $0 <备份文件.sql.gz>" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="${LF_DEPLOY_DIR:-$(dirname "$SCRIPT_DIR")}"

echo "[lf-restore] 将用 $1 覆盖 lost_found_db —— 该库现有数据会被同名表重建替换。"
if [ -t 0 ]; then
  read -r -p "确认继续？(yes/N) " ans
  [ "$ans" = "yes" ] || { echo "已取消"; exit 1; }
fi

gzip -t "$1"   # 先验包体，坏包直接退出，不执行半截恢复
zcat "$1" | ( cd "${COMPOSE_DIR}" && docker compose exec -T mysql \
  sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 lost_found_db' )

echo "[lf-restore] 完成。建议抽查：docker compose exec mysql sh -c \
'mysql -uroot -p\"$MYSQL_ROOT_PASSWORD\" -e \"select count(*) from lost_found_db.sys_user;\"'"
