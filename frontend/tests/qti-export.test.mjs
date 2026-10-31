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
const qti = await import('../src/features/question-bank/qtiModel.ts')
const ex = await import('../src/features/question-bank/exportModel.ts')
import { writeFileSync } from 'node:fs'

const o = (content, correct = false, displayOrder = 0) => ({ content, correct, displayOrder })
export const SAMPLE = [
  { content: 'Thủ đô của Việt Nam là?', questionType: 'MULTIPLE_CHOICE', difficultyLevel: 'EASY', options: [o('Huế', false, 0), o('Hà Nội', true, 1), o('Đà Nẵng', false, 2)], explanation: 'Hà Nội là thủ đô từ 1976.' },
  { content: 'Số nguyên tố nhỏ hơn 6?', questionType: 'MULTIPLE_RESPONSE', difficultyLevel: 'MEDIUM', options: [o('2', true, 0), o('4', false, 1), o('5', true, 2)] },
  { content: 'Nước sôi ở 100°C (áp suất 1 atm).', questionType: 'TRUE_FALSE', difficultyLevel: 'EASY', options: [o('Đúng', true, 0), o('Sai', false, 1)] },
  { content: 'Xét hàm số y = x² < 3 & "đúng":', questionType: 'TRUE_FALSE_GROUP', difficultyLevel: 'HARD', options: [o('Đồng biến trên (0; +∞)', true, 0), o('Có cực đại', false, 1), o('Đi qua gốc toạ độ', true, 2), o('Là hàm lẻ', false, 3)],
    presentation: { passage: { title: 'Đề chung', content: 'Cho hàm số y = x².\n\nĐọc kỹ trước khi trả lời.' } } },
  { content: 'Thủ đô của Pháp là ___, của Nhật là ___.', questionType: 'FILL_BLANK', difficultyLevel: 'EASY', options: [o('Paris | Pa-ri', false, 0), o('Tokyo | Tô-ky-ô', false, 1)] },
  { content: 'Nối nước với thủ đô', questionType: 'MATCHING', difficultyLevel: 'MEDIUM', options: [o('Việt Nam => Hà Nội', false, 0), o('Pháp => Paris', false, 1), o('Nhật Bản => Tokyo', false, 2)] },
  { content: 'H₂O là công thức của chất gì?', questionType: 'SHORT_ANSWER', difficultyLevel: 'EASY', options: [], expectedAnswer: 'nước' },
  { content: 'Trình bày vai trò của quang hợp.', questionType: 'ESSAY', difficultyLevel: 'HARD', options: [], expectedAnswer: 'Nêu tạo chất hữu cơ, oxy, điều hoà khí hậu.' },
  { content: 'Introduce yourself in one minute.', questionType: 'SPEAKING', difficultyLevel: 'MEDIUM', options: [] },
]

/** Reads the STORE zip back without any library: checks signatures, CRCs and returns name -> text. */
function unzip(bytes) {
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const end = bytes.length - 22
  assert.equal(v.getUint32(end, true), 0x06054b50)
  const count = v.getUint16(end + 10, true), cd = v.getUint32(end + 16, true)
  const out = {}
  let p = cd
  for (let i = 0; i < count; i++) {
    assert.equal(v.getUint32(p, true), 0x02014b50)
    const crc = v.getUint32(p + 16, true), size = v.getUint32(p + 24, true), nlen = v.getUint16(p + 28, true), off = v.getUint32(p + 42, true)
    const name = new TextDecoder().decode(bytes.subarray(p + 46, p + 46 + nlen))
    assert.equal(v.getUint32(off, true), 0x04034b50)
    const data = bytes.subarray(off + 30 + v.getUint16(off + 26, true), off + 30 + v.getUint16(off + 26, true) + size)
    assert.equal(qti.crc32(data), crc, name)
    out[name] = new TextDecoder().decode(data)
    p += 46 + nlen
  }
  return out
}

test('crc32 matches the standard check value', () => {
  assert.equal(qti.crc32(new TextEncoder().encode('123456789')), 0xcbf43926)
})

test('QTI package: manifest plus one item per question, every type', () => {
  const zip = qti.toQtiPackage(SAMPLE)
  if (process.env.QTI_OUT) writeFileSync(process.env.QTI_OUT, zip)
  const files = unzip(zip)
  assert.deepEqual(Object.keys(files).sort(), ['imsmanifest.xml', ...SAMPLE.map((_, i) => `items/ITEM_000${i + 1}.xml`)].sort())
  assert.equal((files['imsmanifest.xml'].match(/type="imsqti_item_xmlv2p1"/g) ?? []).length, SAMPLE.length)
  const item = n => files[`items/ITEM_000${n}.xml`]
  assert.match(item(1), /<choiceInteraction responseIdentifier="RESPONSE" shuffle="true" maxChoices="1">/)
  assert.match(item(1), /<correctResponse><value>C2<\/value><\/correctResponse>/)
  assert.match(item(1), /<modalFeedback outcomeIdentifier="FEEDBACK" identifier="EXPLAIN"/)
  assert.match(item(2), /cardinality="multiple".*<value>C1<\/value><value>C3<\/value>/)
  assert.match(item(3), /<value>TRUE<\/value>/)
  assert.match(item(4), /<value>S2 FALSE<\/value>/)
  assert.match(item(4), /x² &lt; 3 &amp; &quot;đúng&quot;/, 'text is escaped')
  assert.match(item(4), /<div class="passage"><h3>Đề chung<\/h3><p>Cho hàm số y = x².<\/p><p>Đọc kỹ/)
  assert.match(item(4), /normalMaximum="4"/)
  assert.equal((item(5).match(/<textEntryInteraction /g) ?? []).length, 2)
  assert.match(item(5), /<mapEntry mapKey="Tô-ky-ô" mappedValue="1" caseSensitive="false"\/>/)
  assert.match(item(5), /<sum><mapResponse identifier="R1"\/><mapResponse identifier="R2"\/><\/sum>/)
  // Right column alphabetical: Hà Nội (R1), Paris (R2), Tokyo (R3).
  assert.match(item(6), /<value>L1 R1<\/value><value>L2 R2<\/value><value>L3 R3<\/value>/)
  assert.match(item(7), /<mapEntry mapKey="nước"/)
  assert.match(item(8), /<rubricBlock view="scorer">/)
  assert.doesNotMatch(item(8), /<responseProcessing>/, 'essays are marked by a teacher')
  assert.match(item(9), /Câu nói/)
})

test('export dialog offers QTI as a zip file', () => {
  const f = ex.exportFile('qti', SAMPLE.slice(0, 2))
  assert.match(f.name, /-qti21\.zip$/)
  assert.equal(f.type, 'application/zip')
  assert.ok(f.body instanceof Uint8Array)
})
