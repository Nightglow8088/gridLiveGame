# --- 第一阶段：构建后端 JAR ---
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
# 复制 pom.xml 和 src
COPY pom.xml .
COPY src ./src
# 打包 (跳过测试以加快速度)
RUN mvn clean package -DskipTests

# --- 第二阶段：运行环境 ---
FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
# 从构建阶段复制 JAR 包
COPY --from=build /app/target/*.jar app.jar
# 暴露端口
EXPOSE 8080
# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]