@echo off
rem ============================================================
rem 启动前端 Vite 开发服务器（:5174，/api 自动代理到 :8081）
rem 本文件以 GBK 编码保存，勿另存为 UTF-8
rem ============================================================
title lf-frontend
call "%~dp0env.bat"
set NO_PROXY=localhost,127.0.0.1
set no_proxy=localhost,127.0.0.1
cd /d "%LF_ROOT%\frontend"
if not exist node_modules (
    echo [提示] 首次运行，安装依赖（npmmirror 源）...
    call npm install --no-audit --no-fund || (echo [失败] npm install & pause & exit /b 1)
)
echo ==== 前端启动中：http://localhost:%LF_FRONTEND_PORT% ====
call npm run dev
pause
