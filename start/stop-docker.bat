@echo off
rem ============================================================
rem 停止并移除 Docker 四容器（保留数据卷，下次 up 数据还在）
rem 带参数 -v 时连卷一起删 = MySQL/上传件全部重置回种子状态：
rem     stop-docker.bat -v
rem 本文件以 GBK 编码保存，勿另存为 UTF-8
rem ============================================================
title lf-docker-stop
call "%~dp0env.bat"
cd /d "%LF_ROOT%\deploy"
if "%~1"=="-v" (
    echo [注意] 将连同数据卷一起删除：MySQL 数据与上传图片全部回到种子初始态
    docker compose down -v
) else (
    docker compose down
)
if errorlevel 1 (echo [失败] compose 停止异常 & pause & exit /b 1)
echo 容器已停止。8088 端口应已释放
pause
