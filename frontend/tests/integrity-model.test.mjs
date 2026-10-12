import { test } from 'node:test'
import assert from 'node:assert/strict'
import { registerHooks } from 'node:module'
import { readFileSync, existsSync } from 'node:fs'
import ts from 'typescript'

registerHooks({
  resolve(specifier, context, next) {
    if (specifier.startsWith('.') && context.parentURL?.includes('/src/')) {
      const url = new URL(specifier, context.parentURL)
      for (const ext of ['.ts', '.tsx']) if (existsSync(new URL(url.href + ext))) return { url: url.href + ext, shortCircuit: true }
    }
    return next(specifier, context)
  },
  load(url, context, next) {
    if (/\.tsx?$/.test(url)) return { format: 'module', shortCircuit: true, source: ts.transpileModule(readFileSync(new URL(url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText }
    return next(url, context)
  },
})
const m = await import('../src/features/integrity/integrityModel.ts')

test('student stage follows the receipt: counting, last warning at the limit, submitted above it', () => {
  assert.equal(m.signalState({ warningCount: 0, warningLimit: 5, warningRecommended: false, autoSubmitted: false }).stage, 'none')
  assert.equal(m.signalState({ warningCount: 2, warningLimit: 5, warningRecommended: false, autoSubmitted: false }).stage, 'counting')
  assert.equal(m.signalState({ warningCount: 5, warningLimit: 5, warningRecommended: true, autoSubmitted: false }).stage, 'last')
  assert.equal(m.signalState({ warningCount: 6, warningLimit: 5, warningRecommended: false, autoSubmitted: true }).stage, 'submitted')
})

test('threshold is an integer 1..100; disabled policy keeps the default 5', () => {
  assert.equal(m.DISABLED_POLICY.warningEventLimit, 5)
  assert.equal(m.limitError(1), null)
  assert.equal(m.limitError(100), null)
  assert.ok(m.limitError(0))
  assert.ok(m.limitError(101))
  assert.ok(m.limitError(2.5))
})

test('copy/paste-only policy is monitored but has no budget', () => {
  const p = { ...m.DISABLED_POLICY, monitorCopy: true }
  assert.equal(m.monitored(p), true)
  assert.equal(m.counts(p), false)
  assert.equal(m.policySummary(p), 'sao chép')
  assert.equal(m.policySummary(m.DISABLED_POLICY), 'Tắt')
  assert.match(m.policySummary({ ...p, monitorTabSwitch: true, warningEventLimit: 3 }), /tự nộp khi vượt 3 tín hiệu/)
})

test('reports retry only on rate limit, server or network failure, with capped backoff', () => {
  assert.equal(m.retryDelay(429, 0), 1000)
  assert.equal(m.retryDelay(503, 3), 8000)
  assert.equal(m.retryDelay(0, 5), 30000)
  assert.equal(m.retryDelay(429, 6), null)
  assert.equal(m.retryDelay(409, 0), null)
  assert.equal(m.retryDelay(400, 0), null)
})

test('grant is offered only for attempts ended by integrity', () => {
  assert.equal(m.integrityEnded([{ action: 'INTEGRITY_BUDGET_AUTO_SUBMITTED' }]), true)
  assert.equal(m.integrityEnded([{ action: 'EXAM_INTEGRITY_LOCK_AUTO_SUBMITTED' }]), true)
  assert.equal(m.integrityEnded([{ action: 'SOMETHING_ELSE' }]), false)
  assert.equal(m.metadataText({ durationMs: 12000, networkState: 'OFFLINE' }), '12 giây · ngoại tuyến')
})

test('budget signals match the contract; wording never says gian lận except to deny it', () => {
  assert.deepEqual([...m.BUDGET_SIGNALS].sort(), ['EXIT_FULLSCREEN', 'NETWORK_DISCONNECT', 'PAGE_RELOAD', 'TAB_SWITCH', 'WINDOW_BLUR'])
  assert.ok(Object.values(m.SIGNAL_LABEL).every(l => !/gian lận/i.test(l)))
})

test('one leave transition (blur then hidden) is one TAB_SWITCH and one acknowledgment', () => {
  const t = new m.LeaveTracker(true, true)
  assert.equal(t.onBlur(0), true)
  assert.deepEqual(t.onHidden(200), { type: 'TAB_SWITCH', metadata: { visibilityState: 'HIDDEN' } })
  assert.equal(t.onTimeout(), null, 'blur timer after hidden adds nothing')
  assert.equal(t.onHidden(300), null)
  const r = t.onReturn(5000)
  assert.equal(r.signal, null)
  assert.equal(r.ack, true)
  assert.equal(r.focusDurationMs, 5000)
  assert.equal(t.onReturn(5100).ack, false, 'second return event is a no-op')
})

test('blur without hiding counts once as WINDOW_BLUR, even when it ends before the window', () => {
  const a = new m.LeaveTracker(true, true)
  a.onBlur(0)
  assert.equal(a.onTimeout().type, 'WINDOW_BLUR')
  assert.equal(a.onHidden(2000), null)
  assert.equal(a.onReturn(3000).ack, true)
  const b = new m.LeaveTracker(true, true)
  b.onBlur(0)
  const r = b.onReturn(400)
  assert.equal(r.signal.type, 'WINDOW_BLUR')
  assert.equal(r.signal.metadata.durationMs, 400)
  assert.equal(r.ack, true)
})

test('tab-only monitoring ignores a short window blur; nothing monitored records nothing', () => {
  const t = new m.LeaveTracker(true, false)
  assert.equal(t.onBlur(0), false)
  assert.equal(t.onTimeout(), null)
  assert.equal(t.onReturn(800).ack, false)
  const off = new m.LeaveTracker(false, false)
  assert.equal(off.onHidden(0), null)
  assert.equal(off.onReturn(10).ack, false)
})

test('leaving the tab while fullscreen is one transition: EXIT_FULLSCREEN covers the tab switch', () => {
  const t = new m.LeaveTracker(true, true)
  t.onFullscreenExit(0)
  assert.equal(t.onBlur(100), false, 'no blur timer: already recorded')
  assert.equal(t.onHidden(150), null)
  assert.equal(t.onTimeout(), null)
  const r = t.onReturn(4000)
  assert.equal(r.signal, null)
  assert.equal(r.ack, false, 'the fullscreen overlay handles it')
  // An old Esc exit does not swallow a later, separate tab switch.
  t.onFullscreenExit(10_000)
  assert.equal(t.onHidden(20_000).type, 'TAB_SWITCH')
  // Exit right after the tab hid (reverse order) still marks the transition covered for the blur path.
  const u = new m.LeaveTracker(false, true)
  u.onBlur(0); u.onFullscreenExit(50)
  assert.equal(u.onTimeout(), null)
})
