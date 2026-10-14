import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import ts from 'typescript'

// Every Vietnamese literal passed to t()/tt() or held in an i18nMap() must have an English entry.
const load = async file => {
  const src = ts.transpileModule(readFileSync(file, 'utf8'), { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText
  return import('data:text/javascript,' + encodeURIComponent(src))
}
const { EN } = await load('src/lib/i18n-en.ts')
const { EN_UI } = await load('src/lib/i18n-en-ui.ts')
const VI = /[À-ỹĐđ]/
const walk = d => readdirSync(d).flatMap(f => { const p = path.join(d, f); return statSync(p).isDirectory() ? walk(p) : /\.tsx?$/.test(p) && !/lib\/i18n/.test(p) ? [p] : [] })

test('every translated Vietnamese literal has an English entry', () => {
  const missing = []
  for (const file of walk('src')) {
    const sf = ts.createSourceFile(file, readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, file.endsWith('x') ? ts.ScriptKind.TSX : ts.ScriptKind.TS)
    const check = s => { if (VI.test(s) && !(s in EN) && !(s in EN_UI)) missing.push(`${file}: ${s}`) }
    const visit = (n, inMap) => {
      if (ts.isCallExpression(n) && ts.isIdentifier(n.expression)) {
        if (['t', 'tt'].includes(n.expression.text) && n.arguments[0]) {
          const a = n.arguments[0]
          if (ts.isStringLiteral(a) || ts.isNoSubstitutionTemplateLiteral(a)) check(a.text)
          if (ts.isConditionalExpression(a)) for (const b of [a.whenTrue, a.whenFalse]) if (ts.isStringLiteral(b)) check(b.text)
        }
        if (n.expression.text === 'i18nMap') { ts.forEachChild(n, c => visit(c, true)); return }
      }
      if (inMap && (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) && !(ts.isPropertyAssignment(n.parent) && n.parent.name === n)) check(n.text)
      ts.forEachChild(n, c => visit(c, inMap))
    }
    visit(sf, false)
  }
  assert.deepEqual(missing, [])
})
