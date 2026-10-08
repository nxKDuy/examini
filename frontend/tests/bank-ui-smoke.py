"""Smoke against fixture-server.mjs + Vite :3107. Requires npx agent-browser; not real E2E."""
import json
import subprocess
import time
from pathlib import Path
from urllib.request import urlopen, Request

checks = []
BASE = 'http://127.0.0.1:3107'

def run(*args):
    if args[0] == 'wait':
        for _ in range(15):
            if args[1] == '--text':
                ready = args[2] in run('get', 'text', 'body')
            elif args[1] == '--url':
                ready = args[2].replace('**', '') in run('get', 'url')
            else:
                ready = 'true' in run('eval', f'Boolean(document.querySelector({json.dumps(args[1])}))')
            if ready:
                return 'ready'
            time.sleep(0.2)
        raise AssertionError(f'Condition not reached: {args}')
    r = subprocess.run(['npx', '--yes', 'agent-browser', '--session', 'examini-bank-fixture', *args], capture_output=True, text=True, timeout=45)
    if r.returncode:
        raise RuntimeError(f'{args[0]} failed: {r.stderr or r.stdout}')
    return r.stdout

def check(name, condition):
    assert condition, name
    checks.append(name)

def click(name):
    run('eval', f'Array.from(document.querySelectorAll("button")).find(b => b.textContent.trim() === {json.dumps(name)})?.scrollIntoView({{block:"center"}})')
    run('find', 'role', 'button', 'click', '--name', name, '--exact')

def text():
    return run('get', 'text', 'body')

def login(actor):
    run('open', f'{BASE}/login')
    run('fill', 'input[type=email]', f'{actor}@example.test')
    run('fill', 'input[type=password]', 'fixture-password')
    run('click', 'button[type=submit]')
    run('wait', 'button[title="Đăng xuất"]')

