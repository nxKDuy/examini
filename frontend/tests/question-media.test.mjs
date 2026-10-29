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
const mm = await import('../src/features/question-bank/media/mediaModel.ts')
const ex = await import('../src/features/question-bank/exportModel.ts')
const pm = await import('../src/features/exams/paperModel.ts')

test('word count handles Vietnamese, punctuation and contractions; limits only flag real answers', () => {
  assert.equal(mm.countWords(''), 0)
  assert.equal(mm.countWords('  Hà Nội là thủ đô, của Việt Nam.  '), 8)
  assert.equal(mm.countWords("I don't know — well-known"), 4)
  const p = { minWords: 5, maxWords: 8 }
  assert.equal(mm.wordState(0, p), 'ok')
  assert.equal(mm.wordState(3, p), 'short')
  assert.equal(mm.wordState(9, p), 'long')
  assert.equal(mm.wordState(6, p), 'ok')
  assert.equal(mm.wordState(100, null), 'ok')
})

test('media pre-check by kind and size', () => {
  assert.equal(mm.mediaProblem({ type: 'image/png', size: 1000, name: 'a.png' }), null)
  assert.equal(mm.mediaProblem({ type: 'image/png', size: 4 * 1024 * 1024, name: 'a.png' }), 'IMAGE_SIZE')
  assert.equal(mm.mediaProblem({ type: '', size: 1000, name: 'nghe.mp3' }), null)
  assert.equal(mm.mediaProblem({ type: 'audio/mpeg', size: 21 * 1024 * 1024, name: 'a.mp3' }), 'AUDIO_SIZE')
  assert.equal(mm.mediaProblem({ type: 'application/pdf', size: 10, name: 'a.pdf' }), 'TYPE')
})

test('play counter survives in storage per attempt and media; blocked storage does not throw', () => {
  const m = new Map(); const store = { getItem: k => m.get(k) ?? null, setItem: (k, v) => m.set(k, v) }
  assert.equal(mm.playsUsed(store, 's1', 'm1'), 0)
  assert.equal(mm.recordPlay(store, 's1', 'm1'), 1)
  assert.equal(mm.recordPlay(store, 's1', 'm1'), 2)
  assert.equal(mm.playsUsed(store, 's2', 'm1'), 0)
  const broken = { getItem() { throw new Error('x') }, setItem() { throw new Error('x') } }
  assert.equal(mm.playsUsed(broken, 's', 'm'), 0)
  assert.equal(mm.recordPlay(broken, 's', 'm'), 1)
})

test('passage blocks group adjacent questions with the same key', () => {
  const P = k => ({ presentation: { passage: { key: k, content: 'x' } } })
  const qs = [{}, P('a'), P('a'), P('a'), {}, P('b')]
  const blocks = mm.passageBlocks(qs)
  assert.deepEqual(blocks.map(b => [b.passageKey, b.start, b.end]), [[null, 0, 0], ['a', 1, 3], [null, 4, 4], ['b', 5, 5]])
  assert.equal(mm.blockOf(qs, 2).start, 1)
  assert.equal(mm.blockOf(qs, 0), null)
})

test('spoken answer metadata and presentation cleaning per type', () => {
  assert.deepEqual(mm.spokenAnswer('{"audio":true,"seconds":12,"bytes":3,"type":"audio/webm"}').seconds, 12)
  assert.equal(mm.spokenAnswer('hello'), null)
  assert.equal(mm.spokenAnswer('{"audio":false}'), null)
  assert.equal(mm.cleanPresentation({}, 'MULTIPLE_CHOICE'), null)
  assert.deepEqual(mm.cleanPresentation({}, 'SPEAKING'), { maxRecordSeconds: 120 })
  // Word limits only for essays; play limit only with audio; empty passage dropped.
  assert.deepEqual(mm.cleanPresentation({ minWords: 50, maxPlays: 2, passage: { key: 'k', content: '  ' } }, 'SHORT_ANSWER'), null)
  const p = mm.cleanPresentation({ media: [{ id: 'm', kind: 'AUDIO' }], maxPlays: 2, minWords: 10, optionMedia: { 0: 'i', 1: '' } }, 'ESSAY')
  assert.deepEqual(p, { media: [{ id: 'm', kind: 'AUDIO' }], maxPlays: 2, minWords: 10 })
  assert.deepEqual(mm.cleanPresentation({ optionMedia: { 0: 'i', 1: '' } }, 'MULTIPLE_CHOICE'), { optionMedia: { 0: 'i' } })
})

