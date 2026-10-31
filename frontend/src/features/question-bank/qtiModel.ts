/**
 * IMS QTI 2.1 export: a content package (.zip) with imsmanifest.xml and one assessmentItem per question, readable by
 * TAO, Canvas (QTI import), Inspera, ONYX, Blackboard Ultra and other QTI 2.1 tools. Every EXAMINI question type maps to a
 * standard interaction; auto-graded types carry their key and response processing, essays are left for manual marking.
 * Explanations become modal feedback shown after answering. Pure functions (no DOM), so node tests can run them.
 */
import type { ExportQuestion } from './exportModel'
import { alternatives, pairOf } from './structuredModel'

export const QTI_NS = 'http://www.imsglobal.org/xsd/imsqti_v2p1'
const SCHEMA = 'http://www.imsglobal.org/xsd/imsqti_v2p1 http://www.imsglobal.org/xsd/qti/qtiv2p1/imsqti_v2p1p2.xsd'
const LEVEL: Record<string, string> = { EASY: 'Dễ', MEDIUM: 'Trung bình', HARD: 'Khó' }

const x = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
  // Characters XML 1.0 forbids (control codes pasted from Word) would make the whole file unreadable.
  .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F￾￿]/g, '')
/** Text as block content: one paragraph per blank-line-separated block, single newlines as line breaks. */
const paras = (s: string) => s.split(/\n{2,}/).map(p => p.trim()).filter(Boolean).map(p => `<p>${x(p).replace(/\n/g, '<br/>')}</p>`).join('')
const inline = (s: string) => x(s.trim()).replace(/\n/g, '<br/>')
const sorted = (q: ExportQuestion) => [...q.options].sort((a, b) => a.displayOrder - b.displayOrder)

interface Built { declarations: string; body: string; processing: string; maxScore: number; interaction: string }

function choice(q: ExportQuestion, multiple: boolean): Built {
  const opts = sorted(q)
  const right = opts.map((o, i) => (o.correct ? `C${i + 1}` : '')).filter(Boolean)
  const card = multiple ? 'multiple' : 'single'
  return {
    interaction: 'choiceInteraction',
    declarations: `<responseDeclaration identifier="RESPONSE" cardinality="${card}" baseType="identifier"><correctResponse>${right.map(v => `<value>${v}</value>`).join('')}</correctResponse></responseDeclaration>`,
    body: `<choiceInteraction responseIdentifier="RESPONSE" shuffle="true" maxChoices="${multiple ? 0 : 1}">${opts.map((o, i) => `<simpleChoice identifier="C${i + 1}">${inline(o.content)}</simpleChoice>`).join('')}</choiceInteraction>`,
    processing: matchCorrect(), maxScore: 1,
  }
}

function trueFalse(q: ExportQuestion): Built {
  const c = sorted(q).find(o => o.correct)
  const isFalse = !!c && ['sai', 'false', 's', 'f'].includes(c.content.trim().toLowerCase())
  return {
    interaction: 'choiceInteraction',
    declarations: `<responseDeclaration identifier="RESPONSE" cardinality="single" baseType="identifier"><correctResponse><value>${isFalse ? 'FALSE' : 'TRUE'}</value></correctResponse></responseDeclaration>`,
    body: '<choiceInteraction responseIdentifier="RESPONSE" shuffle="false" maxChoices="1"><simpleChoice identifier="TRUE">Đúng</simpleChoice><simpleChoice identifier="FALSE">Sai</simpleChoice></choiceInteraction>',
    processing: matchCorrect(), maxScore: 1,
  }
}

/** Statements × {Đúng, Sai}; one point per statement judged right (the THPT 2025 group is scored per statement). */
function statementGroup(q: ExportQuestion): Built {
  const opts = sorted(q)
  const pairs = opts.map((o, i) => `S${i + 1} ${o.correct ? 'TRUE' : 'FALSE'}`)
  return {
    interaction: 'matchInteraction',
    declarations: `<responseDeclaration identifier="RESPONSE" cardinality="multiple" baseType="directedPair"><correctResponse>${pairs.map(p => `<value>${p}</value>`).join('')}</correctResponse>`
      + `<mapping defaultValue="0">${pairs.map(p => `<mapEntry mapKey="${p}" mappedValue="1"/>`).join('')}</mapping></responseDeclaration>`,
    body: `<matchInteraction responseIdentifier="RESPONSE" shuffle="false" maxAssociations="${opts.length}"><simpleMatchSet>`
      + opts.map((o, i) => `<simpleAssociableChoice identifier="S${i + 1}" matchMax="1">${String.fromCharCode(97 + i)}) ${inline(o.content)}</simpleAssociableChoice>`).join('')
      + '</simpleMatchSet><simpleMatchSet><simpleAssociableChoice identifier="TRUE" matchMax="0">Đúng</simpleAssociableChoice><simpleAssociableChoice identifier="FALSE" matchMax="0">Sai</simpleAssociableChoice></simpleMatchSet></matchInteraction>',
    processing: mapped(['RESPONSE']), maxScore: opts.length,
  }
}

