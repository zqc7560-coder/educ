@echo off
rem ============================================================
rem 基础设施：MySQL(本机服务, 已在跑则跳过) + Redis(本机exe, 未跑则拉起)
rem 库表为空时自动灌 schema.sql + data.sql（两脚本幂等，可重复执行）
rem 本文件以 GBK 编码保存，勿另存为 UTF-8
rem ============================================================
title lf-infra
call "%~dp0env.bat"

rem ---- MySQL 端口探测 ----
netstat -ano | findstr ":%LF_DB_PORT% " | findstr LISTEN >nul
if errorlevel 1 (
    echo [失败] MySQL %LF_DB_HOST%:%LF_DB_PORT% 未监听，请先启动本机 MySQL 服务后重试
    pause & exit /b 1
)
echo [OK] MySQL :%LF_DB_PORT% 已在运行

rem ---- 建库灌种子（库内无表才执行；中文必须带 utf8mb4 参数防乱码）----
echo SHOW TABLES; | "%LF_MYSQL_BIN%\mysql.exe" -h %LF_DB_HOST% -u%LF_DB_USER% -p%LF_DB_PASSWORD% --default-character-set=utf8mb4 -N lost_found_db 2>nul | findstr /r "." >nul
if errorlevel 1 (
    echo [提示] lost_found_db 不存在或为空库，正在初始化 schema+data ...
    "%LF_MYSQL_BIN%\mysql.exe" -h %LF_DB_HOST% -u%LF_DB_USER% -p%LF_DB_PASSWORD% --default-character-set=utf8mb4 < "%LF_ROOT%\backend\src\main\resources\db\mysql\schema.sql" || (echo [失败] schema.sql & pause & exit /b 1)
    "%LF_MYSQL_BIN%\mysql.exe" -h %LF_DB_HOST% -u%LF_DB_USER% -p%LF_DB_PASSWORD% --default-character-set=utf8mb4 < "%LF_ROOT%\backend\src\main\resources\db\mysql\data.sql" || (echo [失败] data.sql & pause & exit /b 1)
    echo [OK] 数据库初始化完成
) else (
    echo [OK] lost_found_db 已就绪
)

rem ---- Redis 端口探测 / 拉起 ----
netstat -ano | findstr ":%LF_REDIS_PORT% " | findstr LISTEN >nul
if errorlevel 1 (
    echo 正在启动本机 Redis :%LF_REDIS_PORT% ...
    start "lf-redis" /min cmd /c ""%LF_REDIS_DIR%\redis-server.exe" --port %LF_REDIS_PORT%"
    ping -n 4 127.0.0.1 >nul
    "%LF_REDIS_DIR%\redis-cli.exe" -p %LF_REDIS_PORT% ping | findstr PONG >nul
    if errorlevel 1 (echo [失败] Redis 未能启动 & pause & exit /b 1)
)
echo [OK] Redis :%LF_REDIS_PORT% 已就绪
exit /b 0
