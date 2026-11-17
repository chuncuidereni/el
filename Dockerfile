# syntax=docker/dockerfile:1

# 多阶段构建说明：
# 1. Node 阶段只负责编译 Vue 前端，运行镜像不会携带 Node/npm；
# 2. Maven 阶段先单独缓存依赖，再编译 Java，减少重复打包耗时；
# 3. 最终镜像只保留 JRE、后端 JAR、前端 dist 与健康检查所需 curl；
# 4. 本地 application*.yml 永不进入构建上下文，生产配置由 docker/application.yml 与环境变量提供。

# Build the frontend in an isolated Node stage. Only the compiled dist directory
# reaches the runtime image, so Node and npm are not deployed to production.
FROM node:22-bookworm-slim AS frontend
WORKDIR /build/web
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,id=el2ck-npm,target=/root/.npm,sharing=locked npm ci --prefer-offline --no-audit --no-fund
COPY web/ ./
RUN npm run build

# Build the Spring Boot JAR in a separate Maven stage. The Maven repository is a
# cache mount, so repeated deployments do not redownload every dependency.
FROM maven:3.9-eclipse-temurin-8 AS backend
WORKDIR /build

# Resolve dependencies before copying application sources. Editing Java code then
# invalidates only the compile/package layers, while the dependency layer is reused.
COPY pom.xml ./
RUN --mount=type=cache,id=el2ck-maven,target=/root/.m2,sharing=locked \
    mvn -B -ntp -DskipTests dependency:go-offline

# .dockerignore excludes local application*.yml files, including profile files
# that may contain credentials. This container-only configuration is the sole
# application.yml placed in the build and reads secrets from environment variables.
COPY src/ ./src/
COPY docker/application.yml ./src/main/resources/application.yml

# Some source archives have coarse timestamps. Touching resources guarantees that
# Maven copies the container configuration after replacing the local baseline.
# Tests belong in CI; this production image build only compiles and packages code.
RUN --mount=type=cache,id=el2ck-maven,target=/root/.m2,sharing=locked \
    find src/main/resources -type f -exec touch {} + \
    && mvn -B -ntp -Dmaven.test.skip=true clean package

# Runtime contains only the JRE, the packaged JAR, frontend assets and curl.
FROM eclipse-temurin:8-jre-jammy
RUN sed -i 's|http://|https://|g' /etc/apt/sources.list \
    && apt-get -o Acquire::Retries=3 -o Acquire::https::Timeout=30 update \
    && apt-get -o Acquire::Retries=3 -o Acquire::https::Timeout=30 install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 el2ck \
    && useradd --uid 10001 --gid el2ck --no-create-home el2ck
WORKDIR /app
COPY --from=backend /build/target/el-to-ck-server.jar ./app.jar
COPY --from=frontend /build/web/dist/ ./web/dist/

# Run as a non-root user. The named volume must be writable by UID 10001; an old
# root-owned volume therefore needs a one-time ownership repair after upgrades.
RUN mkdir -p /app/data && chown -R el2ck:el2ck /app/data
USER 10001:10001
EXPOSE 17878

# Docker supports one native healthcheck, so mark the container healthy only when
# readiness passes. Liveness is intentionally independent and remains available
# to orchestrators without restarting the process during a dependency outage.
LABEL com.el2ck.health.liveness-path="/api/health/live" \
      com.el2ck.health.readiness-path="/api/health/ready"
HEALTHCHECK --interval=30s --timeout=8s --start-period=120s --retries=3 \
    CMD curl --fail --silent --show-error --max-time 7 'http://127.0.0.1:17878/api/health/ready' || exit 1
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
