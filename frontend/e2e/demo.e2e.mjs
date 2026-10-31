/**
 * End-to-end suite against a running EXAMINI (docker compose) seeded with scripts/seed_demo.py.
 *
 *   docker compose up -d && python3 scripts/seed_demo.py
 *   cd frontend && npx playwright install chromium   # first time only
 *   npm run e2e                                      # E2E_BASE_URL=http://localhost:8088 if WEB_PORT is not 80
 *
 * Everything goes through nginx like a real browser: the SPA at /, the API at /api. Tests only add data (a new
 * attempt, a graded essay, a moved schedule), so the suite can run again on the same database; numbers are checked
 * as "before vs after", never as fixed counts that a second run would break.
 */
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const BASE = (process.env.E2E_BASE_URL || 'http://localhost').replace(/\/+$/, '')
const API = BASE + '/api'
const PASSWORD = process.env.E2E_PASSWORD || 'Examini@2026'
const HEADLESS = process.env.E2E_HEADED ? false : true
const mail = local => `${local}@examini.demo`

const tokens = {}
async function call(path, who, { method = 'GET', body, ok = [200, 201, 202, 204] } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (who) headers.Authorization = 'Bearer ' + (await token(who))
  const res = await fetch(API + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await res.text()
  if (ok && !ok.includes(res.status)) throw new Error(`${method} ${path} → ${res.status}: ${text.slice(0, 300)}`)
  return { status: res.status, body: text ? JSON.parse(text) : null }
}
async function token(who) {
  if (!tokens[who]) {
    const r = await call('/auth/login', null, { method: 'POST', body: { email: mail(who), password: PASSWORD } })
    tokens[who] = r.body.accessToken
  }
  return tokens[who]
}
const get = async (path, who) => (await call(path, who)).body
const byTitle = (list, start) => list.find(e => e.title.startsWith(start))
/** ISO time with the +07:00 offset the schedule API requires for Asia/Ho_Chi_Minh. */
const vnIso = ms => new Date(ms + 7 * 3600_000).toISOString().replace(/\.\d{3}Z$/, '+07:00')

let browser
before(async () => {
  const health = await fetch(API + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: mail('gv.toan'), password: PASSWORD }) })
    .catch(e => { throw new Error(`Không kết nối được ${BASE} (${e.message}). Chạy docker compose up -d và đặt E2E_BASE_URL nếu WEB_PORT khác 80.`) })
  if (health.status === 401) throw new Error('Chưa có dữ liệu demo: chạy python3 scripts/seed_demo.py trước.')
  browser = await chromium.launch({ headless: HEADLESS })
})
after(async () => { await browser?.close() })

/** A logged-in page for one demo account, at the given viewport (laptop by default). */
async function session(who, viewport = { width: 1366, height: 900 }) {
  const ctx = await browser.newContext({ viewport, locale: 'vi-VN', timezoneId: 'Asia/Ho_Chi_Minh', acceptDownloads: true })
  const page = await ctx.newPage()
  page.errors = []
  page.on('pageerror', e => page.errors.push(e.message))
  await page.goto(BASE + '/login')
  await page.fill('input[type=email]', mail(who))
  await page.fill('input[type=password]', PASSWORD)
  await page.locator('form button[type=submit]').first().click()
  await page.waitForURL(/\/dashboard/, { timeout: 30_000 })
  return page
}
async function done(page) {
  assert.deepEqual(page.errors, [], 'no uncaught errors in the browser')
  await page.context().close()
}

// ───────────────────────────── API: accounts, roles, seeded data ─────────────────────────────

test('every demo account signs in; a wrong password does not', async () => {
  for (const who of ['gv.toan', 'gv.anh', 'gv.ly', 'gv.hoa', 'hs01', 'hs12', 'hs13', 'hs24']) {
    const r = await call('/auth/login', null, { method: 'POST', body: { email: mail(who), password: PASSWORD } })
    assert.ok(r.body.accessToken, who)
  }
  const bad = await call('/auth/login', null, { method: 'POST', body: { email: mail('hs01'), password: 'sai-mat-khau' }, ok: null })
  assert.equal(bad.status, 401)
})

