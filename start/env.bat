@echo off

rem ============================================================

rem 环境路径与本机开发默认参数集中配置 —— 换机器/换口令只改这个文件

rem （LF_DB_PASSWORD 默认值与 application.yml 的开发默认一致，仅限本机课设）

rem 本文件以 GBK 编码保存，勿另存为 UTF-8

rem ============================================================

set LF_ROOT=D:\项目\毕业设计

set LF_MYSQL_BIN=E:\develop\mysql-9.1.0-winx64\bin

set LF_REDIS_DIR=E:\develop\redis-8.10.1\Redis-8.10.1-Windows-x64-msys2

set LF_MVN=E:\develop\apache-maven-3.9.4\bin\mvn.cmd

set LF_DB_HOST=127.0.0.1

set LF_DB_PORT=3306

set LF_DB_USER=root

set LF_DB_PASSWORD=1234

set LF_REDIS_PORT=6379

set LF_BACKEND_PORT=8081

set LF_FRONTEND_PORT=5174

