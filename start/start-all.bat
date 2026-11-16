@echo off
rem ============================================================
rem 一键全启：基础设施(阻塞至就绪) -> 后端窗口 -> 前端窗口 -> 探活 -> 打开浏览器
rem 单独启动某服务请直接双击对应 run-*.bat
rem 本文件以 GBK 编码保存，勿另存为 UTF-8
rem ============================================================
title lf-start-all
call "%~dp0env.bat"
call "%~dp0run-infra.bat" || exit /b 1
start "lf-backend" cmd /k ""%~dp0run-backend.bat""
start "lf-frontend" cmd /k ""%~dp0run-frontend.bat""
echo 等待后端健康（最长约 3 分钟，含首次自动构建）...
setlocal EnableDelayedExpansion
set RETRY=0
:probe
ping -n 5 127.0.0.1 >nul
set /a RETRY+=1
curl -s --noproxy "*" -m 5 http://127.0.0.1:%LF_BACKEND_PORT%/api/health | findstr "overall" | findstr UP >nul && goto up
if !RETRY! LSS 40 goto probe
echo [警告] 后端未在时限内就绪，请查看 lf-backend 窗口的报错日志
pause & exit /b 1
:up
endlocal
echo 后端健康：/api/health overall=UP
echo 打开前端 http://localhost:%LF_FRONTEND_PORT%
start "" http://localhost:%LF_FRONTEND_PORT%
echo 全部完成。停止服务请运行 stop-all.bat
ping -n 6 127.0.0.1 >nul
