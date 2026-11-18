# ==============================================================================
# Stage 1: Build da aplicação com Maven e JDK 21 (sem embutir segredos ou .env)
# ==============================================================================
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ==============================================================================
# Stage 2: Runtime enxuto com JRE 21, usuário não-root e HEALTHCHECK nativo
# ==============================================================================
FROM eclipse-temurin:21-jre
WORKDIR /app

# Cria usuário e grupo sem privilégios de root e diretório de dados para volume SQLite
RUN groupadd --system --gid 10001 appgroup \
    && useradd --system --uid 10001 --gid appgroup --home-dir /app appuser \
    && mkdir -p /app/data \
    && chown -R appuser:appgroup /app

COPY --from=build --chown=appuser:appgroup /workspace/target/linkedinagent-0.1.0-SNAPSHOT.jar /app/app.jar

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
