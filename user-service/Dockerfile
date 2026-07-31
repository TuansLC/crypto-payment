# syntax=docker/dockerfile:1
# ============================================================
# user-service — Spring Boot 4.1 / Java 21
#
# ⚠️ Build context là REPO ROOT (xem infrastructure/docker-compose.yml),
#    KHÔNG phải user-service/. Lý do: service phụ thuộc 3 shared library
#    com.cryptopayment:common-{core,messaging,security}:0.0.1-SNAPSHOT.
#    Các jar này chỉ tồn tại trong ~/.m2 của máy dev, KHÔNG có trên Maven
#    Central → phải copy source vào image build rồi `mvn install` trước,
#    nếu không bước resolve dependency của user-service sẽ fail.
#
# Build:  docker compose -f infrastructure/docker-compose.yml --profile apps build user-service
# ============================================================

# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy shared lib trước (ít thay đổi hơn service) để tận dụng layer cache.
# Thứ tự install BẮT BUỘC: common-core → common-messaging/common-security,
# vì 2 lib sau khai common-core là dependency.
COPY common-core ./common-core
COPY common-messaging ./common-messaging
COPY common-security ./common-security

# --mount=type=cache giữ lại ~/.m2 giữa các lần build → lần sau không tải lại
# dependency (quan trọng vì spring-kafka-test kéo theo rocksdbjni ~73MB).
# -Dmaven.test.skip=true: bỏ cả compile lẫn run test của lib — image không cần test.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -Dmaven.test.skip=true -f common-core/pom.xml install && \
    mvn -B -Dmaven.test.skip=true -f common-messaging/pom.xml install && \
    mvn -B -Dmaven.test.skip=true -f common-security/pom.xml install

# Copy service sau cùng — sửa code service không làm mất cache của các lib trên.
COPY user-service ./user-service
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -Dmaven.test.skip=true -f user-service/pom.xml clean package && \
    cp user-service/target/*.jar /build/app.jar

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
# Chuẩn hoá timezone UTC — tránh lỗi alias "Asia/Saigon" và đồng nhất mọi môi trường
ENV TZ=UTC
COPY --from=build /build/app.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
