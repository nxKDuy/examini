import { chromium } from 'playwright'
const B = 'http://localhost:8088', OUT = process.argv[2]
const api = async (p, tok) => (await fetch(B + '/api' + p, { headers: { Authorization: 'Bearer ' + tok } })).json()
const login = async (e, pw = 'Examini@2026') => (await (await fetch(B + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: e, password: pw }) })).json()).accessToken
const t = await login('gv.toan@examini.demo'), s = await login('hs13@examini.demo')
const toan = (await api('/exams?size=50', t)).content, mid = toan.find(e => e.title.startsWith('Kiểm tra giữa kỳ'))
const my = await api('/exams/my', s), eng = my.find(e => e.title.startsWith('Thi thử'))
const sess = (await api(`/sessions/exam/${eng.id}/mine`, s).catch(() => null))
const roles = {
  student: ['hs13@examini.demo', 'Examini@2026', ['/dashboard', '/my-exams', `/exam/lobby/${eng.id}`, '/progress', '/calendar', '/notifications', '/profile', '/organizations', '/exam/lobby/discover']],
  teacher: ['gv.toan@examini.demo', 'Examini@2026', ['/dashboard', '/questions', '/questions/imports', '/exams', `/exams/${mid.id}`, `/grading?examId=${mid.id}`, '/calendar', '/public-bank', '/exams/create']],
  admin: [process.env.ADMIN_EMAIL, process.env.ADMIN_PASSWORD, ['/admin/stats', '/admin/users', '/dashboard']],
}
const b = await chromium.launch()
const report = []
for (const [role, [email, pw, pages]] of Object.entries(roles)) for (const [W, H, dev] of [[1366, 900, 'laptop'], [390, 844, 'phone']]) {
  const ctx = await b.newContext({ viewport: { width: W, height: H }, locale: 'vi-VN', timezoneId: 'Asia/Ho_Chi_Minh' })
  const page = await ctx.newPage()
  let errs = []; page.on('pageerror', e => errs.push('JS ' + e.message.slice(0, 120)))
  page.on('response', r => { if (r.url().includes('/api/') && r.status() >= 400 && r.status() !== 401) errs.push(`API ${r.status()} ${r.url().replace(B, '').slice(0, 90)}`) })
  await page.goto(B + '/login'); await page.fill('input[type=email]', email); await page.fill('input[type=password]', pw)
  await page.locator('form button[type=submit]').first().click(); await page.waitForURL(/dashboard|admin/, { timeout: 30000 })
  for (const p of pages) {
    errs = []
    await page.goto(B + p, { waitUntil: 'networkidle' }).catch(() => {}); await page.waitForTimeout(700)
    const info = await page.evaluate(() => {
      const W = innerWidth, wide = [], clipped = []
      for (const e of document.querySelectorAll('main *, header *')) {
        const r = e.getBoundingClientRect(); if (!r.width) continue
        const cs = getComputedStyle(e)
        if (r.right > W + 1 && !e.closest('[style*="overflow"], .table-wrap, .tabs, .notif-filter, .cal-scroll, pre') && cs.position !== 'fixed') wide.push(`${e.tagName.toLowerCase()}.${String(e.className).split(' ')[0]}→${Math.round(r.right)}`)
        if ((e.scrollWidth > e.clientWidth + 2) && cs.overflowX === 'hidden' && cs.textOverflow !== 'ellipsis' && e.children.length === 0 && e.textContent.trim()) clipped.push(e.textContent.trim().slice(0, 30))
      }
      return { overflow: document.documentElement.scrollWidth > W, wide: [...new Set(wide)].slice(0, 4), clipped: clipped.slice(0, 3), h1: document.querySelector('h1')?.textContent?.slice(0, 40) ?? '(no h1)' }
    })
    const name = `${role}-${dev}-${p.replace(/[^a-z]+/gi, '_').replace(/_[0-9a-f]{8}_.*$/, '_x').slice(0, 30)}`
    await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: dev === 'phone' })
    report.push(`${name.padEnd(48)} h1=${info.h1}${info.overflow ? ' OVERFLOW' : ''}${info.wide.length ? ' wide:' + info.wide.join(',') : ''}${info.clipped.length ? ' clipped:' + info.clipped.join('|') : ''}${errs.length ? ' ERR:' + [...new Set(errs)].join(' ; ') : ''}`)
  }
  await ctx.close()
}
await b.close()
console.log(report.join('\n'))
