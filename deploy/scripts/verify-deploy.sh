#!/usr/bin/env bash
# =====================================================================
# 交付验收入口（M12）：一条命令完成 起栈→等健康→冒烟→拆栈→报告。
# 蓝本=博客毕设 verify-deploy.sh（M16 定稿）：等待 healthy 与失败诊断
# （自动 dump 非健康容器日志尾部）内置，任何人一条命令看到"卡在哪、为什么"。
#
# 用法：
#   cd deploy && bash scripts/verify-deploy.sh          # 完整（build+up+冒烟+down）
#   KEEP_UP=1 bash scripts/verify-deploy.sh             # 验完不停栈（继续演示）
#   SKIP_BUILD=1 bash scripts/verify-deploy.sh          # 用现成镜像直接起
#
# 前置：前端产物已 npm run build（compose 直接挂载宿主机 frontend/dist）。
# 退出码：0=全部通过；1=任一步失败（输出指明哪步）。
# =====================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "${HERE}/.." && pwd)"
PROJECT="${COMPOSE_PROJECT:-lf-verify}"
WAIT_SECS="${WAIT_SECS:-300}"
BASE="${BASE:-http://localhost:8088}"

cd "${DEPLOY_DIR}"
command -v docker >/dev/null 2>&1 || { echo "FAIL: docker 不在 PATH"; exit 1; }
[ -f .env ] || { echo "FAIL: 缺少 deploy/.env —— cp .env.example .env 并填密钥（.env 不入库）"; exit 1; }
[ -f ../frontend/dist/index.html ] || { echo "FAIL: 前端未构建 —— cd frontend && npm run build"; exit 1; }

COMPOSE=(docker compose -p "${PROJECT}" -f docker-compose.yml -f docker-compose.prod.yml)

echo "== [1/5] 构建并启动四服务 (project=${PROJECT}) =="
BUILD_FLAG="--build"; [ "${SKIP_BUILD:-0}" = "1" ] && BUILD_FLAG=""
"${COMPOSE[@]}" up -d ${BUILD_FLAG} || { echo "FAIL: compose up"; "${COMPOSE[@]}" logs --tail 30; exit 1; }

echo "== [2/5] 等待四服务 healthy（上限 ${WAIT_SECS}s）=="
deadline=$((SECONDS + WAIT_SECS))
while :; do
  unhealthy=$("${COMPOSE[@]}" ps --format '{{.Name}} {{.Health}}' 2>/dev/null | grep -v healthy | grep -v '^$' || true)
  [ -z "${unhealthy}" ] && break
  [ ${SECONDS} -ge ${deadline} ] && { echo "FAIL: 等待超时，未健康容器：${unhealthy}"
    for c in ${unhealthy}; do echo "---- ${c} 日志尾部 ----"; "${COMPOSE[@]}" logs --tail 20 "${c%%-*}" 2>/dev/null | tail -20; done
    exit 1; }
  sleep 5
done
"${COMPOSE[@]}" ps

echo "== [3/5] 接口冒烟（经 nginx 全链路）=="
FAIL=0
R=$(curl -s -m 15 "${BASE}/api/health" || echo '{}')
echo "${R}" | grep -q '"overall":"UP"' && echo "PASS health 经反代 UP" || { echo "FAIL health"; FAIL=1; }

# 游客链：公告横幅（utf8mb4 中文回显核验——M10 乱码教训的容器侧回归位）
A=$(curl -s -m 15 "${BASE}/api/announcements/active" || echo '{}')
echo "${A}" | grep -q "欢迎使用校园失物招领系统" && echo "PASS 公告种子中文无损(容器 utf8mb4 链路)" || { echo "FAIL 公告内容: ${A}"; FAIL=1; }
# 游客链：字典/列表
curl -s -m 15 "${BASE}/api/categories?kind=ITEM" | grep -q "证件卡类" && echo "PASS 分类字典" || { echo "FAIL 分类字典"; FAIL=1; }
curl -s -m 15 "${BASE}/api/items?pageNum=1" | grep -q '"code":0' && echo "PASS 物品列表" || { echo "FAIL 物品列表"; FAIL=1; }

