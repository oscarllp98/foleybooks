# Build context is backend/ (the parent POM is required): docker build -f catalog-service/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /workspace
COPY pom.xml ./
# Sibling POMs: the parent aggregator lists every module, so the reactor needs them
# to resolve -pl catalog-service -am; their sources are not copied or built.
COPY discovery-service/pom.xml ./discovery-service/
COPY api-gateway/pom.xml ./api-gateway/
COPY auth-service/pom.xml ./auth-service/
COPY catalog-service/pom.xml ./catalog-service/
RUN mvn -B -pl catalog-service -am dependency:go-offline
COPY catalog-service/src ./catalog-service/src
RUN mvn -B -pl catalog-service -am package -DskipTests \
    && mv catalog-service/target/catalog-service-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre AS extract
WORKDIR /app
COPY --from=builder /workspace/app.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre
WORKDIR /app
# curl: required by the docker-compose healthcheck against /actuator/health
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 spring
COPY --from=extract --chown=spring:spring /app/dependencies/ ./
COPY --from=extract --chown=spring:spring /app/spring-boot-loader/ ./
COPY --from=extract --chown=spring:spring /app/snapshot-dependencies/ ./
COPY --from=extract --chown=spring:spring /app/application/ ./
USER spring
EXPOSE 8082
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