result = {'outcome': 'INTERRUPTED', 'mode': 'fixture, not real E2E', 'checks': checks}
try:
    urlopen(Request('http://127.0.0.1:3110/__bank-fixture/reset', method='POST')).close()
    run('set', 'viewport', '1440', '1000')
    run('open', f'{BASE}/login'); run('eval', 'localStorage.clear(); location.reload()')
    login('teacher')
    run('open', f'{BASE}/questions'); run('wait', '--text', 'Câu cá nhân fixture')
    run('click', 'section[aria-label="Danh sách câu hỏi"] .list-item'); run('wait', '--text', 'Kích hoạt')
    check('Personal draft offers activation without retired submit-review', 'Gửi duyệt' not in text())
    click('Kích hoạt'); run('click', '[role=dialog] .btn-primary'); run('wait', '--text', 'Đã kích hoạt câu hỏi')
    check('ACTIVE exposes independent Org/Public sharing', 'Gửi vào tổ chức' in text() and 'Xuất bản công khai' in text())
    click('Tạo bộ'); run('wait', '[role="dialog"] input[maxlength="200"]'); run('find', 'label', 'Tên bộ', 'fill', 'Bộ fixture')
    run('check', '.set-questions input[type=checkbox]'); click('Lưu bộ · 1 câu'); run('wait', '--text', 'Bộ fixture')
    click('Xuất bản snapshot'); check('Set publication confirmation pins the snapshot version', 'snapshot v1' in text() and 'không tự cập nhật' in text())
    run('click', '[role=dialog] .btn-primary'); run('wait', '--text', 'Đã xuất bản snapshot phiên bản này')
    click('Gửi vào tổ chức'); run('wait', '#submit-org'); run('select', '#submit-org', 'o1'); run('fill', '#submission-note', 'Ghi chú từ fixture')
    run('click', '[role=dialog] .btn-primary'); run('wait', '--text', 'Đã gửi đóng góp')
    click('Xuất bản công khai')
    check('Publish confirmation binds version 3 and immutable snapshot', 'phiên bản 3' in text() and 'không đổi bản đã xuất bản' in text())
    run('click', '[role=dialog] .btn-primary'); run('wait', '--text', 'Đã xuất bản công khai')
    run('open', f'{BASE}/public-bank'); run('wait', '--text', 'Câu công khai fixture')
    run('fill', '#pb-subject', 'To'); run('wait', '--text', 'Toán'); run('click', 'button[role="option"]')
    run('fill', '#pb-category', 'Đại'); run('wait', '--text', 'Đại số'); run('click', 'button[role="option"]')
    calls = json.load(urlopen('http://127.0.0.1:3110/__bank-fixture/calls'))
    check('Taxonomy suggestions load dynamically and filters send selected server IDs', any(c['path'] == '/public-questions/taxonomy' and c['params'].get('kind') == 'SUBJECT' and c['params'].get('text') == 'To' for c in calls) and any(c['path'] == '/public-questions' and c['params'].get('subjectId') == 's-math' and c['params'].get('categoryId') == 'cat' for c in calls))
    click('Câu công khai fixture')
    run('wait', '--text', 'đáp án và lời giải không được công khai')
    check('Public candidate-safe; Teacher fork and owner unpublish', 'PRIVATE_KEY_FIXTURE' not in text() and 'Đáp án đúng' not in text() and 'Sao chép câu này' in text() and 'Gỡ công khai' in text())
    click('Sao chép câu này'); run('wait', '--text', 'Đã tạo bản sao độc lập')
    run('screenshot', '/tmp/examini-bank-codex-fixture-desktop.png')
    run('open', f'{BASE}/organizations/o1?tab=bank'); run('wait', '--text', 'Câu tổ chức fixture')
    click('Hàng chờ duyệt'); run('wait', '--text', 'Đóng góp teacher'); click('Đóng góp teacher')
    run('wait', '--text', 'Đây là đóng góp của chính bạn')
    check('Self-review blocked with explanation', 'Đây là đóng góp của chính bạn' in text())
    click('Đóng'); click('Đóng góp other'); run('wait', '--text', 'REVIEW_KEY_FIXTURE')
    check('Reviewer explicitly sees key', 'Đáp án đúng' in text())
    click('Duyệt'); run('click', '[role=dialog] .btn-primary'); run('wait', '--text', 'Đóng góp vừa được xử lý')
    check('Review 409 shown', 'Đóng góp vừa được xử lý' in text())
    run('open', f'{BASE}/exams/create'); run('fill', '#exam-title', 'Bank fixture exam')
    click('Tiếp tục'); click('Thêm câu hỏi đủ điều kiện'); run('wait', '#pick-bank'); run('select', '#pick-bank', 'b-public')
    run('wait', '--text', 'Câu công khai fixture')
    check('Exam picker discovers Public resource', 'Câu công khai fixture' in text())
    run('open', f'{BASE}/login'); run('eval', 'localStorage.clear(); location.reload()'); login('student')
    run('open', f'{BASE}/public-bank'); run('wait', '--text', 'Câu công khai fixture'); click('Câu công khai fixture')
    run('wait', '--text', 'Học sinh chỉ xem và có thể đánh giá')
    check('Student read-only with no answer key', 'Sao chép về kho cá nhân' not in text() and 'Gỡ công khai' not in text() and 'PRIVATE_KEY_FIXTURE' not in text() and 'Đáp án đúng' not in text())
    run('fill', 'input[type=number]', '8.75'); click('Gửi đánh giá'); run('wait', '--text', 'Đã lưu đánh giá của bạn')
    check('Student submits fractional rating on the 0–10 scale', '8.75' not in run('get', 'value', 'input[type=number]'))
    calls = json.load(urlopen('http://127.0.0.1:3110/__bank-fixture/calls'))
    check('View event sent only after opening detail and rating remains numeric', any(c['path'] == '/public-questions/pub1/view' and c['method'] == 'POST' for c in calls) and any(c['path'] == '/public-questions/pub1/rating' and c['body'] == {'rating': 8.75} for c in calls))
    run('set', 'viewport', '390', '844'); run('open', f'{BASE}/public-bank'); run('wait', '--text', 'Câu công khai fixture'); click('Câu công khai fixture')
    run('wait', 'aside[role=dialog]')
    check('Mobile bottom sheet', 'đáp án và lời giải không được công khai' in text())
    run('screenshot', '/tmp/examini-bank-codex-fixture-mobile.png')
    calls = json.load(urlopen('http://127.0.0.1:3110/__bank-fixture/calls'))
    check('Picker batch uses pub1 eligible=true without key', any(c['path'] == '/questions/batch' and c['body'] == ['pub1'] and c['params'] == {'eligible': 'true', 'includeAnswers': 'false'} for c in calls))
    check('No retired global review API', not any(c['path'].endswith('/submit-review') or c['path'] == '/questions/q-personal/review' for c in calls))
    check('Submission note uses locked request field', any(c['path'].endswith('/organization-contributions') and c['method'] == 'POST' and c['body'].get('submissionNote') == 'Ghi chú từ fixture' for c in calls))
    check('Review 409 sent once', sum(c['path'] == '/organization-contributions/c1/review' for c in calls) == 1)
    run('open', f'{BASE}/login'); run('eval', 'localStorage.clear(); location.reload()'); login('teacher')
    run('set', 'viewport', '1440', '1000'); run('open', f'{BASE}/public-bank'); run('wait', '--text', 'Câu công khai fixture')
    run('check', 'input[type=checkbox]'); click('Câu công khai fixture'); click('Gỡ công khai')
    run('click', '[role=dialog] .btn-danger'); run('wait', '--text', 'Đã gỡ công khai')
    run('open', f'{BASE}/public-bank'); run('check', 'input[type=checkbox]'); run('wait', '--text', 'Đã gỡ công khai')
    click('Câu công khai fixture')
    check('Owner discovers archived publication after reload with no fork', 'Đã gỡ công khai' in text() and 'Sao chép về kho cá nhân' not in text())
    run('screenshot', '/tmp/examini-bank-codex-fixture-archived.png')
    result = {'outcome': 'PASS', 'mode': 'fixture, not real E2E', 'checks': checks}
except Exception as error:
    result = {'outcome': 'FAIL', 'mode': 'fixture, not real E2E', 'checks': checks, 'error': str(error)}
    raise
finally:
    Path('/tmp/examini-bank-codex-fixture-browser-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))
