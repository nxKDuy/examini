FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY eureka-server/pom.xml eureka-server/
COPY eureka-server/src eureka-server/src
RUN --mount=type=cache,target=/root/.m2 mvn -f eureka-server/pom.xml package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /app/eureka-server/target/*.jar app.jar
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
