#!/usr/bin/env bash
# Khởi động DATN bằng Docker Desktop (macOS/Linux). Không xoá DB.
set -e
cd "$(dirname "$0")"

# Docker Desktop trên macOS có thể chưa nằm trong PATH của terminal.
if ! command -v docker >/dev/null 2>&1 && [ -x /Applications/Docker.app/Contents/Resources/bin/docker ]; then
  export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
fi

# Khoá bí mật lưu mã hoá trên repo (.env.enc): giải mã ra .env nếu chưa có.
if [ ! -f .env ] && [ -f .env.enc ]; then ./scripts/secrets.sh unlock; fi

echo "[1/4] Backend (Docker Compose)..."
docker compose -p datn up -d --wait --wait-timeout 60 postgres
docker compose -p datn exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres' < docker/postgres/ensure-notification-db.sql
docker compose -p datn up -d --build --wait --wait-timeout 240

echo "[2/4] Chờ API Gateway sẵn sàng..."
# Chờ đến khi gateway route được tới auth-service (503 = chưa đăng ký Eureka)
until [[ $(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d "{}") =~ ^4 ]]; do sleep 5; done

echo "[3/4] Tạo tài khoản test (bỏ qua nếu đã có)..."
reg() {
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/auth/register \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"Test@1234\",\"fullName\":\"$2\",\"accountType\":\"PERSONAL\",\"persona\":\"$3\"}")
  case $code in 201) echo "  + $1 [$3] đã tạo";; 409) echo "  = $1 đã tồn tại";; *) echo "  ! $1 lỗi HTTP $code";; esac
}
reg teacher@examini.local  "Giáo viên Test 1" TEACHER
reg teacher2@examini.local "Giáo viên Test 2" TEACHER
reg student@examini.local  "Học sinh Test 1"  STUDENT
reg student2@examini.local "Học sinh Test 2"  STUDENT
reg student3@examini.local "Học sinh Test 3"  STUDENT

echo "[4/4] Frontend (log: frontend/vite.log)..."
[ -d frontend/node_modules ] || (cd frontend && npm install)
pkill -f "$PWD/frontend/node_modules/.bin/vite" || true
(cd frontend && exec nohup npm run dev -- --host 127.0.0.1 --port 3000 --strictPort > vite.log 2>&1 < /dev/null) &

cat <<INFO
==========================================
 Frontend:    http://localhost:3000
 API Gateway: http://localhost:8080
 Eureka:      http://localhost:8761
 RabbitMQ UI: http://localhost:15673

 Admin:   AUTH_BOOTSTRAP_EMAIL / AUTH_BOOTSTRAP_PASSWORD trong .env
 Teacher: teacher@examini.local, teacher2@examini.local  / Test@1234
 Student: student@examini.local, student2..3@examini.local / Test@1234
==========================================
INFO