function matching(q: ExportQuestion): Built {
  const pairs = sorted(q).map(o => pairOf(o.content)).filter((p): p is { left: string; right: string } => !!p)
  // Right column alphabetical, as students see it in EXAMINI (the stored order would reveal the key).
  const rights = [...new Set(pairs.map(p => p.right))].sort((a, b) => a.localeCompare(b, 'vi'))
  const key = pairs.map((p, i) => `L${i + 1} R${rights.indexOf(p.right) + 1}`)
  return {
    interaction: 'matchInteraction',
    declarations: `<responseDeclaration identifier="RESPONSE" cardinality="multiple" baseType="directedPair"><correctResponse>${key.map(p => `<value>${p}</value>`).join('')}</correctResponse>`
      + `<mapping defaultValue="0">${key.map(p => `<mapEntry mapKey="${p}" mappedValue="1"/>`).join('')}</mapping></responseDeclaration>`,
    body: `<matchInteraction responseIdentifier="RESPONSE" shuffle="true" maxAssociations="${pairs.length}"><simpleMatchSet>`
      + pairs.map((p, i) => `<simpleAssociableChoice identifier="L${i + 1}" matchMax="1">${inline(p.left)}</simpleAssociableChoice>`).join('')
      + '</simpleMatchSet><simpleMatchSet>'
      + rights.map((r, i) => `<simpleAssociableChoice identifier="R${i + 1}" matchMax="1">${inline(r)}</simpleAssociableChoice>`).join('')
      + '</simpleMatchSet></matchInteraction>',
    processing: mapped(['RESPONSE']), maxScore: pairs.length,
  }
}

/** One text entry per gap, every accepted spelling mapped to 1 point, case-insensitive like EXAMINI's grader. */
function fillBlank(q: ExportQuestion, stem: string): Built {
  const opts = sorted(q)
  const pieces = stem.split(/_{3,}/)
  const ids = pieces.slice(1).map((_, i) => `R${i + 1}`)
  const declarations = ids.map((id, i) => {
    const alts = alternatives(opts[i]?.content)
    return `<responseDeclaration identifier="${id}" cardinality="single" baseType="string">`
      + (alts[0] ? `<correctResponse><value>${x(alts[0])}</value></correctResponse>` : '')
      + `<mapping defaultValue="0">${alts.map(a => `<mapEntry mapKey="${x(a)}" mappedValue="1" caseSensitive="false"/>`).join('')}</mapping></responseDeclaration>`
  }).join('')
  let body = ''
  pieces.forEach((p, i) => { body += x(p).replace(/\n/g, '<br/>'); if (i < ids.length) body += `<textEntryInteraction responseIdentifier="${ids[i]}" expectedLength="${Math.max(8, ...alternatives(opts[i]?.content).map(a => a.length))}"/>` })
  return { interaction: 'textEntryInteraction', declarations, body: `<p>${body}</p>`, processing: mapped(ids), maxScore: ids.length }
}

function shortAnswer(q: ExportQuestion): Built {
  const a = (q.expectedAnswer ?? '').trim()
  return {
    interaction: 'textEntryInteraction',
    declarations: `<responseDeclaration identifier="RESPONSE" cardinality="single" baseType="string">${a ? `<correctResponse><value>${x(a)}</value></correctResponse><mapping defaultValue="0"><mapEntry mapKey="${x(a)}" mappedValue="1" caseSensitive="false"/></mapping>` : ''}</responseDeclaration>`,
    body: `<p><textEntryInteraction responseIdentifier="RESPONSE" expectedLength="${Math.max(12, a.length)}"/></p>`,
    processing: a ? mapped(['RESPONSE']) : '', maxScore: 1,
  }
}

/** Essay and speaking: free response, marked by a teacher (the guide goes in a rubric block hidden from candidates). */
function essay(q: ExportQuestion, speaking: boolean): Built {
  const guide = (q.expectedAnswer ?? '').trim()
  return {
    interaction: 'extendedTextInteraction',
    declarations: '<responseDeclaration identifier="RESPONSE" cardinality="single" baseType="string"/>',
    body: (speaking ? '<p><em>Câu nói: thí sinh ghi âm câu trả lời (trên nền tảng không hỗ trợ ghi âm, trả lời bằng văn bản).</em></p>' : '')
      + (guide ? `<rubricBlock view="scorer">${paras(guide)}</rubricBlock>` : '')
      + `<extendedTextInteraction responseIdentifier="RESPONSE" expectedLines="12"/>`,
    processing: '', maxScore: 1,
  }
}

