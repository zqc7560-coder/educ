#!/usr/bin/env bash
# =====================================================================
# 每日数据库备份（M12 / 运维项）
# 部署位置：目标机 /usr/local/bin/lf-backup.sh，crontab 每日 03:00：
#   0 3 * * * /usr/local/bin/lf-backup.sh >> /var/log/lf-backup.log 2>&1
#
# 设计点（博客 M12/M13 真机验证过的四条纪律照搬）：
# 1) --single-transaction：InnoDB 一致性快照，不锁表（备份期间系统照常读写）。
# 2) 口令不进命令行明文：compose exec 从容器内环境变量取，ps 列表无痕。
# 3) 保留 7 份按文件名日期排序删除（cp 会刷新 mtime，find -mtime 不可靠）。
# 4) 双重体检：gzip -t + "MySQL dump" 头校验（首行是 "-- MySQL dump 10.13等参数"
#    不是字面 mysqldump——博客冒烟抓过的误杀 bug），只留能解压出来的备份。
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="${LF_DEPLOY_DIR:-$(dirname "$SCRIPT_DIR")}"
BACKUP_DIR="${LF_BACKUP_DIR:-${HOME}/lf_backups}"
RETAIN_DAYS=7
STAMP="$(date +%F_%H%M)"
OUT="${BACKUP_DIR}/lf_${STAMP}.sql.gz"

mkdir -p "${BACKUP_DIR}"

echo "[lf-backup] $(date '+%F %T') 开始备份 lost_found_db -> ${OUT}"
( cd "${COMPOSE_DIR}" && docker compose exec -T mysql \
    sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
           --single-transaction --routines --events --default-character-set=utf8mb4 lost_found_db' \
) | gzip > "${OUT}"

if ! gzip -t "${OUT}"; then
  echo "[lf-backup] 失败：gzip 校验不通过，删除坏包 ${OUT}" >&2
  rm -f "${OUT}"
  exit 1
fi
if ! zcat "${OUT}" | head -c 4096 | grep -q -- "MySQL dump"; then
  echo "[lf-backup] 失败：备份内容异常（缺 mysqldump 头），删除 ${OUT}" >&2
  rm -f "${OUT}"
  exit 1
fi

ls -1 "${BACKUP_DIR}"/lf_*.sql.gz 2>/dev/null | sort | head -n -"${RETAIN_DAYS}" | while read -r old; do
  echo "[lf-backup] 清理过期备份：${old}"
  rm -f "${old}"
done

SIZE=$(du -h "${OUT}" | cut -f1)
echo "[lf-backup] 完成：${OUT} (${SIZE})，保留最近 ${RETAIN_DAYS} 份"
