"""Run against Vite :3107 with VITE_API_BASE_URL=http://127.0.0.1:3110 and fixture-server.mjs."""
import subprocess
import time

PREFIX = ['npx', '--yes', 'agent-browser', '--session', 'examini-fi-smoke']
ORIGIN = 'http://127.0.0.1:3107'


def browser(*args):
    return subprocess.run(PREFIX + list(args), check=True, capture_output=True, text=True, timeout=20).stdout.strip()


def open_path(path):
    browser('open', ORIGIN + path)


def expect_url(path):
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        actual = browser('get', 'url')
        if actual == ORIGIN + path:
            return
        time.sleep(0.2)
    raise AssertionError(actual)


def expect_text(text):
    actual = browser('get', 'text', 'body')
    assert text in actual, actual[:300]


def login(persona):
    open_path('/login')
    browser('fill', '#login-email', persona + '@example.test')
    browser('fill', '#login-password', 'fixture-password')
    browser('find', 'role', 'button', 'click', '--name', 'Đăng nhập hệ thống')
    expect_url('/dashboard')


def logout():
    browser('find', 'title', 'Đăng xuất', 'click')
    expect_url('/login')


open_path('/login')
browser('eval', "localStorage.removeItem('exam-auth')")
open_path('/questions')
expect_url('/login')
print('PASS anonymous protected direct URL -> login')
# Return-path login, then domain entry navigation and direct refresh.
browser('fill', '#login-email', 'teacher@example.test')
browser('fill', '#login-password', 'fixture-password')
browser('find', 'role', 'button', 'click', '--name', 'Đăng nhập hệ thống')
expect_url('/questions')
print('PASS login restores protected direct URL')
open_path('/dashboard')
expect_text('Không gian giáo viên')
for path in ['/questions', '/exams']:
    open_path(path)
    expect_url(path)
    browser('reload')
    expect_url(path)
print('PASS Teacher Question Bank / Exam entry + refresh (fixture API)')
open_path('/users')
expect_url('/dashboard')
print('PASS Teacher cannot enter Admin area')
logout()
login('student')
expect_text('Không gian học sinh / sinh viên')
for path in ['/questions', '/exams', '/users']:
    open_path(path)
    expect_url('/dashboard')
open_path('/exam/lobby/discover')
expect_url('/my-exams')
expect_text('Tham gia bằng mã')
print('PASS Student entry + Teacher/Admin guards; legacy discover URL -> My exams')
open_path('/dashboard')
logout()
login('admin')
expect_text('Quản trị hệ thống')
expect_text('Duyệt câu hỏi')
open_path('/users')
expect_url('/dashboard')
print('PASS Admin dashboard + review navigation; unsupported user management is not exposed')
logout()
for persona in ['STUDENT', 'TEACHER']:
    open_path('/register')
    options = browser('eval', "JSON.stringify(Array.from(document.querySelectorAll('input[name=persona]'), o => o.value))")
    assert 'ADMIN' not in options and 'BUSINESS' not in options and 'STUDENT' in options and 'TEACHER' in options, options
    browser('click', '#reg-persona-' + persona.lower())
    browser('fill', '#reg-name', persona + ' Fixture')
    browser('fill', '#reg-email', persona.lower() + '@example.test')
    browser('fill', '#reg-pwd', 'fixture-password')
    browser('fill', '#reg-confirm', 'fixture-password')
    browser('find', 'role', 'button', 'click', '--name', 'Tạo tài khoản mới')
    expect_text('Không gian giáo viên' if persona == 'TEACHER' else 'Không gian học sinh / sinh viên')
    logout()
    print('PASS personal ' + persona + ' registration, no Admin/Business option (fixture API)')
print('Browser shell smoke checks passed; real domain/backend E2E remains unverified.')
