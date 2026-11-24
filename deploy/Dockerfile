# 知微 · 应用镜像
#
# 只做「装 jar 运行」，不在这里编译 —— 编译请在本机执行：
#   mvn -DskipTests package
# 然后：
#   docker compose --profile app up -d --build
#
# 想改成镜像内多阶段构建（不依赖本机 Maven），把下面换成：
#   FROM maven:3.9-eclipse-temurin-21 AS build
#   WORKDIR /src
#   COPY . .
#   RUN mvn -DskipTests package
FROM eclipse-temurin:21-jre

WORKDIR /app
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

COPY target/zw-agent-platform-0.1.0.jar /app/app.jar

# 技能目录（compose 里挂载持久化）
RUN mkdir -p /app/data/skills

EXPOSE 8088

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
