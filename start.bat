@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ==========================================
echo  Khoi dong DATN (Docker Desktop)
echo ==========================================

rem Khoa bi mat luu ma hoa tren repo (.env.enc): giai ma ra .env neu chua co.
if not exist .env if exist .env.enc call :unlock
if not exist .env (echo Chua co file .env. & pause & exit /b 1)

echo [1/4] Backend (Docker Compose)...
docker compose -p datn up -d --wait --wait-timeout 60 postgres
if errorlevel 1 (echo Loi khi khoi dong Postgres. & pause & exit /b 1)
for /f "delims=" %%u in ('docker compose -p datn exec -T postgres printenv POSTGRES_USER') do set "DATN_DB_USER=%%u"
docker compose -p datn exec -T postgres psql -v ON_ERROR_STOP=1 -U "%DATN_DB_USER%" -d postgres < docker\postgres\ensure-notification-db.sql
if errorlevel 1 (echo Loi khi tao notification_db. & pause & exit /b 1)
docker compose -p datn up -d --build --wait --wait-timeout 240
if errorlevel 1 (echo Loi khi chay docker compose. Docker Desktop da bat chua? & pause & exit /b 1)

echo [2/4] Cho API Gateway san sang...
rem Cho den khi gateway route duoc toi auth-service (503 = chua dang ky Eureka)
:wait
set code=000
for /f %%c in ('curl -s -o nul -w "%%{http_code}" -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d "{}"') do set code=%%c
if not "%code:~0,1%"=="4" (timeout /t 5 /nobreak >nul & goto wait)

echo [3/4] Tao tai khoan test (bo qua neu da co)...
call :reg teacher@examini.local  "Giao vien Test 1" TEACHER
call :reg teacher2@examini.local "Giao vien Test 2" TEACHER
call :reg student@examini.local  "Hoc sinh Test 1"  STUDENT
call :reg student2@examini.local "Hoc sinh Test 2"  STUDENT
call :reg student3@examini.local "Hoc sinh Test 3"  STUDENT

echo [4/4] Frontend...
if not exist frontend\node_modules (pushd frontend & call npm install & popd)
pushd "%~dp0frontend"
start "DATN Frontend" cmd /k "npm run dev -- --host 127.0.0.1 --port 3000 --strictPort"
popd

echo ==========================================
echo  Frontend:    http://localhost:3000
echo  API Gateway: http://localhost:8080
echo  Eureka:      http://localhost:8761
echo  RabbitMQ UI: http://localhost:15673
echo.
echo  Admin:   AUTH_BOOTSTRAP_EMAIL / AUTH_BOOTSTRAP_PASSWORD trong .env
echo  Teacher: teacher@examini.local, teacher2@examini.local  / Test@1234
echo  Student: student@examini.local, student2..3@examini.local / Test@1234
echo ==========================================
pause
exit /b 0

:unlock
set "OSSL=openssl"
where openssl >nul 2>&1 || set "OSSL=%ProgramFiles%\Git\usr\bin\openssl.exe"
if exist .env.key (
  "%OSSL%" enc -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 -a -A -d -in .env.enc -out .env -pass file:.env.key
) else (
  echo Nhap mat khau giai ma khoa EXAMINI:
  "%OSSL%" enc -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 -a -A -d -in .env.enc -out .env
)
if errorlevel 1 (del .env 2>nul & echo Sai mat khau hoac thieu openssl ^(cai Git for Windows^). & pause & exit /b 1)
echo Da giai ma .env
exit /b 0

:reg
for /f %%c in ('curl -s -o nul -w "%%{http_code}" -X POST http://localhost:8080/auth/register -H "Content-Type: application/json" -d "{\"email\":\"%~1\",\"password\":\"Test@1234\",\"fullName\":\"%~2\",\"accountType\":\"PERSONAL\",\"persona\":\"%~3\"}"') do set code=%%c
if "%code%"=="201" (echo   + %~1 [%~3] da tao) else if "%code%"=="409" (echo   = %~1 da ton tai) else (echo   ! %~1 loi HTTP %code%)
exit /b 0
