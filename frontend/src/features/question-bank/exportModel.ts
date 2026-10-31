/**
 * Question export in formats other platforms read: CSV in the same columns as the EXAMINI Excel/CSV import template
 * (opens in Excel, re-importable), Moodle XML and Aiken. Pure functions so they are testable without the API client.
 */
export interface ExportQuestion {
  content: string; questionType: string; difficultyLevel: string
  options: { content: string; correct?: boolean; displayOrder: number }[]
  expectedAnswer?: string | null; explanation?: string | null
  presentation?: { passage?: { title?: string | null; content: string } | null } | null
}
import { alternatives, pairOf } from './structuredModel'
import { toQtiPackage } from './qtiModel'

export type ExportFormat = 'csv' | 'moodle' | 'aiken' | 'qti'

const TYPE_WORD: Record<string, string> = { MULTIPLE_CHOICE: 'Trắc nghiệm', MULTIPLE_RESPONSE: 'Nhiều đáp án', TRUE_FALSE_GROUP: 'Đúng/Sai nhiều ý', TRUE_FALSE: 'Đúng/Sai', SHORT_ANSWER: 'Trả lời ngắn', ESSAY: 'Tự luận', SPEAKING: 'Nói', FILL_BLANK: 'Điền vào chỗ trống', MATCHING: 'Nối cột' }
const LEVEL_WORD: Record<string, string> = { EASY: 'Dễ', MEDIUM: 'Trung bình', HARD: 'Khó' }
const LETTERS = 'ABCDEFGH'
const sorted = (q: ExportQuestion) => [...q.options].sort((a, b) => a.displayOrder - b.displayOrder)
/** Passage text goes in front of the question so the exported item stays answerable on its own. */
const fullText = (q: ExportQuestion) => {
  const p = q.presentation?.passage
  return p?.content ? `${p.title ? p.title + '\n' : ''}${p.content}\n\n${q.content}` : q.content
}

function answerOf(q: ExportQuestion): string {
  const opts = sorted(q)
  if (q.questionType === 'MULTIPLE_CHOICE' || q.questionType === 'MULTIPLE_RESPONSE') return opts.map((o, i) => (o.correct ? LETTERS[i] ?? '' : '')).filter(Boolean).join(',')
  if (q.questionType === 'TRUE_FALSE_GROUP') return opts.map(o => (o.correct ? 'Đ' : 'S')).join(', ')
  if (q.questionType === 'FILL_BLANK') return opts.map(o => o.content).join('; ')
  if (q.questionType === 'MATCHING') return ''
  if (q.questionType === 'TRUE_FALSE') { const c = opts.find(o => o.correct); return c ? (c.content.trim().toLowerCase() === 'sai' || c.content.trim().toLowerCase() === 'false' ? 'Sai' : 'Đúng') : '' }
  return q.expectedAnswer ?? ''
}

