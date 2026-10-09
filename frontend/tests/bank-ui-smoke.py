"""Desktop/mobile smoke for the shared in-memory fixture; not real backend E2E."""
import json
import subprocess
import time
from pathlib import Path
from urllib.request import Request, urlopen

BASE = 'http://127.0.0.1:3107'
SESSION = 'examini-ux-stage1'
checks = []

def run(*args):
    if args[0] == 'wait':
        for _ in range(40):
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
    result = subprocess.run(['npx', '--yes', 'agent-browser', '--session', SESSION, *args], capture_output=True, text=True, timeout=45)
    if result.returncode:
        raise RuntimeError(f'{args[0]} failed: {result.stderr or result.stdout}')
    return result.stdout.strip()

def check(name, condition):
    assert condition, name
    checks.append(name)

def click(name):
    run('find', 'role', 'button', 'click', '--name', name, '--exact')

def body():
    return run('get', 'text', 'body')

def calls():
    return json.load(urlopen('http://127.0.0.1:3110/__bank-fixture/calls'))

def login(actor):
    run('open', BASE + '/login')
    run('fill', 'input[type=email]', actor + '@example.test')
    run('fill', 'input[type=password]', 'fixture-password')
    click('Đăng nhập hệ thống')
    run('wait', '--text', 'Đăng xuất')

