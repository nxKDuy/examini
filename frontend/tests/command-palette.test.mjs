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
const cm = await import('../src/features/command/commandModel.ts')

const C = [
  { id: '1', group: 'Trang', label: 'Lịch thi', to: '/calendar' },
  { id: '2', group: 'Trang', label: 'Đề thi', to: '/exams' },
  { id: '3', group: 'Kỳ thi', label: 'Kiểm tra giữa kỳ I — Toán 12', hint: 'Mã 600863', keywords: '600863', to: '/exams/x' },
  { id: '4', group: 'Thao tác', label: 'Tạo đề thi', to: '/exams/create' },
]

test('accent-insensitive search finds Vietnamese labels', () => {
  assert.equal(cm.fold('Đề Thi  Lịch'), 'de thi lich')
  assert.deepEqual(cm.rank(C, 'lich').map(c => c.id), ['1'])
  assert.deepEqual(cm.rank(C, 'LỊCH THI').map(c => c.id), ['1'])
  assert.deepEqual(cm.rank(C, 'de thi').map(c => c.id), ['2', '4'], 'prefix of the whole label ranks first')
  assert.deepEqual(cm.rank(C, 'toan giua').map(c => c.id), ['3'], 'every word must match, in any order')
  assert.deepEqual(cm.rank(C, '600863').map(c => c.id), ['3'], 'exam code finds the exam')
  assert.deepEqual(cm.rank(C, 'xyz'), [])
  assert.equal(cm.rank(C, '').length, 4, 'empty query lists everything in the given order')
})
