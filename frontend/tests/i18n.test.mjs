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
const store = new Map()
globalThis.localStorage = { getItem: k => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: k => store.delete(k) }
const i = await import('../src/lib/i18n.ts')
const { EN } = await import('../src/lib/i18n-en.ts')

test('Vietnamese is the default and the key; English falls back to Vietnamese when untranslated', () => {
  assert.equal(i.useLangStore.getState().lang, 'vi')
  assert.equal(i.translate('vi', 'Đăng xuất'), 'Đăng xuất')
  assert.equal(i.translate('en', 'Đăng xuất'), 'Sign out')
  assert.equal(i.translate('en', 'Chưa có bản dịch'), 'Chưa có bản dịch')
  assert.equal(i.translate('vi', 'Còn {n} câu', { n: 3 }), 'Còn 3 câu')
})

test('the choice is kept per browser and per account; the account choice wins at sign-in', () => {
  i.useLangStore.getState().setLang('en')
  assert.equal(store.get('examini.lang'), 'en')
  i.useLangStore.getState().bindAccount('u1')
  i.useLangStore.getState().setLang('vi')
  assert.equal(store.get('examini.lang.u1'), 'vi')
  store.set('examini.lang.u2', 'en')
  i.useLangStore.getState().bindAccount('u2')
  assert.equal(i.useLangStore.getState().lang, 'en')
  assert.equal(i.t('Hủy'), 'Cancel')
})

test('every English entry keeps the same {placeholders} as its Vietnamese key', () => {
  const ph = s => (s.match(/\{\w+\}/g) ?? []).sort().join()
  const bad = Object.entries(EN).filter(([vi, en]) => ph(vi) !== ph(en) || !en.trim())
  assert.deepEqual(bad, [])
})

test('i18nMap translates string values when read and keeps keys, nesting and arrays working', () => {
  i.useLangStore.getState().setLang('vi')
  const m = i.i18nMap({ A: 'Hủy', nested: { label: 'Đăng xuất' }, list: [{ label: 'Hủy' }] })
  assert.equal(m.A, 'Hủy')
  i.useLangStore.getState().setLang('en')
  assert.equal(m.A, 'Cancel')
  assert.equal(m.nested.label, 'Sign out')
  assert.deepEqual(m.list.map(x => x.label), ['Cancel'])
  assert.deepEqual(Object.entries(m).filter(([, v]) => typeof v === 'string'), [['A', 'Cancel']])
  i.useLangStore.getState().setLang('vi')
})