try:
    urlopen(Request('http://127.0.0.1:3110/__bank-fixture/reset', method='POST')).close()
    run('set', 'viewport', '1440', '1000')
    run('open', BASE + '/login')
    run('eval', 'localStorage.clear(); location.reload()')
    login('teacher')
    run('wait', '.quicklinks')
    check('Topbar uses role navigation quicklinks and removes the fixed sidebar', 'Không gian' in body() and 'sidebar' not in run('eval', 'document.querySelector(".sidebar")?.outerHTML ?? ""') and 'Kho cá nhân' in body())
    run('click', '.space-launcher button')
    run('wait', '#space-launcher-panel a')
    check('Launcher grid opens from the keyboard-accessible topbar control', 'Ngân hàng công khai' in body())
    run('press', 'Escape')
    run('wait', '#space-launcher-panel[hidden]')

    run('open', BASE + '/questions')
    run('wait', '--text', 'Câu cá nhân fixture')
    run('click', 'section[aria-label="Danh sách câu hỏi"] .list-item')
    run('wait', '--text', 'Kích hoạt')
    click('Kích hoạt')
    run('click', '[role=dialog] .dialog-footer button.btn-primary')
    run('wait', '--text', 'Đã kích hoạt câu hỏi')
    check('Personal lifecycle still activates the current question revision', 'Gửi vào tổ chức' in body() and 'Xuất bản công khai' in body())

    click('Tạo bộ')
    run('wait', '--url', '**/questions/sets/new')
    run('fill', 'input[maxlength="200"]', 'Bộ fixture')
    run('check', '.set-questions input[type=checkbox]')
    check('Question Set editor is a routed page with explicit back and ordering controls', 'Quay lại' in body() and 'Thứ tự trong bộ' in body())
    click('Lưu bộ · 1 câu')
    run('wait', '--url', '**/questions')
    run('wait', '--text', 'Bộ fixture')
    check('Personal Set list exposes paging and a draft/active state', 'Phân trang bộ câu hỏi cá nhân' in body() and 'Đang dùng' in body())
    click('Xuất bản snapshot')
    check('Set publication confirmation pins the snapshot version', 'snapshot v1' in body() and 'không tự cập nhật' in body())
    run('click', '[role=dialog] .dialog-footer button.btn-primary')
    run('wait', '--text', 'Đã xuất bản snapshot phiên bản này')
    click('Xuất bản công khai')
    check('Single post confirmation binds exact active version', 'phiên bản 3' in body() and 'không đổi bản đã xuất bản' in body())
    run('click', '[role=dialog] .dialog-footer button.btn-primary')
    run('wait', '--text', 'Đã xuất bản công khai')

    run('open', BASE + '/public-bank')
    run('wait', '--text', 'Câu công khai fixture')
    check('Role quicklinks retain Public Bank entry', 'Ngân hàng công khai' in body())
    before_detail = calls()
    check('List/prefetch does not record a view', not any(c['path'].endswith('/view') for c in before_detail))
    run('fill', '#pb-subject', 'To')
    run('wait', '--text', 'Toán')
    run('click', 'button[role=option]')
    run('fill', '#pb-category', 'Đại')
    run('wait', '--text', 'Đại số')
    run('click', 'button[role=option]')
    run('wait', '--url', '**subjectId=s-math**')
    run('click', '.public-post-title')
    run('wait', '--url', '**/public-bank/pub1**')
    run('wait', '--text', 'đáp án và lời giải không được công khai')
    detail_url = run('get', 'url')
    check('Public detail is a full routed page, candidate-safe, with filters preserved', '/public-bank/pub1?' in detail_url and 'subjectId=s-math' in detail_url and 'PRIVATE_KEY_FIXTURE' not in body() and 'Đáp án đúng' not in body())
    check('Teacher fork and owner unpublish remain available on the detail page', 'Sao chép câu này' in body() and 'Gỡ công khai' in body())
    check('Detail-only open records view through the explicit endpoint', any(c['path'] == '/public-questions/pub1/view' and c['method'] == 'POST' for c in calls()))
    run('screenshot', '/tmp/examini-ux-stage1-desktop.png')
    click('Quay lại')
    run('wait', '--url', '**/public-bank?**')
    check('Back returns to the canonical filtered discovery URL', 'subjectId=s-math' in run('get', 'url') and 'categoryId=cat' in run('get', 'url'))

    # Direct links have a safe fallback to discovery when router state is absent.
    run('open', BASE + detail_url.split('3107', 1)[1])
    run('wait', 'public-detail-page')
    click('Quay lại')
    run('wait', '--url', '**/public-bank?**')

    run('open', BASE + '/organizations/o1?tab=bank')
    run('wait', '--text', 'Câu tổ chức fixture')
    run('click', '.bk-card')
    run('wait', '--url', '**question=orgq1')
    check('Organization approved-question detail opens as a page with a back route', 'Câu hỏi của tổ chức' in body() and 'Quay lại danh sách' in body() and 'Sao chép về kho cá nhân' in body())
    click('Quay lại danh sách')
    run('wait', '--url', '**/organizations/o1?tab=bank')

    run('set', 'viewport', '390', '844')
    run('open', BASE + '/public-bank')
    run('wait', '--text', 'Câu công khai fixture')
    run('click', '.public-post-title')
    run('wait', 'public-detail-page')
    check('Mobile Public detail stays a full page instead of a bottom sheet', 'Quay lại' in body() and 'aside[role=dialog]' not in run('eval', 'document.body.innerHTML'))
    overflow = run('eval', 'document.documentElement.scrollWidth <= window.innerWidth')
    check('Mobile detail has no horizontal page overflow', overflow == 'true')
    run('screenshot', '/tmp/examini-ux-stage1-mobile.png')

    run('open', BASE + '/login')
    run('eval', 'localStorage.clear(); location.reload()')
    login('student')
    run('open', BASE + '/public-bank')
    run('wait', '--text', 'Câu công khai fixture')
    run('click', '.public-post-title')
    run('wait', 'public-detail-page')
    check('Student can view candidate-safe detail but cannot fork or unpublish', 'Sao chép câu này' not in body() and 'Sao chép cả bộ' not in body() and 'Gỡ công khai' not in body() and 'PRIVATE_KEY_FIXTURE' not in body())
    run('fill', 'input[type=number]', '8.75')
    click('Gửi đánh giá')
    run('wait', '--text', 'Đã lưu đánh giá của bạn')
    check('Student numeric fractional rating remains 0–10', any(c['path'] == '/public-questions/pub1/rating' and c['body'] == {'rating': 8.75} for c in calls()))
    result = {'outcome':'PASS','mode':'local in-memory fixture; not real E2E','checks':checks}
except Exception as error:
    result = {'outcome':'FAIL','mode':'local in-memory fixture; not real E2E','checks':checks,'error':str(error)}
    raise
finally:
    Path('/tmp/examini-ux-stage1-browser-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False,indent=2))
