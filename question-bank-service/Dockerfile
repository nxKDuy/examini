FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY question-bank-service/pom.xml question-bank-service/
COPY question-bank-service/src question-bank-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -f question-bank-service/pom.xml package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Local OCR for document import (contracts/question-import.md): Tesseract with Vietnamese + English data.
RUN apk add --no-cache tesseract-ocr tesseract-ocr-data-vie tesseract-ocr-data-eng
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
# Volume mount points must exist and belong to appuser: a new named volume takes this ownership, otherwise it is root-owned and uploads fail.
RUN mkdir -p /data/question-media && chown -R appuser:appgroup /data
COPY --from=builder /app/question-bank-service/target/*.jar app.jar
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
