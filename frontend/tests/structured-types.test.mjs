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
const sm = await import('../src/features/question-bank/structuredModel.ts')
const cm = await import('../src/features/question-bank/choiceModel.ts')
const ex = await import('../src/features/question-bank/exportModel.ts')
const pm = await import('../src/features/exams/paperModel.ts')

test('fill in the blanks: gaps, accepted spellings and the answer map', () => {
  assert.equal(sm.countBlanks('Thủ đô là ___, năm _____.'), 2)
  assert.equal(sm.countBlanks('a__b'), 0)
  assert.deepEqual(sm.splitBlanks('A ___ B ___'), ['A ', ' B ', ''])
  assert.deepEqual(sm.alternatives(' TP.HCM | Sài Gòn |'), ['TP.HCM', 'Sài Gòn'])
  let v = sm.setText('', 'g1', 'Hà Nội')
  v = sm.setText(v, 'g2', '  ')
  assert.deepEqual(sm.parseTexts(v), { g1: 'Hà Nội' })
  assert.equal(sm.setText(v, 'g1', ''), '')
  assert.equal(cm.isAnswered('FILL_BLANK', v), true)
  assert.equal(cm.isAnswered('FILL_BLANK', '{"g1":"  "}'), false)
  const opts = [{ id: 'g1', content: 'Hà Nội | Ha Noi' }, { id: 'g2', content: '1945' }]
  assert.equal(sm.partsRight('FILL_BLANK', opts, JSON.stringify({ g1: '  ha   NOI ', g2: '1954' })), 1)
  assert.equal(sm.checkStructured('FILL_BLANK', 'A ___ B ___', ['x', 'y']), null)
  assert.ok(sm.checkStructured('FILL_BLANK', 'A ___', ['x', 'y']))
  assert.ok(sm.checkStructured('FILL_BLANK', 'không có', []))
})

test('matching: pairs, validation and scoring mirror the server', () => {
  assert.deepEqual(sm.pairOf('Việt Nam => Hà Nội'), { left: 'Việt Nam', right: 'Hà Nội' })
  assert.equal(sm.pairOf('Việt Nam =>'), null)
  assert.equal(sm.joinPair(' A ', ' 1 '), 'A => 1')
  assert.equal(sm.checkStructured('MATCHING', '', [{ left: 'A', right: '1' }, { left: 'B', right: '2' }]), null)
  assert.ok(sm.checkStructured('MATCHING', '', [{ left: 'A', right: '1' }]))
  assert.ok(sm.checkStructured('MATCHING', '', [{ left: 'A', right: '1' }, { left: 'B', right: '1' }]))
  assert.ok(sm.checkStructured('MATCHING', '', [{ left: 'A', right: '1' }, { left: 'B', right: '' }]))
  const opts = [{ id: 'a', content: 'Việt Nam => Hà Nội' }, { id: 'b', content: 'Pháp => Paris' }]
  assert.equal(sm.partsRight('MATCHING', opts, JSON.stringify({ a: 'Hà Nội', b: 'Hà Nội' })), 1)
  assert.deepEqual(sm.keyLines('MATCHING', [{ content: 'Pháp => Paris', displayOrder: 1 }, { content: 'Việt Nam => Hà Nội', displayOrder: 0 }]), ['Việt Nam → Hà Nội', 'Pháp → Paris'])
  assert.deepEqual(sm.keyLines('FILL_BLANK', [{ content: 'Hà Nội | Ha Noi', displayOrder: 0 }]), ['(1) Hà Nội / Ha Noi'])
})

const fill = { content: 'Thủ đô là ___, năm ___.', questionType: 'FILL_BLANK', difficultyLevel: 'EASY', options: [{ content: 'Hà Nội | Ha Noi', correct: true, displayOrder: 0 }, { content: '1945', correct: true, displayOrder: 1 }] }
const match = { content: 'Nối nước với thủ đô', questionType: 'MATCHING', difficultyLevel: 'MEDIUM', options: [{ content: 'Việt Nam => Hà Nội', correct: true, displayOrder: 0 }, { content: 'Pháp => Paris', correct: true, displayOrder: 1 }, { content: 'Nhật Bản => Tokyo', correct: true, displayOrder: 2 }] }

test('export: CSV re-imports, Moodle gets cloze and matching questions', () => {
  const csv = ex.toCsv([fill, match])
  assert.match(csv, /Điền vào chỗ trống,Dễ,,,,,,,,,"Hà Nội \| Ha Noi; 1945"/)
  assert.match(csv, /Nối cột,Trung bình,Việt Nam => Hà Nội,Pháp => Paris,Nhật Bản => Tokyo/)
  const xml = ex.toMoodleXml([fill, match])
  assert.match(xml, /type="cloze"/)
  assert.match(xml, /\{1:SHORTANSWER:=Hà Nội~=Ha Noi\}/)
  assert.match(xml, /type="matching"/)
  assert.match(xml, /<subquestion format="html"><text><!\[CDATA\[<p>Pháp<\/p>\]\]><\/text><answer><text>Paris<\/text><\/answer><\/subquestion>/)
  assert.equal(ex.toAiken([fill, match]).skipped, 2)
})

test('paper: blanks keyed in order, matching key follows the printed letters for every code', () => {
  const q = (o, i) => ({ questionId: 'q' + i, questionVersion: 1, position: i, points: 1, expectedAnswer: null, presentation: null, ...o })
  for (const code of ['101', '102', '103']) {
    const v = pm.makeVersion('exam-1', [q(fill, 0), q(match, 1)], code, { shuffleQuestions: false, shuffleOptions: true })
    assert.equal(v.items[0].key, '(1) Hà Nội; (2) 1945')
    const m = v.items[1]
    assert.deepEqual(m.match.lefts, ['Việt Nam', 'Pháp', 'Nhật Bản'])
    assert.deepEqual([...m.match.rights].sort(), ['Hà Nội', 'Paris', 'Tokyo'])
    const right = { 'Việt Nam': 'Hà Nội', 'Pháp': 'Paris', 'Nhật Bản': 'Tokyo' }
    m.key.split(', ').forEach((part, k) => {
      const letter = part.split('-')[1]
      assert.equal(m.match.rights[letter.charCodeAt(0) - 97], right[m.match.lefts[k]])
    })
  }
})
