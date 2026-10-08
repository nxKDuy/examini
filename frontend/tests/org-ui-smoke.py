"""Real-browser smoke of the Organization UI on the FIXTURE API (not the real backend).
Run against Vite :3107 with VITE_API_BASE_URL=http://127.0.0.1:3110 and tests/fixture-server.mjs."""
import json
import subprocess
import time

PREFIX = ['npx', '--yes', 'agent-browser', '--session', 'examini-org-smoke']
ORIGIN = 'http://127.0.0.1:3107'


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
    r = js(f"(()=>{{const b=[...document.querySelectorAll({json.dumps(scope)} + ' button, ' + {json.dumps(scope)} + ' a')].find(x=>x.textContent.trim()==={json.dumps(text)}&&x.offsetParent!==null); if(!b) return 'missing'; b.click(); return 'ok'}})()")
    assert r == 'ok', f'{text}: {r}'


def login(persona):
    browser('open', ORIGIN + '/login')
    wait(lambda: js("!!document.querySelector('#login-email')"), 'login form')
    browser('fill', '#login-email', persona + '@example.test')
    browser('fill', '#login-password', 'fixture-password')
    browser('find', 'role', 'button', 'click', '--name', 'Đăng nhập hệ thống')
    wait(lambda: browser('get', 'url') == ORIGIN + '/dashboard', 'login ' + persona)


def tabs():
    return js("[...document.querySelectorAll('[role=tab]')].map(t=>t.textContent.trim())")


browser('set', 'viewport', '1440', '900')
# Teacher (OWNER): list, detail tabs, member panel, stale conflict keeps the unsaved edit
login('teacher')
browser('open', ORIGIN + '/organizations')
wait(lambda: js("document.body.innerText.includes('Khoa CNTT')"), 'org card')
assert js("document.body.innerText.includes('Chủ sở hữu')") and js("document.body.innerText.includes('Tạo tổ chức')")
assert not js("document.body.innerText.includes('thành viên') && !!document.querySelector('.org-card .xsmall')"), 'member count must not be invented when the API sends none'
js("(()=>{document.querySelector('.org-card').click(); return 1})()"); wait(lambda: '/organizations/o1' in browser('get', 'url'), 'detail opened')
wait(lambda: tabs() == ['Thành viên', 'Lời mời', 'Nhật ký', 'Cài đặt'], 'OWNER sees four tabs')
wait(lambda: js("document.querySelectorAll('.org-table tbody tr').length") == 3, 'roster rows')
js("(()=>{[...document.querySelectorAll('.org-row-btn')].find(b=>b.textContent==='Học sinh A').click(); return 1})()"); wait(lambda: js("!!document.querySelector('.org-panel')"), 'panel opens')
assert js("document.querySelector('#member-role')?.value") == 'MEMBER'
js("(()=>{const c=document.querySelector('.org-panel input[type=checkbox]'); c.click(); return 1})()")
click('Lưu thay đổi', '.org-panel'); wait(lambda: js("document.body.innerText.includes('Đã lưu thay đổi')"), 'save ok')
# Member without a display name falls back to a short id; its PATCH answers 409 stale
js("(()=>{[...document.querySelectorAll('.org-table .org-row-btn')].find(b=>b.textContent.startsWith('Thành viên '))?.click(); return 1})()")
wait(lambda: js("document.querySelector('.org-panel h2')?.textContent.startsWith('Thành viên m-stale')"), 'fallback name panel')
js("(()=>{const s=document.querySelector('#member-role'); const set=Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value').set; set.call(s,'ADMIN'); s.dispatchEvent(new Event('change',{bubbles:true})); return 1})()")
click('Lưu thay đổi', '.org-panel')
wait(lambda: js("document.body.innerText.includes('vừa được cập nhật ở nơi khác')"), 'stale warning')
assert js("document.querySelector('#member-role').value") == 'ADMIN', 'unsaved edit is preserved on 409'
assert js("[...document.querySelectorAll('.org-panel button')].find(b=>b.textContent.trim()==='Lưu thay đổi').disabled") is True
click('Cài đặt', '[role=tablist]'); wait(lambda: js("document.body.innerText.includes('Chuyển quyền sở hữu') && document.body.innerText.includes('Lưu trữ tổ chức')"), 'settings')
click('Lưu trữ tổ chức', 'section'); wait(lambda: js("!!document.querySelector('[role=dialog] #typed-confirm')"), 'typed confirm')
assert js("[...document.querySelectorAll('[role=dialog] button')].find(b=>b.textContent.includes('Lưu trữ tổ chức')).disabled") is True, 'archive disabled until the name is typed'
click('Hủy', '[role=dialog]')

# Mobile: roster becomes cards, no horizontal overflow
browser('set', 'viewport', '390', '844'); browser('open', ORIGIN + '/organizations/o1')
wait(lambda: js("document.querySelectorAll('.org-member-card').length") == 3, 'mobile cards')
assert js("document.querySelectorAll('.org-table').length") == 0
assert js("document.documentElement.scrollWidth - innerWidth") == 0, 'no horizontal overflow'
browser('set', 'viewport', '1440', '900')

# Student (MEMBER): dashboard notice, invitation block, accept -> detail with the single Thành viên tab
login('student')
wait(lambda: js("document.body.innerText.includes('lời mời tham gia tổ chức')"), 'dashboard invitation notice')
browser('open', ORIGIN + '/organizations?tab=invitations')
wait(lambda: js("!!document.querySelector('#org-invitations')"), 'invitation block')
click('Chấp nhận', '#org-invitations'); wait(lambda: '/organizations/o1' in browser('get', 'url'), 'accept leads to the organization')
wait(lambda: tabs() == ['Thành viên'], 'MEMBER sees only Thành viên')
assert not js("document.body.innerText.includes('Mời thành viên')"), 'MEMBER cannot invite'
assert js("document.body.innerText.includes('Rời tổ chức')")
print('organization UI smoke: PASS')
