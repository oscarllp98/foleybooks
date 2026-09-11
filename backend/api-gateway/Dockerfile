# Build context is backend/ (the parent POM is required): docker build -f api-gateway/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /workspace
COPY pom.xml ./
COPY api-gateway/pom.xml ./api-gateway/
RUN mvn -B -pl api-gateway -am dependency:go-offline
COPY api-gateway/src ./api-gateway/src
RUN mvn -B -pl api-gateway -am package -DskipTests \
    && mv api-gateway/target/api-gateway-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre AS extract
WORKDIR /app
COPY --from=builder /workspace/app.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 spring
COPY --from=extract --chown=spring:spring /app/dependencies/ ./
COPY --from=extract --chown=spring:spring /app/spring-boot-loader/ ./
COPY --from=extract --chown=spring:spring /app/snapshot-dependencies/ ./
COPY --from=extract --chown=spring:spring /app/application/ ./
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
