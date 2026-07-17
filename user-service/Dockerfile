# syntax=docker/dockerfile:1
# ============================================================
# user-service — Spring Boot 4.1 / Java 21
# ============================================================

# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Copy pom trước để cache layer dependencies (chỉ tải lại khi pom đổi)
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
# Chuẩn hoá timezone UTC — tránh lỗi alias "Asia/Saigon" và đồng nhất mọi môi trường
ENV TZ=UTC
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