test('role boundaries hold through the gateway', async () => {
  assert.equal((await call('/exams/my', null, { ok: null })).status, 401, 'no token')
  assert.equal((await call('/analytics/admin/overview', 'hs01', { ok: null })).status, 403, 'student is not admin')
  assert.equal((await call('/analytics/admin/overview', 'gv.toan', { ok: null })).status, 403, 'teacher is not admin')
  const mine = await get('/questions?size=5', 'hs01')
  assert.equal(mine.totalElements, 0, 'students see no teacher question bank')
  // A student cannot read another class's exam paper with answers.
  const exams = (await get('/exams?size=50', 'gv.toan')).content
  const a1Only = byTitle(exams, 'Kiểm tra 15 phút — Hàm số')
  assert.ok(a1Only, 'seeded 12A1 exam')
  const stranger = await call(`/exams/${a1Only.id}/paper`, 'hs24', { ok: null })
  assert.ok([403, 404].includes(stranger.status), `hs24 (12A2) must not open a 12A1 paper, got ${stranger.status}`)
})

test('seeded data is complete and consistent across services', async () => {
  const toan = (await get('/exams?size=50', 'gv.toan')).content
  const statuses = Object.fromEntries(toan.map(e => [e.title, e.status]))
  assert.equal(statuses['Kiểm tra giữa kỳ I — Toán 12'], 'CLOSED')
  assert.equal(statuses['Kiểm tra cuối kỳ I — Toán 12'], 'PUBLISHED', 'scheduled exam waits for its start time')
  const questions = await get('/questions?size=1', 'gv.toan')
  assert.ok(questions.totalElements >= 10, `gv.toan has questions (${questions.totalElements})`)

  const my = await get('/exams/my', 'hs01')
  assert.ok(byTitle(my, 'Kiểm tra cuối kỳ I'), 'the scheduled final is on the student list')
  const progress = await get('/grades/my', 'hs01')
  assert.ok(progress.overview.attempts >= 1, 'student has graded attempts')
  assert.ok(progress.overview.averagePercent >= 0 && progress.overview.averagePercent <= 100)

  // Grading and exam agree: every submission of a closed exam belongs to it and has a status.
  const mid = byTitle(toan, 'Kiểm tra giữa kỳ I')
  const subs = await get(`/submissions?examId=${mid.id}&size=50`, 'gv.toan')
  assert.ok(subs.totalElements >= 10, `midterm submissions (${subs.totalElements})`)
  for (const s of subs.content) assert.ok(s.status, 'submission has a status')
})

test('AI question generation works without any external AI (local fallback at worst)', async t => {
  const list = x => (Array.isArray(x) ? x : x?.content ?? [])
  const subject = list(await get('/subjects', 'gv.hoa')).find(s => /Hóa|Hoá/.test(s.name)) ?? list(await get('/subjects', 'gv.hoa'))[0]
  const cat = list(await get(`/categories?subjectId=${subject.id}`, 'gv.hoa'))[0]
  assert.ok(cat, 'gv.hoa has a topic')
  const job = (await call('/question-imports/ai', 'gv.hoa', { method: 'POST', ok: [202, 429],
    body: { topic: 'Este và chất béo', material: 'Este là hợp chất hữu cơ tạo thành khi thay nhóm OH của axit cacboxylic bằng nhóm OR. Chất béo là trieste của glixerol với axit béo. Phản ứng xà phòng hoá chất béo trong dung dịch kiềm tạo ra glixerol và muối của axit béo (xà phòng). Este có mùi thơm, nhẹ hơn nước và ít tan trong nước.',
      categoryId: cat.id, count: 2, questionTypes: ['MULTIPLE_CHOICE'], difficulty: 'EASY', language: 'vi' } })).body
  if (!job?.id) return t.skip('gv.hoa đang có một lượt AI khác chạy') // one AI job in flight per teacher
  let j = job
  for (let i = 0; i < 90 && j.status === 'PROCESSING'; i++) { await new Promise(r => setTimeout(r, 2000)); j = await get(`/question-imports/${job.id}`, 'gv.hoa') }
  assert.equal(j.status, 'READY', `AI job ended as ${j.status}: ${j.failureReason ?? ''}`)
  assert.ok(j.summary.total >= 1, 'at least one generated draft')
})