# 登录链：admin 口令来自 .env（LF_ADMIN_PASSWORD，AdminBootstrapRunner 首启创建）
ADMIN_PWD=$(grep -E '^LF_ADMIN_PASSWORD=' .env | cut -d= -f2-)
T=$(curl -s -m 15 -X POST "${BASE}/api/auth/login" -H 'Content-Type: application/json' \
     -d "{\"studentNo\":\"admin\",\"password\":\"${ADMIN_PWD}\"}" | python -c "import json,sys;print(json.load(sys.stdin).get('data',{}).get('token',''))")
[ -n "${T}" ] && echo "PASS admin 登录(引导器生效)" || { echo "FAIL admin 登录"; FAIL=1; }

if [ -n "${T}" ]; then
  # 治理口：审核队列 + 看板（M8 口的容器态回归）
  curl -s -m 15 "${BASE}/api/admin/audit-queue" -H "Authorization: Bearer ${T}" | grep -q '"code":0' && echo "PASS 审核队列" || { echo "FAIL 审核队列"; FAIL=1; }
  curl -s -m 15 "${BASE}/api/admin/stats/overview" -H "Authorization: Bearer ${T}" | grep -q 'userTotal' && echo "PASS 看板聚合" || { echo "FAIL 看板"; FAIL=1; }
  # 缓存链（M9 容器态）：游客读详情一次 → Redis 键应出现
  FIRST=$(curl -s -m 15 "${BASE}/api/items?pageNum=1" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
  if [ -n "${FIRST}" ]; then
    curl -s -m 15 "${BASE}/api/items/${FIRST}" > /dev/null
    KEYS=$("${COMPOSE[@]}" exec -T redis sh -c 'redis-cli -a "$REDIS_PASS" --no-auth-warning -n 1 --scan --pattern "lf:item:*" | head -3')
    [ -n "${KEYS}" ] && echo "PASS 详情缓存键 lf:item:* 落 Redis(db1)" || echo "WARN 缓存键未见(该物品未发布态则属预期: ${FIRST})"
  fi
fi

# 前端 SPA：history 路由刷新兜底
curl -s -m 15 "${BASE}/admin" | grep -q "<div id=\"app\">" && echo "PASS SPA 路由兜底" || { echo "FAIL SPA 兜底"; FAIL=1; }
# uploads 直出通道（404 也算路由段挂载成功——无文件时预期 404，502 才是链路断）
C404=$(curl -s -o /dev/null -w "%{http_code}" -m 15 "${BASE}/uploads/none.png")
[ "${C404}" = "404" ] && echo "PASS /uploads 反代段" || { echo "FAIL /uploads(${C404})"; FAIL=1; }

echo "== [4/5] 备份脚本体检（真 dump 一次不落盘保留）=="
"${COMPOSE[@]}" exec -T mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --default-character-set=utf8mb4 lost_found_db' \
  | gzip | { gzip -t && echo "PASS mysqldump→gzip 管道可用(backup-mysql.sh 同款链路)"; } || { echo "FAIL 备份管道"; FAIL=1; }

echo "== [5/5] 收尾 =="
if [ "${KEEP_UP:-0}" = "1" ]; then
  echo "栈保留运行中：docker compose -p ${PROJECT} ps ；停栈：docker compose -p ${PROJECT} down"
else
  "${COMPOSE[@]}" down -v >/dev/null 2>&1 && echo "已拆栈清卷（一次性验证模式）"
fi

if [ ${FAIL} -eq 0 ]; then echo "DEPLOY_VERIFY_ALL_PASS"; else echo "DEPLOY_VERIFY_HAS_FAIL"; fi
exit ${FAIL}