const csvCell = (v: string) => (/[",;\n\r]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v)
export function toCsv(list: ExportQuestion[]): string {
  const head = ['Câu hỏi', 'Loại', 'Độ khó', ...LETTERS.split(''), 'Đáp án', 'Giải thích']
  const rows = list.map(q => {
    const opts = ['MULTIPLE_CHOICE', 'MULTIPLE_RESPONSE', 'TRUE_FALSE_GROUP', 'MATCHING'].includes(q.questionType) ? sorted(q).slice(0, 8).map(o => o.content) : []
    return [fullText(q), TYPE_WORD[q.questionType] ?? q.questionType, LEVEL_WORD[q.difficultyLevel] ?? '',
      ...Array.from({ length: 8 }, (_, i) => opts[i] ?? ''), answerOf(q), q.explanation ?? '']
  })
  // BOM so Excel opens Vietnamese text as UTF-8.
  return '﻿' + [head, ...rows].map(r => r.map(csvCell).join(',')).join('\r\n') + '\r\n'
}

const xml = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
const html = (s: string) => `<text><![CDATA[${s.replace(/]]>/g, ']]&gt;').split('\n').map(l => `<p>${xml(l)}</p>`).join('')}]]></text>`
const plain = (s: string) => `<text>${xml(s)}</text>`

export function toMoodleXml(list: ExportQuestion[], category = 'EXAMINI'): string {
  const out = ['<?xml version="1.0" encoding="UTF-8"?>', '<quiz>',
    `  <question type="category"><category>${plain('$course$/' + category)}</category></question>`]
  list.forEach((q, n) => {
    const name = `<name>${plain(q.content.replace(/\s+/g, ' ').slice(0, 60) || `Q${n + 1}`)}</name>`
    const text = `<questiontext format="html">${html(fullText(q))}</questiontext>`
    const fb = q.explanation ? `<generalfeedback format="html">${html(q.explanation)}</generalfeedback>` : ''
    const opts = sorted(q)
    if (q.questionType === 'TRUE_FALSE_GROUP') {
      // Moodle has no statement-group type: one true/false question per statement, the stem repeated in front.
      opts.forEach((o, k) => {
        const label = String.fromCharCode(97 + k)
        out.push(`  <question type="truefalse"><name>${plain(`${q.content.replace(/\s+/g, ' ').slice(0, 50)} (${label})`)}</name><questiontext format="html">${html(`${fullText(q)}\n${label}) ${o.content}`)}</questiontext>${fb}<defaultgrade>1</defaultgrade>`,
          `    <answer fraction="${o.correct ? 100 : 0}">${plain('true')}</answer>`, `    <answer fraction="${o.correct ? 0 : 100}">${plain('false')}</answer>`, '  </question>')
      })
    } else if (q.questionType === 'FILL_BLANK') {
      // Moodle "Embedded answers (Cloze)": each gap becomes {1:SHORTANSWER:=answer~=other spelling}.
      const esc = (s: string) => s.replace(/[}#~/"\\=]/g, m => '\\' + m)
      let k = 0
      const cloze = fullText(q).replace(/_{3,}/g, () => { const alts = alternatives(opts[k++]?.content); return `{1:SHORTANSWER:${alts.map(a => '=' + esc(a)).join('~')}}` })
      out.push(`  <question type="cloze">${name}<questiontext format="html">${html(cloze)}</questiontext>${fb}</question>`)
    } else if (q.questionType === 'MATCHING') {
      out.push(`  <question type="matching">${name}${text}${fb}<defaultgrade>1</defaultgrade><shuffleanswers>true</shuffleanswers>`)
      for (const o of opts) { const p = pairOf(o.content); if (p) out.push(`    <subquestion format="html">${html(p.left)}<answer>${plain(p.right)}</answer></subquestion>`) }
      out.push('  </question>')
    } else if (q.questionType === 'MULTIPLE_CHOICE' || q.questionType === 'MULTIPLE_RESPONSE') {
      const correct = opts.filter(o => o.correct).length || 1
      const single = correct === 1
      out.push(`  <question type="multichoice">${name}${text}${fb}<defaultgrade>1</defaultgrade><single>${single}</single><shuffleanswers>true</shuffleanswers><answernumbering>ABCD</answernumbering>`)
      for (const o of opts) {
        const fraction = o.correct ? (single ? 100 : Math.round((100 / correct) * 100000) / 100000) : (single ? 0 : -100)
        out.push(`    <answer fraction="${fraction}" format="html">${html(o.content)}</answer>`)
      }
      out.push('  </question>')
    } else if (q.questionType === 'TRUE_FALSE') {
      const isTrue = answerOf(q) !== 'Sai'
      out.push(`  <question type="truefalse">${name}${text}${fb}<defaultgrade>1</defaultgrade>`,
        `    <answer fraction="${isTrue ? 100 : 0}">${plain('true')}</answer>`, `    <answer fraction="${isTrue ? 0 : 100}">${plain('false')}</answer>`, '  </question>')
    } else if (q.questionType === 'SHORT_ANSWER') {
      out.push(`  <question type="shortanswer">${name}${text}${fb}<defaultgrade>1</defaultgrade><usecase>1</usecase>`,
        `    <answer fraction="100">${plain(q.expectedAnswer ?? '')}</answer>`, '  </question>')
    } else {
      // Essay and speaking: Moodle has no speaking type, an essay with the grading guide keeps the task usable.
      const guide = q.expectedAnswer ? `<graderinfo format="html">${html(q.expectedAnswer)}</graderinfo>` : ''
      out.push(`  <question type="essay">${name}${text}${fb}${guide}<defaultgrade>1</defaultgrade><responseformat>editor</responseformat><responserequired>1</responserequired><responsefieldlines>15</responsefieldlines></question>`)
    }
  })
  out.push('</quiz>')
  return out.join('\n') + '\n'
}

/** Aiken holds single-answer multiple choice only (and true/false as two options); other questions are skipped. */
export function toAiken(list: ExportQuestion[]): { text: string; skipped: number } {
  const blocks: string[] = []
  let skipped = 0
  for (const q of list) {
    const opts = sorted(q)
    const correct = opts.filter(o => o.correct)
    const usable = (q.questionType === 'MULTIPLE_CHOICE' || q.questionType === 'TRUE_FALSE') && correct.length === 1 && opts.length >= 2 && opts.length <= 26
    if (!usable) { skipped++; continue }
    const one = (s: string) => s.replace(/\s*\n\s*/g, ' ').trim()
    blocks.push([one(fullText(q)), ...opts.map((o, i) => `${String.fromCharCode(65 + i)}. ${one(o.content)}`),
      `ANSWER: ${String.fromCharCode(65 + opts.indexOf(correct[0]))}`].join('\n'))
  }
  return { text: blocks.join('\n\n') + (blocks.length ? '\n' : ''), skipped }
}

export function exportFile(format: ExportFormat, list: ExportQuestion[]): { name: string; type: string; body: string | Uint8Array<ArrayBuffer>; skipped: number } {
  const stamp = new Date().toISOString().slice(0, 10)
  if (format === 'qti') return { name: `cau-hoi-${stamp}-qti21.zip`, type: 'application/zip', body: toQtiPackage(list), skipped: 0 }
  if (format === 'moodle') return { name: `cau-hoi-${stamp}-moodle.xml`, type: 'application/xml', body: toMoodleXml(list), skipped: 0 }
  if (format === 'aiken') { const a = toAiken(list); return { name: `cau-hoi-${stamp}-aiken.txt`, type: 'text/plain;charset=utf-8', body: a.text, skipped: a.skipped } }
  return { name: `cau-hoi-${stamp}.csv`, type: 'text/csv;charset=utf-8', body: toCsv(list), skipped: 0 }
}
