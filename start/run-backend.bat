@echo off
rem ============================================================
rem 启动后端 Spring Boot（:8081）。jar 缺失时自动构建。
rem 开发默认参数已在 application.yml（root库/Redis db1/dev密钥），
rem 如要覆盖可 set LF_DB_PASSWORD 等环境变量后再启动。
rem 本文件以 GBK 编码保存，勿另存为 UTF-8
rem ============================================================
title lf-backend
call "%~dp0env.bat"
set NO_PROXY=localhost,127.0.0.1
set no_proxy=localhost,127.0.0.1
if not exist "%LF_ROOT%\backend\target\lf-server.jar" (
    echo [提示] 未找到 lf-server.jar，先构建（约 1-3 分钟）...
    call "%~dp0build-backend.bat" || (pause & exit /b 1)
)
cd /d "%LF_ROOT%\backend"
echo ==== 后端启动中：http://localhost:%LF_BACKEND_PORT% （本窗口为实时日志）====
java -jar target\lf-server.jar
echo ==== 后端进程已退出，窗口保留供查看日志 ====
pause
