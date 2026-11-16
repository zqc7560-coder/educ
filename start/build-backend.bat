@echo off
rem ============================================================
rem 构建后端 jar（跳过测试，约 1-3 分钟）
rem 本文件以 GBK 编码保存，勿另存为 UTF-8
rem ============================================================
title lf-build
call "%~dp0env.bat"
cd /d "%LF_ROOT%\backend"
echo ==== mvn package -DskipTests ====
call "%LF_MVN%" -q package -DskipTests
if errorlevel 1 (echo [失败] 构建未通过 & pause & exit /b 1)
echo [OK] 产物：backend\target\lf-server.jar
exit /b 0
