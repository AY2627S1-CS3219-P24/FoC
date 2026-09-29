# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY user-service ./user-service
COPY supplier-service ./supplier-service
COPY order-service ./order-service
COPY credit-service ./credit-service

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && ./gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app
RUN apk add --no-cache curl \
    && addgroup -S app && adduser -S app -G app
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime AS user-service
COPY --from=build /workspace/user-service/build/libs/*.jar /app/app.jar
EXPOSE 8080

FROM runtime AS supplier-service
COPY --from=build /workspace/supplier-service/build/libs/*.jar /app/app.jar
EXPOSE 8081

FROM runtime AS order-service
COPY --from=build /workspace/order-service/build/libs/*.jar /app/app.jar
EXPOSE 8082

FROM runtime AS credit-service
COPY --from=build /workspace/credit-service/build/libs/*.jar /app/app.jar
EXPOSE 8083
