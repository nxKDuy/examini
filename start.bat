@echo off
echo ==========================================
echo Khởi động ExamAI Platform
echo ==========================================

echo [1/2] Đang khởi động Backend (Docker Compose)...
cd /d "%~dp0"
docker-compose up -d --build

echo.
echo [2/2] Đang khởi động Frontend (React)...
cd frontend
start cmd /k "npm run dev"

echo.
echo ==========================================
echo Hoàn tất!
echo - Frontend: http://localhost:3000
echo - API Gateway: http://localhost:8080
echo - Eureka Dashboard: http://localhost:8761
echo - Backend chạy ngầm trong Docker (Postgres, Redis, RabbitMQ, Microservices)
echo ==========================================
pause
