FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY grading-service/pom.xml grading-service/
COPY grading-service/src grading-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f grading-service/pom.xml package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /app/grading-service/target/*.jar app.jar
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