// ───────────────────────────── Browser: student ─────────────────────────────

test('student sees exams, a released result and progress', async () => {
  const page = await session('hs13')
  await page.goto(BASE + '/my-exams')
  await page.getByText('Thi thử THPT lần 1 — Tiếng Anh').first().waitFor()
  await page.getByText('Kiểm tra cuối kỳ I — Toán 12').first().waitFor()

  await page.goto(BASE + '/progress')
  await page.getByRole('heading', { level: 1 }).waitFor()
  assert.ok(await page.locator('svg').count() > 0, 'progress chart is drawn')
  await done(page)
})

test('student joins a practice exam by code and gets instant feedback', async t => {
  const lyExams = (await get('/exams?size=50', 'gv.ly')).content
  const coded = byTitle(lyExams, 'Luyện tập — Sóng cơ')
  assert.ok(coded?.code, 'seeded join-code exam')
  assert.ok((await call('/exams/code/000000/join', 'hs05', { method: 'POST', ok: null })).status >= 400, 'a wrong code is refused')

  // Two attempts per student: take the first student who still has one, so the suite can run again.
  for (const who of ['hs05', 'hs06', 'hs07', 'hs08', 'hs09', 'hs10', 'hs11', 'hs14', 'hs15', 'hs16', 'hs17', 'hs18']) {
    const joined = await call(`/exams/code/${coded.code}/join`, who, { method: 'POST' })
    assert.equal(joined.body.id, coded.id, 'the code leads to the right exam')
    const page = await session(who)
    await page.goto(`${BASE}/exam/lobby/${coded.id}`)
    await page.getByText('Lượt làm bài của bạn').waitFor({ timeout: 20_000 })
    const start = page.getByRole('button', { name: /Bắt đầu|Làm lại|Tiếp tục/ }).first()
    const ready = page.getByLabel('Tôi đã đọc hướng dẫn và sẵn sàng bắt đầu.')
    if (!(await ready.count())) { await done(page); continue } // no attempt left: the lobby offers no start
    await ready.check()
    await start.click()
    await page.waitForURL(/\/exam\/take\//, { timeout: 30_000 })
    const check = page.getByRole('button', { name: /Kiểm tra đáp án/ }).first()
    await check.waitFor({ timeout: 20_000 })
    // Answer the first question on screen with its first choice, or type into its first text box.
    const choice = page.locator('label.choice').first()
    if (await choice.count()) await choice.click()
    else await page.locator('textarea, input[type=text]').first().fill('A')
    await check.click()
    await page.getByText(/Chính xác|Chưa đúng|Đúng một phần/).first().waitFor({ timeout: 15_000 })
    await done(page)
    return
  }
  t.skip('mọi học sinh thử đã dùng hết lượt làm đề luyện tập (seed lại để chạy)')
})

test('calendar lists the scheduled exam and exports it as .ics', async () => {
  const page = await session('hs02')
  const final = byTitle(await get('/exams/my', 'hs02'), 'Kiểm tra cuối kỳ I')
  const sched = await get(`/exams/${final.id}/schedule`, 'gv.toan')
  await page.goto(BASE + '/calendar')
  await page.locator('.cal-arrow').first().waitFor()
  // The month on screen is the current one; walk forward to the exam's month (seeded six days ahead).
  const monthsAhead = (d => (d.getFullYear() - new Date().getFullYear()) * 12 + d.getMonth() - new Date().getMonth())(new Date(Date.parse(sched.startAt) + 7 * 3600_000))
  for (let i = 0; i < monthsAhead; i++) await page.getByRole('button', { name: 'Tháng sau' }).click()
  await page.getByText('Kiểm tra cuối kỳ I — Toán 12').first().waitFor({ timeout: 20_000 })
  const ics = await fetch(`${API}/exams/${final.id}/calendar.ics`, { headers: { Authorization: 'Bearer ' + await token('hs02') } })
  assert.equal(ics.status, 200)
  const text = await ics.text()
  assert.match(text, /BEGIN:VCALENDAR/)
  assert.match(text, /SUMMARY:.*cuối kỳ/)
  await done(page)
})

test('student reviews a released result: mistakes filter and AI tutor', async () => {
  const items = (await get('/grades/my', 'hs13')).items
  const mock = items.find(i => i.title.startsWith('Thi thử THPT') && i.phase === 'PUBLISHED')
  assert.ok(mock, 'seeded released English result')
  const page = await session('hs13')
  await page.goto(`${BASE}/exam/result/${mock.sessionId}`)
  await page.locator('.result-q').first().waitFor({ timeout: 20_000 })
  const all = await page.locator('.result-q').count()
  await page.getByLabel('Chỉ xem câu sai').check()
  const wrong = await page.locator('.result-q').count()
  assert.ok(wrong > 0 && wrong < all, `filter keeps only mistakes (${wrong}/${all})`)
  const ask = page.getByRole('button', { name: /Vì sao mình sai/ }).first()
  if (await ask.count()) { // the English mock reveals keys, so the tutor is offered on its mistakes
    await ask.click()
    await page.locator('.tutor').first().waitFor({ timeout: 90_000 })
    assert.ok((await page.locator('.tutor').first().innerText()).length > 40, 'an explanation is shown (AI or key + teacher note)')
  }
  await done(page)
})

test('exam taking on a laptop: keyboard answers and review marks', async t => {
  const hoa = (await get('/exams?size=50', 'gv.hoa')).content
  const practice = byTitle(hoa, 'Ôn tập Este')
  for (const who of ['hs22', 'hs23', 'hs24', 'hs19', 'hs20', 'hs21']) {
    await call(`/exams/${practice.id}/join`, who, { method: 'POST', ok: [200, 201, 204, 409] })
    const page = await session(who)
    await page.goto(`${BASE}/exam/lobby/${practice.id}`)
    await page.getByText('Lượt làm bài của bạn').waitFor({ timeout: 20_000 })
    const ready = page.getByLabel('Tôi đã đọc hướng dẫn và sẵn sàng bắt đầu.')
    if (!(await ready.count())) { await done(page); continue }
    await ready.check()
    await page.getByRole('button', { name: /Bắt đầu|Làm lại|Tiếp tục/ }).first().click()
    await page.waitForURL(/\/exam\/take\//, { timeout: 30_000 })
    await page.locator('#question-heading').waitFor()
    await page.keyboard.press('b')
    assert.equal(await page.locator('input[type=radio]:checked').count(), 1, 'B picks an option')
    await page.keyboard.press('f')
    await page.keyboard.press('ArrowRight')
    assert.match(await page.locator('#question-heading').innerText(), /^Câu 2/)
    assert.equal(await page.locator('.palette button[data-flagged=true]').count(), 1, 'F marks question 1 for review')
    await page.getByRole('button', { name: 'Nộp bài' }).first().click()
    await page.getByText('Câu bạn đánh dấu để xem lại:').waitFor()
    await done(page)
    return
  }
  t.skip('các học sinh thử đã dùng hết lượt làm đề luyện tập (seed lại để chạy)')
})

// ───────────────────────────── Browser: teacher ─────────────────────────────

test('teacher grades a waiting essay and the queue shrinks', async t => {
  // Seeded with essays left ungraded: 3 in the English mock test, 6 in the physics test.
  let teacher, mock, waiting
  for (const [who, title] of [['gv.anh', 'Thi thử THPT lần 1'], ['gv.ly', 'Kiểm tra 1 tiết — Dao động cơ']]) {
    const exam = byTitle((await get('/exams?size=50', who)).content, title)
    const w = await get(`/submissions?examId=${exam.id}&status=NEEDS_REVIEW&size=5`, who)
    if (w.totalElements > 0) { teacher = who; mock = exam; waiting = w; break }
  }
  if (!teacher) return t.skip('không còn bài chờ chấm (đã chấm hết ở các lần chạy trước; seed lại để chạy)')
  const before = await get(`/submissions/overview?examId=${mock.id}`, teacher)
  const sub = waiting.content[0]
  const page = await session(teacher)
  const queue = `${BASE}/grading?examId=${mock.id}&status=NEEDS_REVIEW`
  await page.goto(queue)
  const row = page.getByText(sub.studentDisplayName).first()
  await row.waitFor({ timeout: 20_000 })
  const detail = await get(`/submissions/${sub.id}`, teacher)
  const items = detail.items.filter(i => i.needsReview).map(i => ({ questionId: i.questionId, score: Math.min(1, i.pointValue ?? 1), feedback: 'Đủ ý, chú ý ngữ pháp.', overrideReason: null }))
  assert.ok(items.length > 0, 'a waiting submission has items to grade')
  const drafted = (await call(`/submissions/${sub.id}/draft`, teacher, { method: 'PUT', body: { expectedRevision: detail.resultRevision, feedback: 'Khá tốt.', items } })).body
  const finalized = (await call(`/submissions/${sub.id}/finalize`, teacher, { method: 'POST', body: { expectedRevision: drafted.resultRevision } })).body
  assert.equal(finalized.status, 'GRADED')
  const after = await get(`/submissions/overview?examId=${mock.id}`, teacher)
  assert.equal(after.needsReview, before.needsReview - 1, 'one fewer submission waiting')
  assert.equal(after.graded, before.graded + 1)
  // A stale revision is refused (two teachers grading the same paper).
  assert.equal((await call(`/submissions/${sub.id}/draft`, teacher, { method: 'PUT', ok: null, body: { expectedRevision: detail.resultRevision, feedback: null, items } })).status, 409)
  await page.goto(queue)
  await page.getByRole('heading', { level: 1 }).waitFor()
  await page.waitForLoadState('networkidle')
  await done(page)
})

test('moving an exam notifies the student in the bell', async () => {
  const toan = (await get('/exams?size=50', 'gv.toan')).content
  const final = byTitle(toan, 'Kiểm tra cuối kỳ I')
  const sched = await get(`/exams/${final.id}/schedule`, 'gv.toan')
  const start = new Date(Date.parse(sched.startAt) + 15 * 60_000)
  await call(`/exams/${final.id}/schedule`, 'gv.toan', { method: 'PUT', body: {
    startAt: vnIso(start.getTime()), endAt: vnIso(start.getTime() + 90 * 60_000), timezone: 'Asia/Ho_Chi_Minh',
    deadlineRule: 'CAPPED_AT_END', autoOpen: true, autoClose: true, reminderOffsetsMinutes: [1440, 30],
    expectedRevision: sched.scheduleRevision, reason: 'Kiểm thử tự động: lùi 15 phút' } })
  // Fan-out is asynchronous (outbox → RabbitMQ → notification-service).
  let found = null
  for (let i = 0; i < 30 && !found; i++) {
    const inbox = await get('/notifications?size=10&group=SCHEDULE', 'hs03')
    found = inbox.content.find(n => n.type === 'EXAM_RESCHEDULED' && n.entityId === final.id && Date.parse(n.createdAt) > Date.now() - 120_000)
    if (!found) await new Promise(r => setTimeout(r, 1000))
  }
  assert.ok(found, 'hs03 got EXAM_RESCHEDULED')
  const page = await session('hs03', { width: 390, height: 844 })
  await page.goto(BASE + '/notifications')
  await page.getByText('Lịch thi đã thay đổi').first().waitFor({ timeout: 15_000 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, 'no sideways scroll on a phone')
  await done(page)
})

test('quick find (Ctrl+K) jumps to an exam typed without accents', async () => {
  const page = await session('gv.toan')
  await page.locator('.cmd-open').waitFor()
  await page.keyboard.press('Control+k')
  await page.getByPlaceholder('Tìm trang, thao tác hoặc kỳ thi…').fill('giua ky')
  await page.getByRole('option', { name: /giữa kỳ I/ }).waitFor({ timeout: 15_000 })
  await page.keyboard.press('Enter')
  await page.waitForURL(/\/exams\/[0-9a-f-]{36}$/, { timeout: 15_000 })
  await page.getByRole('heading', { level: 1, name: /giữa kỳ/ }).waitFor()
  // The published exam shows its frozen questions, not ids.
  await page.getByText('Tập xác định của hàm số').first().waitFor({ timeout: 15_000 })
  await done(page)
})

test('teacher reviews a question with AI; a wrong key is caught', async () => {
  const qs = (await get('/questions?size=50', 'gv.toan')).content
  const any = qs.find(q => q.questionType === 'MULTIPLE_CHOICE')
  const created = (await call('/questions', 'gv.toan', { method: 'POST', body: { categoryId: any.categoryId, content: 'Kiểm thử tự động: 7 × 8 bằng bao nhiêu?', questionType: 'MULTIPLE_CHOICE', difficultyLevel: 'EASY',
    options: [{ content: '54', correct: true, displayOrder: 0 }, { content: '56', correct: false, displayOrder: 1 }, { content: '64', correct: false, displayOrder: 2 }, { content: '48', correct: false, displayOrder: 3 }] } })).body
  try {
    const r = (await call(`/questions/${created.id}/ai-review`, 'gv.toan', { method: 'POST' })).body
    if (r.aiAvailable) assert.equal(r.keyCheck, 'DISPUTED', 'AI solves 7 × 8 = 56, not the stored 54')
    else assert.equal(r.keyCheck, 'UNCHECKED')
    const page = await session('gv.toan')
    await page.goto(`${BASE}/questions?q=${created.id}`)
    await page.getByRole('button', { name: 'Rà soát bằng AI' }).click()
    await page.getByText(r.aiAvailable ? /AI tự giải ra đáp án khác/ : /Chưa có AI/).first().waitFor({ timeout: 90_000 })
    await done(page)
  } finally {
    await call(`/questions/${created.id}`, 'gv.toan', { method: 'DELETE', ok: null })
  }
})

test('teacher exports the question bank as QTI 2.1', async () => {
  const page = await session('gv.toan')
  await page.goto(BASE + '/questions')
  await page.getByRole('button', { name: 'Xuất', exact: true }).click()
  await page.getByText('QTI 2.1 (ZIP)').click()
  const [dl] = await Promise.all([page.waitForEvent('download', { timeout: 60_000 }), page.getByRole('button', { name: 'Tải xuống' }).click()])
  const path = await dl.path()
  const { readFileSync } = await import('node:fs')
  const zip = readFileSync(path)
  assert.equal(zip.readUInt32LE(0), 0x04034b50, 'zip file')
  assert.ok(zip.includes(Buffer.from('imsmanifest.xml')), 'has a manifest')
  assert.ok(zip.includes(Buffer.from('assessmentItem')), 'has items')
  await done(page)
})

// ───────────────────────────── Browser: admin ─────────────────────────────

test('admin statistics page loads real numbers', { skip: !process.env.E2E_ADMIN_EMAIL && 'đặt E2E_ADMIN_EMAIL/E2E_ADMIN_PASSWORD để chạy' }, async () => {
  const r = await call('/auth/login', null, { method: 'POST', body: { email: process.env.E2E_ADMIN_EMAIL, password: process.env.E2E_ADMIN_PASSWORD } })
  tokens.admin = r.body.accessToken
  const overview = await get('/analytics/admin/overview', 'admin')
  assert.ok(overview, 'overview')
  const ctx = await browser.newContext({ viewport: { width: 1366, height: 900 } })
  const page = await ctx.newPage(); page.errors = []; page.on('pageerror', e => page.errors.push(e.message))
  await page.goto(BASE + '/login')
  await page.fill('input[type=email]', process.env.E2E_ADMIN_EMAIL); await page.fill('input[type=password]', process.env.E2E_ADMIN_PASSWORD)
  await page.locator('form button[type=submit]').first().click(); await page.waitForURL(/\/(dashboard|admin)/)
  await page.goto(BASE + '/admin/stats')
  await page.getByRole('heading', { level: 1 }).waitFor()
  await done(page)
})