const score = (v: string) => `<setOutcomeValue identifier="SCORE">${v}</setOutcomeValue>`
function matchCorrect() {
  return `<responseCondition><responseIf><match><variable identifier="RESPONSE"/><correct identifier="RESPONSE"/></match>${score('<baseValue baseType="float">1</baseValue>')}</responseIf>`
    + `<responseElse>${score('<baseValue baseType="float">0</baseValue>')}</responseElse></responseCondition>`
}
function mapped(ids: string[]) {
  const parts = ids.map(id => `<mapResponse identifier="${id}"/>`).join('')
  return score(ids.length === 1 ? parts : `<sum>${parts}</sum>`)
}

export function qtiItem(q: ExportQuestion, identifier: string): string {
  const passage = q.presentation?.passage
  const stem = q.content ?? ''
  let b: Built
  switch (q.questionType) {
    case 'MULTIPLE_CHOICE': b = choice(q, false); break
    case 'MULTIPLE_RESPONSE': b = choice(q, true); break
    case 'TRUE_FALSE': b = trueFalse(q); break
    case 'TRUE_FALSE_GROUP': b = statementGroup(q); break
    case 'MATCHING': b = matching(q); break
    case 'FILL_BLANK': b = fillBlank(q, stem); break
    case 'SHORT_ANSWER': b = shortAnswer(q); break
    default: b = essay(q, q.questionType === 'SPEAKING')
  }
  const explain = (q.explanation ?? '').trim()
  const feedback = explain ? `<setOutcomeValue identifier="FEEDBACK"><baseValue baseType="identifier">EXPLAIN</baseValue></setOutcomeValue>` : ''
  const processing = b.processing || feedback ? `<responseProcessing>${b.processing}${feedback}</responseProcessing>` : ''
  const title = stem.replace(/_{3,}/g, '…').replace(/\s+/g, ' ').trim().slice(0, 80) || identifier
  const intro = (passage?.content ? `<div class="passage">${passage.title ? `<h3>${x(passage.title)}</h3>` : ''}${paras(passage.content)}</div>` : '')
    + (q.questionType === 'FILL_BLANK' ? '' : `<div class="stem">${paras(stem)}</div>`)
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    `<assessmentItem xmlns="${QTI_NS}" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="${SCHEMA}"`,
    `  identifier="${identifier}" title="${x(title)}" adaptive="false" timeDependent="false" xml:lang="vi">`,
    b.declarations,
    `<outcomeDeclaration identifier="SCORE" cardinality="single" baseType="float" normalMaximum="${b.maxScore}"><defaultValue><value>0</value></defaultValue></outcomeDeclaration>`,
    `<outcomeDeclaration identifier="MAXSCORE" cardinality="single" baseType="float"><defaultValue><value>${b.maxScore}</value></defaultValue></outcomeDeclaration>`,
    explain ? '<outcomeDeclaration identifier="FEEDBACK" cardinality="single" baseType="identifier"/>' : '',
    `<itemBody>${intro}${b.body}</itemBody>`,
    processing,
    explain ? `<modalFeedback outcomeIdentifier="FEEDBACK" identifier="EXPLAIN" showHide="show" title="Giải thích">${paras(explain)}</modalFeedback>` : '',
    '</assessmentItem>',
  ].filter(Boolean).join('\n') + '\n'
}

export function qtiManifest(items: { identifier: string; href: string; title: string; type: string; level?: string }[], title: string): string {
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<manifest xmlns="http://www.imsglobal.org/xsd/imscp_v1p1" xmlns:imsmd="http://ltsc.ieee.org/xsd/LOM" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"',
    '  xsi:schemaLocation="http://www.imsglobal.org/xsd/imscp_v1p1 http://www.imsglobal.org/xsd/qti/qtiv2p1/qtiv2p1_imscpv1p2_v1p0.xsd" identifier="EXAMINI_EXPORT">',
    `  <metadata><schema>QTIv2.1 Package</schema><schemaversion>1.0.0</schemaversion><imsmd:lom><imsmd:general><imsmd:title><imsmd:string language="vi">${x(title)}</imsmd:string></imsmd:title></imsmd:general></imsmd:lom></metadata>`,
    '  <organizations/>',
    '  <resources>',
    ...items.map(i => `    <resource identifier="RES_${i.identifier}" type="imsqti_item_xmlv2p1" href="${i.href}"><metadata><imsmd:lom><imsmd:general><imsmd:title><imsmd:string language="vi">${x(i.title)}</imsmd:string></imsmd:title><imsmd:keyword><imsmd:string language="vi">${x(i.type)}</imsmd:string></imsmd:keyword>${i.level ? `<imsmd:keyword><imsmd:string language="vi">${x(i.level)}</imsmd:string></imsmd:keyword>` : ''}</imsmd:general></imsmd:lom></metadata><file href="${i.href}"/></resource>`),
    '  </resources>',
    '</manifest>',
  ].join('\n') + '\n'
}

