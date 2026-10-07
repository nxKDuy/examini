"""Real-browser check of the unsaved-draft guard (data router + useBlocker).
Run against Vite :3107 with VITE_API_BASE_URL=http://127.0.0.1:3110 and tests/fixture-server.mjs (fixture API, not the real backend)."""
import json
import subprocess
import time

PREFIX = ['npx', '--yes', 'agent-browser', '--session', 'examini-guard-smoke']
ORIGIN = 'http://127.0.0.1:3107'


def browser(*args):
    return subprocess.run(PREFIX + list(args), check=True, capture_output=True, text=True, timeout=30).stdout.strip()


def js(expr):
    out = browser('eval', f'(()=>{{ return {expr} }})()')
    return json.loads(out) if out else None


def wait(cond, what, seconds=10):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if cond():
            return
        time.sleep(0.2)
    raise AssertionError(what + ' | url=' + browser('get', 'url'))


def url_is(suffix):
    return browser('get', 'url') == ORIGIN + suffix


def click_text(text, scope='body'):
    r = js(f"(()=>{{const b=[...document.querySelectorAll({json.dumps(scope)} + ' button, ' + {json.dumps(scope)} + ' a')].find(x=>x.textContent.trim()==={json.dumps(text)}&&x.offsetParent!==null); if(!b) return 'missing'; b.click(); return 'ok'}})()")
    assert r == 'ok', f'{text}: {r}'


def dialog_open():
    return js("!!document.querySelector('[role=dialog]')")


def score_box():
    return js("document.querySelector('#item-q1-score')?.value ?? null")


def type_score(value):
    wait(lambda: score_box() is not None, 'workspace loaded')
    browser('fill', '#item-q1-score', value)
    wait(lambda: score_box() == value, 'score typed')


def login():
    browser('open', ORIGIN + '/login')
    browser('fill', '#login-email', 'teacher@example.test')
    browser('fill', '#login-password', 'fixture-password')
    browser('find', 'role', 'button', 'click', '--name', 'Đăng nhập hệ thống')
    wait(lambda: url_is('/dashboard'), 'login')


login()
GRADING = '/grading?examId=e1&r=aaaa1111'

# 1. Sidebar link while dirty: dialog, Stay keeps the URL and the typed score
browser('open', ORIGIN + GRADING); type_score('7')
click_text('Tổng quan', 'aside, nav')
wait(dialog_open, 'dialog after sidebar click')
assert url_is(GRADING) and score_box() == '7'
click_text('Ở lại', '[role=dialog]')
wait(lambda: not dialog_open(), 'dialog closed by Stay')
assert url_is(GRADING) and score_box() == '7', 'Stay must keep draft and place'

# 2. Same link again, confirm: goes to THE destination that was asked for
click_text('Tổng quan', 'aside, nav')
wait(dialog_open, 'dialog again')
click_text('Bỏ thay đổi và tiếp tục', '[role=dialog]')
wait(lambda: url_is('/dashboard'), 'confirm reaches the sidebar destination')

# 3. Browser Back while dirty (in-app history only: a full page load would not be a router navigation)
click_text('Chấm bài', 'aside, nav'); wait(lambda: url_is('/grading'), 'back at grading')
SELECT = "(()=>{const s=document.querySelector('#grading-exam'); const set=Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value').set; set.call(s,'%s'); s.dispatchEvent(new Event('change',{bubbles:true})); return 1})()"
js(SELECT % 'e1'); wait(lambda: url_is('/grading?examId=e1'), 'exam chosen (clean switch is not blocked)')
wait(lambda: js("[...document.querySelectorAll('.grade-queue .list-item')].length") == 2, 'queue loaded')
js("(()=>{[...document.querySelectorAll('.grade-queue .list-item')][0].click(); return 1})()")
wait(lambda: 'r=aaaa1111' in browser('get', 'url'), 'submission opened (clean switch)')
type_score('4')
js("(history.back(), 1)")
wait(dialog_open, 'dialog after browser Back')
assert 'r=aaaa1111' in browser('get', 'url') and score_box() == '4', 'Back is held at the draft'
click_text('Ở lại', '[role=dialog]')
wait(lambda: not dialog_open(), 'Stay after Back')
assert score_box() == '4' and 'r=aaaa1111' in browser('get', 'url'), 'Back + Stay keeps the draft and place'
js("(history.back(), 1)")
wait(dialog_open, 'dialog after Back again')
click_text('Bỏ thay đổi và tiếp tục', '[role=dialog]')
wait(lambda: url_is('/grading?examId=e1'), 'confirm completes the Back to the previous in-app entry')
wait(lambda: score_box() is None, 'draft dropped after confirm')
js("(()=>{[...document.querySelectorAll('.grade-queue .list-item')][0].click(); return 1})()")
wait(lambda: 'r=aaaa1111' in browser('get', 'url'), 'reopened'); type_score('4')

# 4. Switch exam while dirty: confirm executes the whole intent (examId=e2), cancel keeps e1 and the draft
js(SELECT % 'e2')
wait(dialog_open, 'dialog after exam switch')
click_text('Ở lại', '[role=dialog]'); wait(lambda: not dialog_open(), 'closed')
assert 'examId=e1' in browser('get', 'url') and score_box() == '4', 'cancel keeps exam and draft'
js(SELECT % 'e2')
wait(dialog_open, 'dialog again')
click_text('Bỏ thay đổi và tiếp tục', '[role=dialog]')
wait(lambda: 'examId=e2' in browser('get', 'url') and 'r=' not in browser('get', 'url'), 'confirm applies the full exam switch')
print('grading guard smoke: PASS')
