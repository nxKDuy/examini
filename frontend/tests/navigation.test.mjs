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
    if (/\.tsx?$/.test(url)) return { format: 'module', shortCircuit: true, source: ts.transpileModule(readFileSync(new URL(url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX } }).outputText }
    return next(url, context)
  },
})
globalThis.localStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {} }
globalThis.window = { localStorage: globalThis.localStorage }
const n = await import('../src/components/layout/navigation.ts')
const names = items => items.map(i => i.label)

test('Student has exactly five destinations: all five fit the dock and there is no "Khác"', () => {
  const { dock, more } = n.dockFor('STUDENT')
  assert.deepEqual(names(dock), ['Tổng quan', 'Ngân hàng công khai', 'Lịch của tôi', 'Kỳ thi của tôi', 'Tổ chức']); assert.deepEqual(more, [])
})

test('Teacher: four priority tiles (Tổng quan, Lịch thi, Đề thi, Chấm bài) and the rest behind Khác; Admin has its own priority set', () => {
  const t = n.dockFor('TEACHER')
  assert.deepEqual(names(t.dock), ['Tổng quan', 'Lịch thi', 'Đề thi', 'Chấm bài']); assert.deepEqual(names(t.more), ['Kho cá nhân', 'Ngân hàng công khai', 'Tổ chức'])
  const a = n.dockFor('ADMIN')
  assert.deepEqual(names(a.dock), ['Tổng quan', 'Câu hỏi', 'Đề thi', 'Chấm bài']); assert.deepEqual(names(a.more), ['Người dùng', 'Ngân hàng công khai', 'Tổ chức', 'Lịch thi'])
})

test('Dock never exceeds five tiles including Khác, and dock + sheet together are every destination exactly once (nothing lost, nothing duplicated)', () => {
  for (const audience of ['STUDENT', 'TEACHER', 'ADMIN']) {
    const { dock, more } = n.dockFor(audience)
    assert.ok(dock.length + (more.length ? 1 : 0) <= n.DOCK_SLOTS, audience)
    const all = n.navigationItems(audience).map(i => i.to).sort()
    assert.deepEqual([...dock, ...more].map(i => i.to).sort(), all, audience)
    assert.equal(new Set(all).size, all.length, audience)
  }
})

test('Destinations follow the audience: TEACHER never gets the ADMIN question queue label and ADMIN is not a Teacher', () => {
  assert.ok(!n.navigationItems('TEACHER').some(i => i.label === 'Câu hỏi')); assert.ok(n.navigationItems('ADMIN').some(i => i.label === 'Câu hỏi'))
  assert.ok(!n.navigationItems('STUDENT').some(i => ['/exams', '/grading', '/questions'].includes(i.to)))
})

test('Every dock tile has a short label that fits a ~64px tile (or is already short)', () => {
  for (const audience of ['STUDENT', 'TEACHER', 'ADMIN']) for (const i of n.dockFor(audience).dock) assert.ok((i.shortLabel ?? i.label).length <= 12, `${audience} ${i.label}`)
})
