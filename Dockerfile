# syntax=docker/dockerfile:1

# ---------- 构建阶段 ----------
# 用官方 maven 镜像而不是 JDK 镜像 + wrapper：本仓库没有 mvnw，
# 而 maven:3.9-eclipse-temurin-25 已实测存在，省掉一份要维护的 wrapper。
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# 先只拷 pom 拉依赖，让依赖层能被后续的源码改动复用。
# -DskipFrontend=true 是必须的：这一步还没有 frontend/，
# 让前端插件跑起来只会失败。
COPY pom.xml ./
RUN mvn -B -ntp -DskipFrontend=true -DskipTests dependency:resolve

# 再拷源码。frontend-maven-plugin 会在这里下载 Node（v24.19.0）、
# 跑 npm ci → npm run test → npm run build，产物由 maven-resources-plugin
# 拷进 target/classes/static/。
COPY src ./src
COPY frontend ./frontend
# -DskipTests 只关后端 surefire（集成测试走 Testcontainers，
# 构建容器里没有 Docker 守护进程，跑不了）。
# 前端 Vitest 绑在 generate-resources 阶段，不受它影响，会照常跑完 68 条。
RUN mvn -B -ntp -DskipTests package

# ---------- 运行阶段 ----------
# eclipse-temurin:25-jre 实测是 Ubuntu 26.04 LTS / OpenJDK 25.0.4+7 / 478 MB。
FROM eclipse-temurin:25-jre AS runtime

# ffmpeg / ffprobe 烘焙进镜像（spec §10 第 1 条），
# curl 供 HEALTHCHECK 与 demo-seed 引导脚本使用。
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 不用 root 跑应用。uid 固定成 1000，方便在 Linux 上对 bind mount 调属主。
#
# 实测发现：eclipse-temurin:25-jre 底层是 Ubuntu 26.04，官方镜像自带一个
# uid=1000/gid=1000 的默认账号 "ubuntu"（云镜像惯例，非 root 但已经占了
# 我们要用的 uid），不删的话 `useradd --uid 1000` 会以 exit code 4 失败
# （"UID 1000 is not unique"）。先删掉这个自带账号，再按原计划把 uid 1000
# 固定给 mymedia。
#
# 这里先判断存在再删：`25-jre` 是浮动 tag，将来某次 docker build 拉到的新版
# 基础镜像未必还带这个自带账号，而 `userdel` 删不存在的用户会返回非零退出码，
# 那会让整条构建以一个和真实原因无关的报错挂掉。
RUN if id -u ubuntu >/dev/null 2>&1; then userdel -r ubuntu; fi \
    && useradd --system --uid 1000 --shell /bin/bash --home-dir /app --create-home mymedia

WORKDIR /app
COPY --from=build /build/target/mymedia-0.0.1-SNAPSHOT.jar /app/app.jar

# derived / uploads 是应用要写的；/media 是媒体库根的挂载点。
# 三个目录都先建出来并给对属主，免得容器以非 root 启动时创建失败。
RUN mkdir -p /app/data/derived /app/data/uploads /media \
    && chown -R mymedia:mymedia /app /media

USER mymedia
EXPOSE 8080

# /actuator/health 在 SecurityConfig 里是 permitAll（SecurityConfig.java:29），
# 所以健康检查不需要凭证。start-period 给足 60 秒：首次启动要跑 V1–V16 迁移。
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=12 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
