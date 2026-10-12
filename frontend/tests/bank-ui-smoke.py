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

def clickable(name, scope=None):
    """Click the visible control whose accessible name (aria-label, else text) is exactly `name`; fail loudly when there is none."""
    root = f'document.querySelector({json.dumps(scope)})' if scope else 'document'
    found = run('eval', f"""(() => {{
      const root = {root}; if (!root) return 'no-scope';
      const visible = el => el.offsetParent !== null || el.getClientRects().length > 0;
      const label = el => (el.getAttribute('aria-label') || el.textContent || '').replace(/\\s+/g, ' ').trim();
      const hit = Array.from(root.querySelectorAll('button,a,[role=button],[role=tab],[role=option]')).find(el => visible(el) && !el.disabled && label(el) === {json.dumps(name)});
      if (!hit) return 'missing'; hit.click(); return 'clicked';
    }})()""").strip('"')
    if found != 'clicked':
        raise AssertionError(f'No visible enabled control named {name!r}' + (f' in {scope}' if scope else '') + f': {found}')

def click(name, scope=None):
    clickable(name, scope)

def confirm_dialog():
    run('wait', '.dialog-footer .btn-primary')
    run('eval', 'document.querySelector(".dialog-footer .btn-primary").click()')

def click_selector(selector):
    found = run('eval', f'(() => {{ const el = document.querySelector({json.dumps(selector)}); if (!el) return "missing"; el.click(); return "clicked" }})()').strip('"')
    if found != 'clicked':
        raise AssertionError(f'No element matches {selector!r}')

def tap(selector):
    """Real pointer click after scrolling the target into view (cards can sit below the fold on a phone)."""
    found = run('eval', f'(() => {{ const el = document.querySelector({json.dumps(selector)}); if (!el) return "missing"; el.scrollIntoView({{block: "center"}}); return "ok" }})()').strip('"')
    if found != 'ok':
        raise AssertionError(f'No element matches {selector!r}')
    run('click', selector)

def body():
    return run('get', 'text', 'body')

def calls():
    return json.load(urlopen('http://127.0.0.1:3110/__bank-fixture/calls'))

def wait_call(predicate, label):
    """The UI updates optimistically, so wait for the request to reach the fixture instead of racing it."""
    for _ in range(40):
        if any(predicate(c) for c in calls()):
            return
        time.sleep(0.2)
    raise AssertionError(f'Request not observed: {label}')

def login(actor):
    run('open', BASE + '/login')
    run('fill', 'input[type=email]', actor + '@example.test')
    run('fill', 'input[type=password]', 'fixture-password')
    click('Đăng nhập hệ thống')
    run('wait', '--url', '**/dashboard')  # the logout label is icon-only on phones, so wait on the route, not its text
    run('wait', '.topbar')

