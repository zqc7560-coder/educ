@echo off
rem ============================================================
rem Docker 版一键全启（毕业演示推荐路线）：
rem   Docker 引擎检查/拉起 -> 前端 dist 缺失则自动构建 ->
rem   compose up -d --build（mysql/redis/app/nginx 四容器，healthcheck 级联）->
rem   探活 http://127.0.0.1:8088/api/health -> 打开浏览器
rem 首次空卷启动会自动灌 schema.sql+data.sql（种子演示数据）。
rem 注意：compose 与原生 start/ 后端勿同时跑（Redis 共享无碍，DB 数据各存各卷）。
rem 本文件以 GBK 编码保存，勿另存为 UTF-8
rem ============================================================
title lf-docker-start
call "%~dp0env.bat"

rem ---- Docker 引擎 ----
docker info >nul 2>&1
if errorlevel 1 (
    echo [提示] Docker 引擎未就绪，尝试拉起 Docker Desktop...
    if exist "C:\Program Files\Docker\Docker\Docker Desktop.exe" start "" "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    setlocal EnableDelayedExpansion
    set RETRY=0
    :waitloop
    ping -n 6 127.0.0.1 >nul
    set /a RETRY+=1
    docker info >nul 2>&1 && goto ready
    if !RETRY! LSS 30 goto waitloop
    echo [失败] Docker 引擎未就绪，请手动打开 Docker Desktop 后重试
    endlocal
    pause & exit /b 1
    :ready
    endlocal
)
echo [OK] Docker 引擎就绪

rem ---- 前端构建产物（nginx 直接挂载 dist，不引 node 构建容器）----
if not exist "%LF_ROOT%\frontend\dist\index.html" (
    echo [提示] 未发现 frontend\dist，先构建生产包（约 1-2 分钟）...
    pushd "%LF_ROOT%\frontend"
    if not exist node_modules call npm install --no-audit --no-fund
    call npm run build
    popd
    if not exist "%LF_ROOT%\frontend\dist\index.html" (echo [失败] 前端构建未产出 dist & pause & exit /b 1)
)
echo [OK] 前端 dist 就绪

rem ---- 起四容器（带 --build：镜像已在则秒级复用缓存）----
if not exist "%LF_ROOT%\deploy\.env" (
    echo [失败] 缺少 deploy\.env（cp deploy\.env.example deploy\.env 后填口令）
    pause & exit /b 1
)
cd /d "%LF_ROOT%\deploy"
docker compose up -d --build
rem 自愈（实测竞态）：MySQL 卷首建期 healthcheck 有"临时实例"误放行窗口，
rem 后端可能抢跑失败——compose 首跑会因依赖报错退出，nginx 停在 Created。
rem 后端容器已配 restart: unless-stopped 会自行重拉；这里无条件再补一次
rem 幂等的 compose up -d，把没被拉起的 nginx 等剩余容器带上。
ping -n 11 127.0.0.1 >nul
docker compose up -d >nul 2>&1

rem ---- 探活：经 nginx 打 /api/health（同时验证 nginx-反代-后端-库 全链）----
echo 等待全栈健康（最长约 3 分钟，含首次镜像构建）...
setlocal EnableDelayedExpansion
set RETRY=0
:probe
ping -n 6 127.0.0.1 >nul
set /a RETRY+=1
curl -s --noproxy "*" -m 5 http://127.0.0.1:8088/api/health | findstr "overall" | findstr UP >nul && goto up
if !RETRY! LSS 30 goto probe
echo [警告] 未在时限内就绪。自动诊断：
docker compose ps --format "table {{.Name}}	{{.Status}}"
docker compose logs lf-server --tail 15
echo 若上列为 Exited/Created，多为 MySQL 首建竞态，重跑本脚本即可
pause & exit /b 1
:up
endlocal
echo 全栈健康：http://127.0.0.1:8088/api/health overall=UP
start "" http://localhost:8088
echo 演示入口 http://localhost:8088 （admin/管理员口令见 deploy\.env 的 LF_ADMIN_PASSWORD）
echo 停止请运行 stop-docker.bat；想连演示数据一起清掉重置：stop-docker.bat -v
ping -n 6 127.0.0.1 >nul
