# syntax=docker/dockerfile:1.7
# 构建阶段生成可执行 Jar；运行镜像不包含 Maven、源码或 Node.js。
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY maven-settings.docker.xml ./maven-settings.xml

COPY src ./src
# BuildKit 缓存 Maven 仓库，后续升级源码不重复下载全部依赖。
# 默认走 Maven Central：镜像统一在 GitHub runner（境外）构建，且国内公共代理对新版本
# 依赖存在同步延迟（如 spring-boot 4.1.0 缺失会直接构建失败）。国内本地构建经 compose
# 传入 MAVEN_MIRROR_URL 覆盖成腾讯源。
ARG MAVEN_MIRROR_URL=https://repo.maven.apache.org/maven2/
RUN --mount=type=cache,target=/root/.m2 \
    MAVEN_MIRROR_URL="$MAVEN_MIRROR_URL" \
    mvn -s /workspace/maven-settings.xml -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine

# 固定非 root UID，配合生产 Compose 的只读文件系统和 capability 清理。
RUN addgroup -S idolradar && adduser -S -G idolradar -u 10001 idolradar
WORKDIR /app
COPY --from=build --chown=idolradar:idolradar /workspace/target/*.jar /app/app.jar

USER idolradar
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
