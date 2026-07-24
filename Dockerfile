# 构建阶段生成可执行 Jar；运行镜像不包含 Maven、源码或 Node.js。
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /workspace
COPY backend/maven-settings.xml /root/.m2/settings.xml
COPY backend/pom.xml ./pom.xml

COPY backend/src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine

# 固定非 root UID，配合生产 Compose 的只读文件系统和 capability 清理。
RUN addgroup -S idolradar && adduser -S -G idolradar -u 10001 idolradar
WORKDIR /app
COPY --from=build --chown=idolradar:idolradar /workspace/target/*.jar /app/app.jar

USER idolradar
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
