# Build context is backend/ (the parent POM is required): docker build -f discovery-service/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /workspace
COPY pom.xml ./
COPY discovery-service/pom.xml ./discovery-service/
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
RUN useradd --system --uid 1001 spring
COPY --from=extract --chown=spring:spring /app/dependencies/ ./
COPY --from=extract --chown=spring:spring /app/spring-boot-loader/ ./
COPY --from=extract --chown=spring:spring /app/snapshot-dependencies/ ./
COPY --from=extract --chown=spring:spring /app/application/ ./
USER spring
EXPOSE 8761
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