const mc = { content: 'Thủ đô, "Việt Nam"?', questionType: 'MULTIPLE_CHOICE', difficultyLevel: 'EASY', explanation: 'Vì <đúng>',
  options: [{ content: 'Huế', correct: false, displayOrder: 1 }, { content: 'Hà Nội', correct: true, displayOrder: 0 }] }
const tf = { content: 'Trái Đất tròn', questionType: 'TRUE_FALSE', difficultyLevel: 'MEDIUM', options: [{ content: 'Đúng', correct: false, displayOrder: 0 }, { content: 'Sai', correct: true, displayOrder: 1 }] }
const essay = { content: 'Viết đoạn văn', questionType: 'SPEAKING', difficultyLevel: 'HARD', options: [], expectedAnswer: 'Phát âm & ý',
  presentation: { passage: { title: 'Bài đọc', content: 'Ngày xưa…' } } }

test('CSV export uses the import template columns, quotes cells and keeps the passage', () => {
  const csv = ex.toCsv([mc, tf, essay])
  assert.ok(csv.startsWith('﻿Câu hỏi,Loại,Độ khó,A,B,C,D,E,F,G,H,Đáp án,Giải thích\r\n'))
  const rows = csv.trim().split('\r\n')
  assert.equal(rows[1], '"Thủ đô, ""Việt Nam""?",Trắc nghiệm,Dễ,Hà Nội,Huế,,,,,,,A,Vì <đúng>')
  assert.ok(rows[2].startsWith('Trái Đất tròn,Đúng/Sai,Trung bình,') && rows[2].endsWith(',Sai,'))
  assert.ok(csv.includes('"Bài đọc\nNgày xưa…\n\nViết đoạn văn",Nói,Khó'))
})

test('Moodle XML escapes text and maps every type; Aiken keeps single-answer choice questions only', () => {
  const x = ex.toMoodleXml([mc, tf, essay])
  assert.ok(x.includes('<question type="multichoice">') && x.includes('<single>true</single>'))
  assert.ok(x.includes('<answer fraction="100" format="html"><text><![CDATA[<p>Hà Nội</p>]]></text></answer>'))
  assert.ok(x.includes('<answer fraction="0">') && x.includes('<question type="truefalse">'))
  assert.ok(x.includes('<question type="essay">') && x.includes('Phát âm &amp; ý'))
  assert.ok(x.includes('<name><text>Thủ đô, &quot;Việt Nam&quot;?</text></name>'))
  const a = ex.toAiken([mc, tf, essay])
  assert.equal(a.skipped, 1)
  assert.equal(a.text, 'Thủ đô, "Việt Nam"?\nA. Hà Nội\nB. Huế\nANSWER: A\n\nTrái Đất tròn\nA. Đúng\nB. Sai\nANSWER: B\n')
})

test('paper versions keep passage groups together and report their ranges', () => {
  const q = (i, key) => ({ questionId: 'q' + i, questionVersion: 1, questionType: 'ESSAY', content: 'C' + i, options: [], points: 1, expectedAnswer: null, explanation: null, position: i,
    ...(key ? { presentation: { passage: { key, content: 'p' } } } : {}) })
  const qs = [q(1), q(2, 'k'), q(3, 'k'), q(4, 'k'), q(5), q(6), q(7)]
  for (let code = 101; code < 109; code++) {
    const v = pm.makeVersion('exam', qs, String(code), { shuffleQuestions: true, shuffleOptions: true })
    const at = v.items.findIndex(it => it.question.questionId === 'q2')
    assert.deepEqual(v.items.slice(at, at + 3).map(it => it.question.questionId), ['q2', 'q3', 'q4'])
    assert.deepEqual([...pm.passageRanges(v.items).values()], [{ key: 'k', from: at + 1, to: at + 3 }])
  }
})
