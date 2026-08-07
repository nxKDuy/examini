FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY exam-session-service/pom.xml exam-session-service/
COPY exam-session-service/src exam-session-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -f exam-session-service/pom.xml package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /app/exam-session-service/target/*.jar app.jar
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