try:
    urlopen(Request('http://127.0.0.1:3110/__bank-fixture/reset', method='POST')).close()
    run('set', 'viewport', '1440', '1000')
    run('open', BASE + '/login')
    run('eval', 'localStorage.clear(); location.reload()')
    run('wait', 'input[type=email]')  # the previous run's page is gone: late requests from it must not pollute the call log
    urlopen(Request('http://127.0.0.1:3110/__bank-fixture/reset', method='POST')).close()
    login('teacher')
    run('wait', '.quicklinks')
    check('Launcher is gone: no button or panel, no fixed sidebar', run('eval', 'document.querySelector(".space-launcher, #space-launcher-panel, .sidebar") === null') == 'true')
    desktop = run('eval', '(() => { const links = Array.from(document.querySelectorAll(".quicklinks a")); const tops = new Set(links.map(a => Math.round(a.getBoundingClientRect().top))); const dock = document.querySelector(".dock"); return { names: links.map(a => a.textContent.trim()), oneRow: tops.size === 1, current: document.querySelectorAll(".quicklinks a[aria-current=page]").length, dockVisible: !!dock && getComputedStyle(dock).display !== "none", overflow: document.documentElement.scrollWidth > innerWidth } })()')
    desktop = json.loads(desktop)
    expected_teacher = ['Tổng quan', 'Lịch thi', 'Kho cá nhân', 'Ngân hàng công khai', 'Đề thi', 'Chấm bài', 'Tổ chức']
    check('Desktop topbar shows every Teacher destination directly, on one row, without opening anything', desktop['names'] == expected_teacher and desktop['oneRow'] and not desktop['overflow'] and not desktop['dockVisible'])
    check('The active topbar link is exposed with aria-current (not colour alone)', desktop['current'] == 1)

    run('open', BASE + '/questions')
    run('wait', '--text', 'Câu cá nhân fixture')
    check('Kho cá nhân opens on single questions; Question Sets wait behind their own tab', run('eval', 'document.querySelector("section[aria-label=\\"Bộ câu hỏi cá nhân\\"]") === null && document.querySelector("[role=tab][aria-selected=true]").textContent.trim() === "Câu hỏi lẻ"') == 'true')
    run('click', 'section[aria-label="Danh sách câu hỏi"] .list-item')
    run('wait', '--text', 'Kích hoạt')
    click('Kích hoạt')
    confirm_dialog()
    run('wait', '--text', 'Đã kích hoạt câu hỏi')
    check('Personal lifecycle activates and can submit to an org, but publishing moved to Public Bank', 'Gửi vào tổ chức' in body() and 'Xuất bản công khai' not in body() and 'nút + ở Ngân hàng công khai' in body())

    click('Bộ câu hỏi')
    run('wait', 'section[aria-label="Bộ câu hỏi cá nhân"]')
    check('Sets tab is linkable through the URL', 'view=sets' in run('get', 'url'))
    click('Tạo bộ')
    run('wait', '--url', '**/questions/sets/new')
    run('fill', 'input[maxlength="200"]', 'Bộ fixture')
    run('fill', 'input[type=search]', 'fixture')
    click('Tìm câu')
    run('wait', '--text', 'Câu thứ hai fixture')
    run('check', '.set-questions input[type=checkbox]')
    run('eval', 'document.querySelectorAll(".set-questions input[type=checkbox]")[1].click()')
    check('Question Set editor is a routed page with explicit back and ordering controls', 'Quay lại' in body() and 'Thứ tự trong bộ' in body())
    click('Đưa câu 2 lên')
    ordered = json.loads(run('eval', 'Array.from(document.querySelectorAll(".set-order-row .small")).map(x=>x.innerText.trim())'))
    check('Accessible reorder moves the selected question without autosaving', ordered[0].startswith('Câu thứ hai fixture'))
    click('Lưu bộ · 2 câu')
    run('wait', '--url', '**/questions?view=sets')
    run('wait', '--text', 'Bộ fixture')
    run('fill', 'section[aria-label="Bộ câu hỏi cá nhân"] input[type=search]', 'fixture')
    click('Tìm')
    run('wait', '--text', 'Bộ fixture')
    check('Set list search is sent to bank-scoped API with a short server page', any(c['path'] == '/question-banks/b-personal/question-sets' and c['params'].get('text') == 'fixture' and c['params'].get('page') == '0' and c['params'].get('size') == '6' for c in calls()))
    check('Personal Set list exposes the searched set, its ACTIVE state and no publish action', 'Bộ fixture' in body() and 'Đang dùng' in body() and 'Sửa bộ' in body() and 'Xuất bản snapshot' not in body())
    click('Câu hỏi lẻ')
    run('wait', 'section[aria-label="Danh sách câu hỏi"] .list-item')
    check('Switching back to single questions clears the view param', 'view=sets' not in run('get', 'url'))

    run('open', BASE + '/public-bank')
    run('wait', '--text', 'Câu công khai fixture')
    check('Role quicklinks retain Public Bank entry', 'Ngân hàng công khai' in body())
    click('Lưu bài đăng')
    run('wait', '--text', 'Đã lưu')
    wait_call(lambda c: c['path'] == '/public-questions/pub1/bookmark' and c['method'] == 'PUT', 'bookmark PUT')
    check('Saving a post calls bookmark PUT and does not create a view', any(c['path'] == '/public-questions/pub1/bookmark' and c['method'] == 'PUT' for c in calls()) and not any(c['path'].endswith('/view') for c in calls()))
    run('click', 'button.public-advanced-toggle')
    run('eval', 'document.querySelector("input[type=checkbox]").click()')
    run('wait', '--url', '**saved=1**')
    run('wait', '--text', 'Bài đã lưu')
    check('Saved filter is in URL and server query uses saved=true', any(c['path'] == '/public-questions' and c['params'].get('saved') == 'true' for c in calls()))
    saved_list_url = run('get', 'url')
    run('open', saved_list_url)
    run('wait', '--text', 'Câu công khai fixture')
    check('Saved state survives route reload from the actor API response', 'Đã lưu' in body())
    before_detail = calls()
    check('List/prefetch does not record a view', not any(c['path'].endswith('/view') for c in before_detail))
    run('fill', '#pb-subject', 'To')
    run('wait', '#pb-subject-options button[role=option]')
    click_selector('#pb-subject-options button[role=option]')
    run('fill', '#pb-category', 'Đại')
    run('wait', '#pb-category-options button[role=option]')
    click_selector('#pb-category-options button[role=option]')
    run('wait', '--url', '**subjectId=s-math**')
    tap('.public-post-title')
    run('wait', '--url', '**/public-bank/pub1**')
    run('wait', '--text', 'đáp án và lời giải không được công khai')
    detail_url = run('get', 'url')
    check('Public detail is a full routed page, candidate-safe, with filters preserved', '/public-bank/pub1?' in detail_url and 'subjectId=s-math' in detail_url and 'PRIVATE_KEY_FIXTURE' not in body() and 'Đáp án đúng' not in body())
    check('Teacher fork and owner unpublish remain available on the detail page', 'Sao chép câu này' in body() and 'Gỡ công khai' in body())
    check('Detail-only open records view through the explicit endpoint', any(c['path'] == '/public-questions/pub1/view' and c['method'] == 'POST' for c in calls()))
    run('screenshot', '/tmp/examini-ux-stage2-desktop.png')
    click('Quay lại')
    run('wait', '--url', '**/public-bank?**')
    check('Back returns to the canonical filtered discovery URL', 'subjectId=s-math' in run('get', 'url') and 'categoryId=cat' in run('get', 'url'))

    # Direct links have a safe fallback to discovery when router state is absent.
    run('open', BASE + detail_url.split('3107', 1)[1])
    run('wait', '.public-detail-page')
    click('Quay lại')
    run('wait', '--url', '**/public-bank?**')

    run('open', BASE + '/organizations/o1?tab=bank')
    run('wait', '--text', 'Câu tổ chức fixture')
    run('click', '.bk-row-btn')
    run('wait', '--url', '**question=orgq1')
    check('Organization approved-question detail opens as a page with a back route', 'Câu hỏi của tổ chức' in body() and 'Quay lại danh sách' in body() and 'Sao chép về kho cá nhân' in body())
    click('Quay lại danh sách')
    run('wait', '--url', '**/organizations/o1?tab=bank')

    run('set', 'viewport', '390', '844')
    run('open', BASE + '/public-bank')
    run('wait', '--text', 'Câu công khai fixture')
    tap('.public-post-title')
    run('wait', '.public-detail-page')
    check('Mobile Public detail stays a full page instead of a bottom sheet', 'Quay lại' in body() and run('eval', 'document.querySelector("aside[role=dialog]") === null') == 'true')
    overflow = run('eval', 'document.documentElement.scrollWidth <= window.innerWidth')
    check('Mobile detail has no horizontal page overflow', overflow == 'true')
    run('screenshot', '/tmp/examini-ux-stage2-mobile.png')

    run('open', BASE + '/login')
    run('eval', 'localStorage.clear(); location.reload()')
    login('student')
    run('open', BASE + '/public-bank')
    run('wait', '--text', 'Câu công khai fixture')
    tap('.public-post-title')
    run('wait', '.public-detail-page')
    check('Student can view candidate-safe detail but cannot fork or unpublish', 'Sao chép câu này' not in body() and 'Sao chép cả bộ' not in body() and 'Gỡ công khai' not in body() and 'PRIVATE_KEY_FIXTURE' not in body())
    run('fill', 'input[type=number]', '8.75')
    click('Gửi đánh giá')
    run('wait', '--text', 'Đã lưu đánh giá của bạn')
    check('Student numeric fractional rating remains 0–10', any(c['path'] == '/public-questions/pub1/rating' and c['body'] == {'rating': 8.75} for c in calls()))
    run('open', BASE + '/exam/result/s-result')
    run('wait', '--text', 'Phương án B đã chọn')
    check('Published result renders frozen answerDisplay and backend-returned explanation', 'Giải thích trong snapshot đã công bố.' in body() and 'Đáp án đúng' not in body())
    run('open', BASE + '/exam/result/s-legacy')
    run('wait', '--text', 'Đã chọn một phương án')
    check('Legacy UUID answer keeps safe fallback and missing explanation stays absent', 'Giải thích trong snapshot đã công bố.' not in body() and 'answerDisplay' not in body())
    run('open', BASE + '/login')
    run('eval', 'localStorage.clear(); location.reload()')
    login('teacher')
    run('open', BASE + '/grading?examId=e1')
    run('wait', '--text', 'Học sinh Fixture')
    check('Grading queue shows trusted display name and legacy ID fallback', 'Thí sinh bbbb2222' in body())
    tap('button.list-item')
    run('wait', '--text', 'Học sinh Fixture')
    check('Grading detail preserves full ID copy action beside trusted display name', run('eval', 'Boolean(document.querySelector("button[aria-label=\\"Sao chép mã thí sinh\\"]"))') == 'true' and 'aaaa1111-0000-aaaa' in body())

    # ADMIN is a global-admin audience, not a Teacher/Student: it may read Public posts but has no Save/Saved (the API answers 403 for it).
    run('set', 'viewport', '1440', '1000')
    run('open', BASE + '/login')
    run('eval', 'localStorage.clear(); location.reload()')
    login('admin')
    urlopen(Request('http://127.0.0.1:3110/__bank-fixture/reset', method='POST')).close()
    run('open', BASE + '/public-bank?saved=1')
    run('wait', '--text', 'Câu công khai fixture')
    run('click', 'button.public-advanced-toggle')
    admin_controls = json.loads(run('eval', '({ saveButtons: Array.from(document.querySelectorAll("button")).filter(b => /Lưu bài đăng|Bỏ lưu bài đăng/.test(b.getAttribute("aria-label") || b.textContent)).length, savedFilter: /Bài đã lưu/.test(document.body.innerText), posts: document.querySelectorAll(".public-post").length })'))
    check('ADMIN sees posts but no Save button and no Saved filter, even with saved=1 in the URL', admin_controls['posts'] >= 1 and admin_controls['saveButtons'] == 0 and not admin_controls['savedFilter'])
    check('ADMIN discovery never sends saved=true to the API', not any(c['path'] == '/public-questions' and c['params'].get('saved') == 'true' for c in calls()))
    tap('.public-post-title')
    run('wait', '.public-detail-page')
    check('ADMIN can open Public detail but it has no Save control', run('eval', 'Array.from(document.querySelectorAll("button")).filter(b => /Lưu bài đăng|Bỏ lưu bài đăng/.test(b.getAttribute("aria-label") || b.textContent)).length') == '0' and 'Câu công khai fixture' in body())

    run('open', BASE + '/login')
    run('eval', 'localStorage.clear(); location.reload()')
    run('wait', 'input[type=email]')
    login('teacher')
    # Phone dock: fixed to the bottom inside the viewport, never covers the end of the page, "Khác" works from the keyboard.
    def dock_metrics():
        return json.loads(run('eval', '(() => { const d = document.querySelector(".dock"); if (!d) return null; const r = d.getBoundingClientRect(); const tiles = Array.from(d.querySelectorAll(".dock-tile")); return { pos: getComputedStyle(d).position, left: r.left, right: r.right, bottom: r.bottom, top: r.top, w: innerWidth, h: innerHeight, tiles: tiles.length, names: tiles.map(t => t.getAttribute("aria-label")), current: d.querySelectorAll("[aria-current=page]").length, overflow: document.documentElement.scrollWidth > innerWidth, quickVisible: getComputedStyle(document.querySelector(".quicklinks")).display !== "none" } })()'))
    for width, height in ((375, 667), (320, 640), (767, 900)):
        run('set', 'viewport', str(width), str(height))
        run('open', BASE + '/public-bank')
        run('wait', '.dock')
        m = dock_metrics()
        check(f'Dock at {width}px is fixed, inside the viewport, hides the topbar links and has at most 5 tiles', m['pos'] == 'fixed' and m['left'] >= 0 and m['right'] <= m['w'] and m['bottom'] <= m['h'] + 1 and m['tiles'] <= 5 and not m['quickVisible'] and not m['overflow'])
        check(f'Teacher dock at {width}px = 4 priority tiles plus Khác', m['names'] == ['Tổng quan', 'Lịch thi', 'Đề thi', 'Chấm bài', 'Khác'])
        run('eval', 'window.scrollTo(0, document.documentElement.scrollHeight)')
        time.sleep(0.3)
        clear = run('eval', '(() => { const d = document.querySelector(".dock").getBoundingClientRect(); const last = document.querySelector("#main-content").lastElementChild.getBoundingClientRect(); return last.bottom <= d.top + 1 })()')
        check(f'The last content of the page stays above the dock at {width}px', clear == 'true')
        run('eval', 'document.querySelector(".dock button[aria-label=Khác]").focus()')
        run('press', 'Enter')
        run('wait', '.dock-sheet')
        check(f'Khác opens a modal bottom sheet from the keyboard at {width}px', run('eval', 'document.querySelector(".dock-sheet").getAttribute("aria-modal") === "true" && document.querySelector(".dock button[aria-label=Khác]").getAttribute("aria-expanded") === "true"') == 'true')
        sheet_links = json.loads(run('eval', 'Array.from(document.querySelectorAll(".dock-sheet-link")).map(a => a.textContent.trim())'))
        check(f'The sheet holds exactly the destinations that are not in the dock at {width}px', sheet_links == ['Kho cá nhân', 'Ngân hàng công khai', 'Tổ chức'])
        for _ in range(8):
            run('press', 'Tab')
        check(f'Focus stays trapped inside the sheet at {width}px', run('eval', 'document.querySelector(".dock-sheet").contains(document.activeElement)') == 'true')
        run('press', 'Escape')
        time.sleep(0.4)
        check(f'Escape closes the sheet and returns focus to Khác at {width}px', run('eval', 'document.querySelector(".dock-sheet") === null && document.activeElement?.getAttribute("aria-label") === "Khác"') == 'true')
    run('set', 'viewport', '768', '900')
    run('open', BASE + '/public-bank')
    run('wait', '.quicklinks')
    check('At 768px the dock is gone and the topbar links are back', run('eval', 'getComputedStyle(document.querySelector(".dock")).display === "none" && getComputedStyle(document.querySelector(".quicklinks")).display !== "none" && document.documentElement.scrollWidth <= innerWidth') == 'true')
    run('set', 'viewport', '375', '667')
    run('open', BASE + '/public-bank')
    run('wait', '.dock')
    run('eval', 'document.querySelector(".dock button[aria-label=Khác]").click()')
    run('wait', '.dock-sheet')
    click('Tổ chức', '.dock-sheet')
    run('wait', '--url', '**/organizations')
    check('Choosing a destination in the sheet navigates, closes the sheet and marks Khác as the active tile', run('eval', 'document.querySelector(".dock-sheet") === null && document.querySelector(".dock button[aria-label=Khác]").getAttribute("data-active") === "true"') == 'true')
    run('open', BASE + '/login')
    run('eval', 'localStorage.clear(); location.reload()')
    run('wait', 'input[type=email]')
    login('student')
    run('open', BASE + '/public-bank')
    run('wait', '.dock')
    m = dock_metrics()
    check('Student dock fits exactly five destination tiles and no Khác', m['names'] == ['Tổng quan', 'Ngân hàng công khai', 'Lịch của tôi', 'Kỳ thi của tôi', 'Tổ chức'] and m['tiles'] == 5 and m['current'] == 1)
    run('open', BASE + '/exam/take/s-live')
    run('wait', '.take-bar')
    check('The exam-taking page never shows the dock', run('eval', 'document.querySelector(".dock") === null') == 'true')

    result = {'outcome':'PASS','mode':'local in-memory fixture; not real E2E','checks':checks}
except Exception as error:
    result = {'outcome':'FAIL','mode':'local in-memory fixture; not real E2E','checks':checks,'error':str(error)}
    raise
finally:
    Path('/tmp/examini-ux-stage2-browser-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False,indent=2))
