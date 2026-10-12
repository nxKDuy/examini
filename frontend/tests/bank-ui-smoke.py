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
    run('click', 'section[aria-label="Danh sách câu hỏi"] .list-item')
    run('wait', '--text', 'Kích hoạt')
    click('Kích hoạt')
    confirm_dialog()
    run('wait', '--text', 'Đã kích hoạt câu hỏi')
    check('Personal lifecycle still activates the current question revision', 'Gửi vào tổ chức' in body() and 'Xuất bản công khai' in body())

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
    run('wait', '--url', '**/questions')
    run('wait', '--text', 'Bộ fixture')
    run('fill', 'section[aria-label="Bộ câu hỏi cá nhân"] input[type=search]', 'fixture')
    click('Tìm')
    run('wait', '--text', 'Bộ fixture')
    check('Set list search is sent to bank-scoped API with pagination', any(c['path'] == '/question-banks/b-personal/question-sets' and c['params'].get('text') == 'fixture' and c['params'].get('page') == '0' for c in calls()))
    check('Personal Set list exposes the searched set and its ACTIVE state', 'Bộ fixture' in body() and 'Đang dùng' in body() and 'Sửa bộ' in body())
    click('Xuất bản snapshot')
    check('Set publication confirmation pins the snapshot version', 'snapshot v1' in body() and 'không tự cập nhật' in body())
    confirm_dialog()
    run('wait', '--text', 'Đã xuất bản snapshot phiên bản này')
    run('click', 'section[aria-label="Danh sách câu hỏi"] .list-item')
    run('wait', '--text', 'Xuất bản công khai')
    click('Xuất bản công khai')
    check('Single post confirmation binds exact active version', 'phiên bản 3' in body() and 'không đổi bản đã xuất bản' in body())
    confirm_dialog()
    run('wait', '--text', 'Đã xuất bản công khai')

    run('open', BASE + '/public-bank')
    run('wait', '--text', 'Câu công khai fixture')
    check('Role quicklinks retain Public Bank entry', 'Ngân hàng công khai' in body())
    # Card layout: one card per post (a set is ONE card), equal heights per row, aligned footers, lean metadata.
    run('wait', '.public-post')
    run('wait', '--text', 'Hà Nội')
    layout = json.loads(run('eval', '(() => { const r = e => e.getBoundingClientRect(); const cards = Array.from(document.querySelectorAll(".public-post")); const rows = {}; cards.forEach(c => { const k = Math.round(r(c).top); (rows[k] = rows[k] || []).push(c) }); const row = Object.values(rows).sort((a, b) => b.length - a.length)[0]; const bottoms = row.map(c => Math.round(r(c.querySelector(".public-post-actions .btn")).bottom)); const statTops = row.map(c => Math.round(r(c.querySelector(".public-post-stats")).top)); return { cards: cards.length, rowSize: row.length, heights: row.map(c => Math.round(r(c).height)), bottoms, statTops, covers: document.querySelectorAll(".public-post-cover, .public-post-tags").length, itemCards: document.querySelectorAll(".public-post[data-item]").length, setCards: cards.filter(c => /Bộ · 12 câu/.test(c.innerText)).length, overflow: document.documentElement.scrollWidth > innerWidth } })()'))
    check('Each post (including a 12-question set) is exactly one card: 3 posts, 3 cards, the set appears once', layout['cards'] == 3 and layout['setCards'] == 1 and layout['itemCards'] == 0)
    check('Cards in a row share one height and their footer buttons and stat lines line up exactly', layout['rowSize'] >= 2 and len(set(layout['heights'])) == 1 and len(set(layout['bottoms'])) == 1 and len(set(layout['statTops'])) == 1 and not layout['overflow'])
    card_text = run('eval', 'Array.from(document.querySelectorAll(".public-post")).map(c => c.innerText).join("|||")')
    check('Cover blocks and boxed tag chips are gone; question type and difficulty stay out of the card', layout['covers'] == 0 and 'Trắc nghiệm' not in card_text and 'Dễ' not in card_text)
    check('Set card states type + broad subject (+N), update date, partial-copied tag and compact real stats', 'Bộ · 12 câu' in card_text and 'Toán +1' in card_text and 'Cập nhật 05/10/2026' in card_text and 'Đã sao chép một phần' in card_text and 'Thầy Minh' in card_text)
    check('A post without subjects names its top-level category, and a post without ratings says so through its accessible stats', 'Văn học' in card_text and run('eval', 'Array.from(document.querySelectorAll(".public-post-stats [aria-label]")).map(e => e.getAttribute("aria-label")).join("|")').find('Chưa có đánh giá') >= 0)
    # Whole-card click, keyboard and the + entry point
    check('Pointer hits on the summary resolve to the card title link (whole card is clickable)', run('eval', '(() => { const s = document.querySelector(".public-post-snippet"); const r = s.getBoundingClientRect(); const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2); return hit?.classList.contains("public-post-title") })()') == 'true')
    run('eval', 'document.querySelectorAll(".public-post-title")[1].focus()')
    run('press', 'Enter')
    run('wait', '--url', '**/public-bank/pub-set**')
    check('Keyboard Enter on a card title opens that post detail page', '/public-bank/pub-set' in run('get', 'url'))
    run('open', BASE + '/public-bank')
    run('wait', '.public-post')
    tap('.public-post-snippet')
    run('wait', '--url', '**/public-bank/pub1**')
    check('Clicking the card body (not only the title) opens the detail page', '/public-bank/pub1' in run('get', 'url'))
    run('open', BASE + '/public-bank')
    run('wait', '.public-post')
    plus = json.loads(run('eval', '(() => { const b = document.querySelector("button[aria-label=\\"Đăng bài lên Ngân hàng công khai\\"]"); if (!b) return null; const r = b.getBoundingClientRect(); return { w: r.width, h: r.height, title: b.getAttribute("title"), dialog: b.getAttribute("aria-haspopup") } })()'))
    check('Teacher sees a small labelled + button (>= 38px, tooltip, dialog popup) at the top of Public Bank', plus is not None and plus['w'] >= 38 and plus['h'] >= 38 and plus['title'] == 'Đăng bài lên Ngân hàng công khai' and plus['dialog'] == 'dialog')
    click('Đăng bài lên Ngân hàng công khai')
    run('wait', '#publish-search')
    check('Publish dialog lists only ACTIVE personal questions (server filter status=ACTIVE) and offers links to draft/activate flows', 'Câu thứ hai fixture' in body() and 'Soạn hoặc kích hoạt câu hỏi ở Kho cá nhân' in body() and any(c['path'] == '/question-banks/b-personal/questions' and c['params'].get('status') == 'ACTIVE' for c in calls()))
    published_before = [c for c in calls() if c['path'].endswith('/publish-public')]
    click('Đăng câu hỏi: Câu thứ hai fixture', '.dialog')
    run('wait', '--text', 'Xuất bản công khai?')
    check('Publishing an ACTIVE question asks for confirmation pinned to its exact version before any request', 'phiên bản 2' in body() and len([c for c in calls() if c['path'].endswith('/publish-public')]) == len(published_before))
    confirm_dialog()
    run('wait', '--text', 'Đã xuất bản công khai')
    check('Confirming publishes exactly that version through the existing endpoint', any(c['path'] == '/questions/q-second/publish-public' and c['method'] == 'POST' and c['body'] == {'sourceQuestionVersion': 2} for c in calls()))
    # Stacked dialogs (list + confirmation): Escape/Tab act on the top one only and focus returns to the opener (opened from the keyboard, as a real user focuses the button).
    run('eval', 'document.querySelector("button[aria-label=\\"Đăng bài lên Ngân hàng công khai\\"]").focus()')
    run('press', 'Enter')
    run('wait', '#publish-search')
    run('eval', 'document.querySelector(".dialog .publish-row .btn").focus()')
    run('press', 'Enter')
    run('wait', '--text', 'Xuất bản công khai?')
    for _ in range(6):
        run('press', 'Tab')
    check('With the confirmation open, Tab stays inside the TOP dialog only', run('eval', '(() => { const d = Array.from(document.querySelectorAll(".dialog")); return d.length === 2 && d[1].contains(document.activeElement) })()') == 'true')
    run('press', 'Escape')
    time.sleep(0.4)
    check('Escape closes only the confirmation; the list dialog stays open and focus returns to the Đăng button', run('eval', '(() => { const d = document.querySelectorAll(".dialog"); return d.length === 1 && !!document.querySelector("#publish-search") && document.activeElement?.classList.contains("btn-primary") && document.activeElement.closest(".publish-row") !== null })()') == 'true')
    run('press', 'Escape')
    time.sleep(0.4)
    check('A second Escape closes the list dialog and returns focus to the + button', run('eval', 'document.querySelector(".dialog") === null && document.activeElement?.getAttribute("aria-label") === "Đăng bài lên Ngân hàng công khai"') == 'true')
    check('The publish success message is shown on Public Bank after the dialog closes', run('eval', 'document.querySelector("[role=dialog]") === null') == 'true')
    click('Đăng bài lên Ngân hàng công khai')
    run('wait', '#publish-search')
    click('Bộ câu hỏi', '[role=tablist]')
    run('wait', '--text', 'Bộ fixture')
    check('The Set tab lists only ACTIVE sets and publishes the pinned snapshot after confirmation', any(c['path'] == '/question-banks/b-personal/question-sets' and c['params'].get('status') == 'ACTIVE' for c in calls()))
    click('Đăng bộ câu hỏi: Bộ fixture', '.dialog')
    run('wait', '--text', 'Xuất bản bộ câu hỏi?')
    click('Xuất bản bộ', '.dialog-footer')
    run('wait', '--text', 'Đã xuất bản bộ câu hỏi')
    check('Set publish calls the set endpoint with its version', any(c['path'] == '/question-sets/set1/publish-public' and c['method'] == 'POST' and c['body'] == {'version': 1} for c in calls()))
    view_baseline = len([c for c in calls() if c['path'].endswith('/view')])  # earlier checks legitimately opened detail pages
    click('Lưu bài đăng')
    run('wait', '--text', 'Đã lưu')
    wait_call(lambda c: c['path'] == '/public-questions/pub1/bookmark' and c['method'] == 'PUT', 'bookmark PUT')
    check('Saving a post calls bookmark PUT and does not create a view', any(c['path'] == '/public-questions/pub1/bookmark' and c['method'] == 'PUT' for c in calls()) and len([c for c in calls() if c['path'].endswith('/view')]) == view_baseline)
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
    check('List/prefetch does not record a view', len([c for c in before_detail if c['path'].endswith('/view')]) == view_baseline)
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
    mobile = json.loads(run('eval', '(() => { const r = e => e.getBoundingClientRect(); const cards = Array.from(document.querySelectorAll(".public-post")); const plus = document.querySelector("button[aria-label=\\"Đăng bài lên Ngân hàng công khai\\"]"); return { cols: new Set(cards.map(c => Math.round(r(c).left))).size, widthOk: cards.every(c => r(c).right <= innerWidth && r(c).left >= 0), overflow: document.documentElement.scrollWidth > innerWidth, plusVisible: !!plus && r(plus).right <= innerWidth && r(plus).width >= 38 } })()'))
    check('Mobile cards are one column, inside the viewport, with the + button reachable', mobile['cols'] == 1 and mobile['widthOk'] and not mobile['overflow'] and mobile['plusVisible'])
    click('Đăng bài lên Ngân hàng công khai')
    run('wait', '#publish-search')
    sheet = json.loads(run('eval', '(() => { const d = document.querySelector(".dialog").getBoundingClientRect(); return { left: d.left, right: d.right, w: innerWidth, overflow: document.documentElement.scrollWidth > innerWidth } })()'))
    check('The publish dialog fits a 390px phone without horizontal overflow', sheet['left'] >= 0 and sheet['right'] <= sheet['w'] and not sheet['overflow'])
    run('press', 'Escape')
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
