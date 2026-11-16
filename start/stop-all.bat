@echo off
rem ============================================================
rem 停止本项目后端/前端进程（按命令行特征杀，绝不误伤其它 java/node；
rem 不动 MySQL 服务与 Redis 共享实例）
rem 本文件以 GBK 编码保存，勿另存为 UTF-8
rem ============================================================
title lf-stop-all
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-procs.ps1"
echo 已停止 lf 后端/前端（无匹配进程属正常）。MySQL/Redis 为共享服务不在此停
pause
