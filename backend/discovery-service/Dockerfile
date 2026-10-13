# Build context is backend/ (the parent POM is required): docker build -f discovery-service/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /workspace
COPY pom.xml ./
# Sibling POMs: the parent aggregator lists every module, so the reactor needs them
# to resolve -pl discovery-service -am; their sources are not copied or built.
COPY discovery-service/pom.xml ./discovery-service/
COPY api-gateway/pom.xml ./api-gateway/
COPY auth-service/pom.xml ./auth-service/
COPY catalog-service/pom.xml ./catalog-service/
COPY order-service/pom.xml ./order-service/
RUN mvn -B -pl discovery-service -am dependency:go-offline
COPY discovery-service/src ./discovery-service/src
RUN mvn -B -pl discovery-service -am package -DskipTests \
    && mv discovery-service/target/discovery-service-*.jar /workspace/app.jar

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
EXPOSE 8761
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
