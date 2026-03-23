FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

COPY . .

RUN chmod +x gradlew && ./gradlew :apps:workbench-api:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /workspace/apps/workbench-api/build/libs/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
