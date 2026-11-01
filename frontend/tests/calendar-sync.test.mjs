import { test } from 'node:test'
import assert from 'node:assert/strict'
import { registerHooks } from 'node:module'
import { readFileSync, existsSync } from 'node:fs'
import ts from 'typescript'

registerHooks({
  resolve(specifier, context, next) {
    if (specifier.endsWith('/lib/api')) return { url: 'data:text/javascript,export default {};export const apiBaseURL="/api/"', shortCircuit: true }
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
const m = await import('../src/features/calendar/calendarSync.ts')

test('feed URL follows the API base the app uses', () => {
  assert.equal(m.feedUrl('Tok_en-123', '/api/', 'https://thi.truong.edu.vn'), 'https://thi.truong.edu.vn/api/exams/calendar/feed/Tok_en-123.ics')
  assert.equal(m.feedUrl('abc', 'http://127.0.0.1:8080', 'http://localhost:3000'), 'http://127.0.0.1:8080/exams/calendar/feed/abc.ics')
  assert.equal(m.feedUrl('abc', '/', 'http://localhost:3000'), 'http://localhost:3000/exams/calendar/feed/abc.ics')
})

test('subscribe links', () => {
  const url = 'https://thi.example/api/exams/calendar/feed/abc.ics'
  assert.equal(m.webcalUrl(url), 'webcal://thi.example/api/exams/calendar/feed/abc.ics')
  assert.equal(m.googleSubscribeUrl(url), 'https://calendar.google.com/calendar/r?cid=' + encodeURIComponent('webcal://thi.example/api/exams/calendar/feed/abc.ics'))
  assert.equal(m.isLocalAddress('http://localhost:8088/x'), true)
  assert.equal(m.isLocalAddress('http://127.0.0.1/x'), true)
  assert.equal(m.isLocalAddress(url), false)
})

test('Google quick-add uses UTC and falls back to the duration', () => {
  const g = new URL(m.googleEventUrl({ examTitle: 'Toán 12', startAt: '2030-03-10T01:30:00Z', endAt: null, durationMinutes: 45 }, 'https://x/exam/lobby/1'))
  assert.equal(g.searchParams.get('dates'), '20300310T013000Z/20300310T021500Z')
  assert.equal(g.searchParams.get('text'), 'Thi: Toán 12')
  assert.equal(m.googleEventUrl({ examTitle: 'x', startAt: null, endAt: null, durationMinutes: 10 }, ''), null)
})

test('file names are ASCII slugs', () => {
  assert.equal(m.slug('Kiểm tra cuối kỳ — Toán 12 (Đề A)'), 'kiem-tra-cuoi-ky-toan-12-de-a')
})
