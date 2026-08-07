FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY api-gateway/pom.xml api-gateway/
COPY api-gateway/src api-gateway/src
RUN --mount=type=cache,target=/root/.m2 mvn -f api-gateway/pom.xml package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /app/api-gateway/target/*.jar app.jar
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