export function toQtiPackage(list: ExportQuestion[], title = 'Câu hỏi EXAMINI'): Uint8Array<ArrayBuffer> {
  const enc = new TextEncoder()
  const files: ZipEntry[] = []
  const items = list.map((q, n) => {
    const identifier = `ITEM_${String(n + 1).padStart(4, '0')}`
    const href = `items/${identifier}.xml`
    files.push({ name: href, data: enc.encode(qtiItem(q, identifier)) })
    return { identifier, href, title: q.content.replace(/_{3,}/g, '…').replace(/\s+/g, ' ').trim().slice(0, 80) || identifier, type: q.questionType, level: LEVEL[q.difficultyLevel] }
  })
  files.unshift({ name: 'imsmanifest.xml', data: enc.encode(qtiManifest(items, title)) })
  return zipStore(files)
}

// ---- Minimal ZIP writer (STORE, UTF-8 names): enough for a content package, no dependency. ----
export interface ZipEntry { name: string; data: Uint8Array }
const CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0 } return t })()
export function crc32(d: Uint8Array): number { let c = 0xffffffff; for (let i = 0; i < d.length; i++) c = CRC[(c ^ d[i]) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0 }

export function zipStore(entries: ZipEntry[], when = new Date()): Uint8Array<ArrayBuffer> {
  const enc = new TextEncoder()
  const time = (when.getHours() << 11) | (when.getMinutes() << 5) | (when.getSeconds() >> 1)
  const date = ((Math.max(1980, when.getFullYear()) - 1980) << 9) | ((when.getMonth() + 1) << 5) | when.getDate()
  const names = entries.map(e => enc.encode(e.name))
  const crcs = entries.map(e => crc32(e.data))
  const localSize = entries.reduce((s, e, i) => s + 30 + names[i].length + e.data.length, 0)
  const centralSize = entries.reduce((s, _e, i) => s + 46 + names[i].length, 0)
  const out = new Uint8Array(new ArrayBuffer(localSize + centralSize + 22))
  const v = new DataView(out.buffer)
  let p = 0
  const offsets: number[] = []
  entries.forEach((e, i) => {
    offsets.push(p)
    v.setUint32(p, 0x04034b50, true); v.setUint16(p + 4, 20, true); v.setUint16(p + 6, 0x0800, true); v.setUint16(p + 8, 0, true)
    v.setUint16(p + 10, time, true); v.setUint16(p + 12, date, true); v.setUint32(p + 14, crcs[i], true)
    v.setUint32(p + 18, e.data.length, true); v.setUint32(p + 22, e.data.length, true); v.setUint16(p + 26, names[i].length, true); v.setUint16(p + 28, 0, true)
    out.set(names[i], p + 30); out.set(e.data, p + 30 + names[i].length); p += 30 + names[i].length + e.data.length
  })
  const central = p
  entries.forEach((e, i) => {
    v.setUint32(p, 0x02014b50, true); v.setUint16(p + 4, 20, true); v.setUint16(p + 6, 20, true); v.setUint16(p + 8, 0x0800, true); v.setUint16(p + 10, 0, true)
    v.setUint16(p + 12, time, true); v.setUint16(p + 14, date, true); v.setUint32(p + 16, crcs[i], true)
    v.setUint32(p + 20, e.data.length, true); v.setUint32(p + 24, e.data.length, true); v.setUint16(p + 28, names[i].length, true)
    // extra, comment, disk, internal attrs = 0; external attrs = 0; local header offset
    v.setUint32(p + 42, offsets[i], true)
    out.set(names[i], p + 46); p += 46 + names[i].length
  })
  v.setUint32(p, 0x06054b50, true); v.setUint16(p + 8, entries.length, true); v.setUint16(p + 10, entries.length, true)
  v.setUint32(p + 12, centralSize, true); v.setUint32(p + 16, central, true)
  return out
}
