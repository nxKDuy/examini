"""Real-browser smoke of the Participants UI on the FIXTURE API (not the real backend).
Run against Vite :3107 with VITE_API_BASE_URL=http://127.0.0.1:3110 and tests/fixture-server.mjs."""
import json
import subprocess
import time

PREFIX = ['npx', '--yes', 'agent-browser', '--session', 'examini-pt-smoke']
ORIGIN = 'http://127.0.0.1:3107'
UID = lambda n: f'{n:08x}-1111-2222-3333-444444444444'


def browser(*args):
    return subprocess.run(PREFIX + list(args), check=True, capture_output=True, text=True, timeout=30).stdout.strip()


def js(expr):
    out = browser('eval', f'(()=>{{ return {expr} }})()')
    return json.loads(out) if out else None


def wait(cond, what, seconds=10):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        try:
            if cond():
                return
        except Exception:
            pass
        time.sleep(0.2)
    raise AssertionError(what + ' | url=' + browser('get', 'url'))


def click(text, scope='body'):
    r = js(f"(()=>{{const b=[...document.querySelectorAll({json.dumps(scope)} + ' button, ' + {json.dumps(scope)} + ' a')].find(x=>x.textContent.trim()==={json.dumps(text)}&&x.offsetParent!==null&&!x.disabled); if(!b) return 'missing'; b.click(); return 'ok'}})()")
    assert r == 'ok', f'{text}: {r}'


def disabled(text, scope):
    return js(f"[...document.querySelectorAll({json.dumps(scope)} + ' button')].find(x=>x.textContent.trim()==={json.dumps(text)})?.disabled")


def text_has(s):
    return js(f"document.body.innerText.includes({json.dumps(s)})")


def login(persona):
    browser('open', ORIGIN + '/login')
    wait(lambda: js("!!document.querySelector('#login-email')"), 'login form')
    browser('fill', '#login-email', persona + '@example.test')
    browser('fill', '#login-password', 'fixture-password')
    browser('find', 'role', 'button', 'click', '--name', 'Đăng nhập hệ thống')
    wait(lambda: browser('get', 'url') == ORIGIN + '/dashboard', 'login ' + persona)


browser('set', 'viewport', '1440', '900')
login('teacher')
browser('open', ORIGIN + '/exams/e1')
wait(lambda: text_has('Kỳ thi thử'), 'exam detail')
click('Tham gia', '[role=tablist]')
wait(lambda: js("document.querySelectorAll('.pt-table tbody tr').length") == 3, 'roster rows')
counts = js("[...document.querySelectorAll('.pt-counts .n')].map(n=>n.textContent)")
assert counts == ['3', '2/30', '1'], counts
assert text_has('Thí sinh bbbb2222') and text_has('(chưa có tên)'), 'fallback name for a missing displayName'

# Add dialog: mixed kinds rejected; three ids -> per-line outcomes, failed lines stay in the box
click('Thêm thí sinh')
wait(lambda: js("!!document.querySelector('#pt-add-text')"), 'add dialog')
browser('fill', '#pt-add-text', UID(1) + '\na@b.co')
click('Thêm thí sinh', '[role=dialog]'); wait(lambda: text_has('Mỗi lần chỉ dùng một loại'), 'mixed kinds are rejected')
browser('fill', '#pt-add-text', '\n'.join(UID(i) for i in (1, 2, 3)))
click('Thêm thí sinh', '[role=dialog]'); wait(lambda: text_has('Không tìm thấy tài khoản') and text_has('Không phải học sinh') and text_has('Đã thêm'), 'six-outcome labels')
assert js("document.querySelector('#pt-add-text').value.split('\\n').length") == 2, 'failed lines are kept to fix and resend'
click('Đóng', '[role=dialog]'); wait(lambda: not js("!!document.querySelector('[role=dialog]')"), 'dialog closed')

# Member panel: attempts with invalidated badge + public reason, grant needs a reason, invalidate needs reason + typed confirmation, 409 keeps the dialog
js("(()=>{[...document.querySelectorAll('.pt-row-btn')].find(b=>b.textContent==='Học sinh A').click(); return 1})()")
wait(lambda: js("document.querySelectorAll('.pt-panel .pt-attempt').length") == 2, 'attempts listed')
assert text_has('Lý do công khai: Sai phòng thi') and js("document.querySelectorAll('.pt-panel .pt-attempt[data-invalid=true]').length") == 1
click('Cấp thêm lượt', '.pt-panel'); wait(lambda: js("!!document.querySelector('#pt-grant-reason')"), 'grant dialog')
assert disabled('Cấp thêm lượt', '[role=dialog]') is True, 'grant disabled without a reason'
browser('fill', '#pt-grant-reason', 'Sự cố mạng'); wait(lambda: disabled('Cấp thêm lượt', '[role=dialog]') is False, 'grant enabled with a reason')
click('Cấp thêm lượt', '[role=dialog]'); wait(lambda: text_has('Đã cấp thêm lượt'), 'grant done')
js("(()=>{[...document.querySelectorAll('.pt-panel .pt-attempt:not([data-invalid=true]) button')].find(b=>b.textContent.includes('Vô hiệu hóa lượt')).click(); return 1})()")
wait(lambda: js("!!document.querySelector('#pt-inv-reason')"), 'invalidate dialog')
assert disabled('Vô hiệu hóa lượt', '[role=dialog]') is True
browser('fill', '#pt-inv-reason', 'conflict'); assert disabled('Vô hiệu hóa lượt', '[role=dialog]') is True, 'reason alone is not enough'
browser('fill', '#pt-inv-typed', '2'); wait(lambda: disabled('Vô hiệu hóa lượt', '[role=dialog]') is False, 'enabled with reason + attempt number')
click('Vô hiệu hóa lượt', '[role=dialog]'); wait(lambda: text_has('Phiên đã thay đổi'), '409 is shown')
assert js("!!document.querySelector('#pt-inv-reason')"), 'dialog stays open after a 409; nothing was re-sent'
click('Hủy', '[role=dialog]')
click('Gỡ khỏi kỳ thi', '.pt-panel'); wait(lambda: text_has('Chặn Join và Start mới'), 'revoke dialog'); click('Hủy', '[role=dialog]')

# Mobile: cards, no horizontal overflow
browser('set', 'viewport', '390', '844'); browser('open', ORIGIN + '/exams/e1')
wait(lambda: text_has('Kỳ thi thử'), 'mobile detail'); click('Tham gia', '[role=tablist]')
wait(lambda: js("document.querySelectorAll('.pt-card').length") == 3, 'mobile cards')
assert js("document.querySelectorAll('.pt-table').length") == 0 and js("document.documentElement.scrollWidth - innerWidth") == 0
browser('set', 'viewport', '1440', '900')

# Student: own attempts on the Lobby (used/remaining, invalidated with public reason, result link)
login('student')
browser('open', ORIGIN + '/exam/lobby/e1')
wait(lambda: text_has('Lượt làm bài của bạn'), 'student attempts block')
assert text_has('Sai phòng thi') and text_has('Đã vô hiệu') and text_has('Sự cố mạng')
assert js("[...document.querySelectorAll('a')].some(a=>a.getAttribute('href')==='/exam/result/s2')"), 'result link for the valid attempt'
print('participants UI smoke: PASS')
