FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY core-model ./core-model
COPY core-crypto ./core-crypto
COPY server ./server

RUN chmod +x gradlew && ./gradlew :server:installDist --no-daemon

# Runtime Stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# Run as non-privileged user for security
RUN useradd -m -u 1000 mythuser

COPY --from=builder /app/server/build/install/server /app

USER mythuser

EXPOSE 8080

ENV HOST=0.0.0.0
ENV PORT=8080

ENTRYPOINT ["/app/bin/server"]
