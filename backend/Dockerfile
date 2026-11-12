# =====================================================================
# 校园失物招领系统后端 · 生产镜像（M12 部署件）
# 蓝本：博客毕设 M12/M13 定稿版（真机三连构建数据验证过），差异点：
#   jar 名 lf-server.jar（pom finalName）/ 端口 8081（避与博客 8080 同机冲突）
#
# 注意：**故意不加** #syntax=docker/dockerfile:1 头——本机 Engine 29.7 的
# 内建前端原生支持 --mount=type=cache；syntax 行会让每次 build 先去 docker.io
# 拉前端镜像，国内无代理直接超时炸构建（博客 M13 首跑真实踩中）。
#
# 构建上下文 = backend/（compose 里 build.context: ../backend）。
# =====================================================================

# ---------- 阶段一：构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# settings.xml 必须落在 cache mount 挂载点**之外**（/root/.m2/settings.xml，
# 而挂载目标是 /root/.m2/repository 子目录）——挂整目录会遮蔽镜像层文件，
# 改动静默失效（博客 M13 实测陷阱）。
COPY docker/settings-aliyun.xml /root/.m2/settings.xml

# 单次 mvn 打包（无 go-offline：cache mount 承担跨构建复用后 go-offline
# 是纯冗余——博客真机数据：暖缓存仍要 397.8s）。跳过测试执行：接口/单测在
# CI 与本地 H2 全量跑过，镜像构建期重跑只拖慢一倍；覆盖率门禁在 CI。
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -DskipTests package \
    && cp target/lf-server.jar /build/app.jar

# ---------- 阶段二：运行 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# 非 root 运行（最小权限原则，答辩可讲）：预建 uploads/logs 并 chown，
# 配合 compose 命名卷（卷属主问题见 deploy/README §4）。
RUN useradd -r -u 10001 lfuser && mkdir -p uploads logs && chown -R lfuser:lfuser /app
USER lfuser

COPY --from=builder /build/app.jar app.jar

EXPOSE 8081

# -XX:MaxRAMPercentage=75：容器内存上限联动（compose 给 512m）；
# ExitOnOutOfMemoryError：OOM 退出交给 compose 重启策略，不留半死进程骗健康检查。
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
