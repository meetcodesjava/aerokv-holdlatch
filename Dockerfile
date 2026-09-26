# ---- build stage ----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests compile

# ---- runtime stage --------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home aerokv \
    && mkdir /data && chown aerokv /data

WORKDIR /app
COPY --from=build /build/target/classes ./classes
USER aerokv

# The write-ahead log goes on a volume so holds survive a container restart.
ENV AEROKV_PORT=8080 \
    AEROKV_LOG_PATH=/data/aerokv.log \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
VOLUME /data
EXPOSE 8080

# PING is answered before authentication, so this works with or without a password configured.
HEALTHCHECK --interval=10s --timeout=4s --start-period=10s --retries=5 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && echo PING >&3 && read -t 2 -u 3 reply && [ "$reply" = PONG ]'

ENTRYPOINT ["java", "-cp", "/app/classes", "day06.AeroKVServerApp"]
