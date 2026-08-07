FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY question-bank-service/pom.xml question-bank-service/
COPY question-bank-service/src question-bank-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -f question-bank-service/pom.xml package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /app/question-bank-service/target/*.jar app.jar
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
