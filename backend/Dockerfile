# syntax=docker/dockerfile:1.7
# 构建阶段生成可执行 Jar；运行镜像不包含 Maven、源码或 Node.js。
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY maven-settings.docker.xml ./maven-settings.xml

COPY src ./src
# BuildKit 缓存 Maven 仓库，后续升级源码不重复下载全部依赖。
ARG MAVEN_MIRROR_URL=https://mirrors.cloud.tencent.com/nexus/repository/maven-public/
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
