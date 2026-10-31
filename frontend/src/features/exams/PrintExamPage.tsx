import { Fragment, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, Printer } from 'lucide-react'
import { examApi } from './examApi'
import { LETTERS, compactOptions, makeVersions, passageRanges, type PaperItem, type PaperVersion } from './paperModel'
import { MediaImage } from '../question-bank/media/QuestionMedia'
import { ErrorState, LoadingState, Notice } from '../../components/ui'
import { formatScore } from '../../lib/format'
import { t, useT } from '../../lib/i18n'
import './print.css'

interface Header { school: string; title: string; subject: string }

/**
 * Paper exam: N exam codes (mã đề) mixed from the published snapshot, the usual Vietnamese header, and the answer key
 * for every code. Printing uses the browser (Print → Save as PDF on phones too). The paper itself stays Vietnamese.
 */
export default function PrintExamPage() {
  useT()
  const { id } = useParams<{ id: string }>()
  const exam = useQuery({ queryKey: ['exam', id], queryFn: () => examApi.get(id!), enabled: !!id })
  const paper = useQuery({ queryKey: ['exam-paper', id], queryFn: () => examApi.paper(id!), enabled: !!id && exam.isSuccess, retry: false, staleTime: Infinity })
  const [codes, setCodes] = useState(4)
  const [firstCode, setFirstCode] = useState(101)
  const [shuffleQuestions, setShuffleQuestions] = useState(true)
  const [shuffleOptions, setShuffleOptions] = useState(true)
  const [withKey, setWithKey] = useState(true)
  const [showPoints, setShowPoints] = useState(true)
  const [sheets, setSheets] = useState(false)
  const [header, setHeader] = useState<Header | null>(null)
  const h: Header = header ?? { school: 'TRƯỜNG ……………………', title: (exam.data?.title ?? '').toUpperCase(), subject: '' }

  const versions = useMemo(() => paper.data && id ? makeVersions(id, paper.data, { codes, firstCode, shuffleQuestions, shuffleOptions }) : [],
    [paper.data, id, codes, firstCode, shuffleQuestions, shuffleOptions])
  const total = paper.data?.reduce((s, q) => s + Number(q.points), 0) ?? 0

  if (exam.isLoading || paper.isLoading) return <div className="print-screen"><LoadingState rows={4} /></div>
  if (exam.isError) return <div className="print-screen"><ErrorState error={exam.error} /></div>

  return (
    <div className="print-root">
      <aside className="print-controls stack" aria-label={t('Thiết lập bản in')}>
        <Link to={`/exams/${id}`} className="btn btn-ghost btn-sm" style={{ alignSelf: 'flex-start', justifySelf: 'start' }}><ArrowLeft size={14} aria-hidden /> {t('Quay lại đề thi')}</Link>
        <h1 style={{ fontSize: '1.4rem' }}>{t('In đề & trộn mã đề')}</h1>
        {paper.isError ? <ErrorState error={paper.error} title={t('Không lấy được nội dung đề')} />
          : <>
            <p className="small muted">{t('{0} câu · {1} điểm. Mỗi mã đề đảo thứ tự câu và đáp án theo một khóa cố định: in lại cùng mã đề luôn ra đúng đề và đáp án cũ.', { 0: paper.data?.length ?? 0, 1: formatScore(total) })}</p>
            <div className="print-grid">
              <label className="field"><span className="field-label">{t('Số mã đề')}</span>
                <input className="input" type="number" min={1} max={24} value={codes} onChange={e => setCodes(Math.max(1, Math.min(24, Number(e.target.value) || 1)))} /></label>
              <label className="field"><span className="field-label">{t('Mã đề đầu tiên')}</span>
                <input className="input" type="number" min={1} max={9999} value={firstCode} onChange={e => setFirstCode(Math.max(1, Math.min(9999, Number(e.target.value) || 101)))} /></label>
            </div>
            <label className="check small"><input type="checkbox" checked={shuffleQuestions} onChange={e => setShuffleQuestions(e.target.checked)} /> <span>{t('Đảo thứ tự câu hỏi')}</span></label>
            <label className="check small"><input type="checkbox" checked={shuffleOptions} onChange={e => setShuffleOptions(e.target.checked)} /> <span>{t('Đảo thứ tự đáp án trắc nghiệm')}</span></label>
            <label className="check small"><input type="checkbox" checked={showPoints} onChange={e => setShowPoints(e.target.checked)} /> <span>{t('Ghi điểm từng câu')}</span></label>
            <label className="check small"><input type="checkbox" checked={sheets} onChange={e => setSheets(e.target.checked)} /> <span>{t('In kèm phiếu trả lời trắc nghiệm cho mỗi mã đề')}</span></label>
            <label className="check small"><input type="checkbox" checked={withKey} onChange={e => setWithKey(e.target.checked)} /> <span>{t('In kèm bảng đáp án (trang cuối)')}</span></label>
            <fieldset className="stack-sm" style={{ border: 0, padding: 0, margin: 0 }}>
              <legend className="field-label">{t('Tiêu đề trên đề')}</legend>
              <input className="input" aria-label={t('Tên trường / đơn vị')} value={h.school} onChange={e => setHeader({ ...h, school: e.target.value })} />
              <input className="input" aria-label={t('Tên kỳ thi')} value={h.title} onChange={e => setHeader({ ...h, title: e.target.value })} />
              <input className="input" aria-label={t('Môn / lớp')} placeholder={t('Môn: … – Lớp: …')} value={h.subject} onChange={e => setHeader({ ...h, subject: e.target.value })} />
            </fieldset>
            <button type="button" className="btn btn-primary" disabled={!versions.length} onClick={() => window.print()}><Printer size={16} aria-hidden /> {t('In / Lưu PDF')}</button>
            <Notice tone="info">{t('Bản in chứa đáp án nên chỉ chủ đề thi và người quản lý mở được; mỗi lần mở đều được ghi vào nhật ký đề thi.')}</Notice>
          </>}
      </aside>

      <main className="print-preview" aria-label={t('Bản xem trước khi in')}>
        {versions.map(v => <Fragment key={v.code}>
          <Sheet v={v} h={h} duration={exam.data?.durationMinutes ?? 0} showPoints={showPoints} />
          {sheets && <AnswerSheet v={v} h={h} />}
        </Fragment>)}
        {withKey && versions.length > 0 && <AnswerKey versions={versions} title={h.title} />}
      </main>
    </div>
  )
}

const pts = (n: number) => `${formatScore(Number(n)).replace('.', ',')} điểm`

function Sheet({ v, h, duration, showPoints }: { v: PaperVersion; h: Header; duration: number; showPoints: boolean }) {
  const ranges = passageRanges(v.items)
  return (
    <article className="paper-sheet">
      <table className="paper-head"><tbody><tr>
        <td><strong>{h.school}</strong><div className="paper-rule" /></td>
        <td><strong>{h.title}</strong>{h.subject && <div>{h.subject}</div>}<div><em>Thời gian làm bài: {duration} phút, không kể thời gian phát đề</em></div></td>
      </tr></tbody></table>
      <div className="paper-candidate">
        <span>Họ và tên thí sinh: ……………………………………………</span><span>Số báo danh: ……………</span>
        <span className="paper-code">Mã đề {v.code}</span>
      </div>
      <ol className="paper-questions">
        {v.items.map(item => {
          const p = item.question.presentation
          const range = ranges.get(item.number)
          const images = (p?.media ?? []).filter(m => m.kind === 'IMAGE')
          const listening = (p?.media ?? []).some(m => m.kind === 'AUDIO')
          const optionImage = (o: { displayOrder?: number }) => o.displayOrder === undefined ? undefined : p?.optionMedia?.[String(o.displayOrder)]
          return <Fragment key={item.question.questionId}>
          {range && p?.passage && <li className="paper-passage">
            <p><strong><em>{range.from === range.to ? `Đọc đoạn sau và trả lời câu ${range.from}.` : `Đọc đoạn sau và trả lời các câu từ ${range.from} đến ${range.to}.`}</em></strong></p>
            {p.passage.title && <p className="paper-passage-title"><strong>{p.passage.title}</strong></p>}
            {(p.passage.media ?? []).filter(m => m.kind === 'IMAGE').map(m => <MediaImage key={m.id} id={m.id} alt={m.name ?? 'Hình'} maxHeight={260} />)}
            {(p.passage.media ?? []).some(m => m.kind === 'AUDIO') && <p><em>(Phần nghe: giáo viên phát đoạn âm thanh{p.maxPlays ? ` ${p.maxPlays} lần` : ''}.)</em></p>}
            <p className="prewrap">{p.passage.content}</p>
          </li>}
          <li className="paper-q">
          <p><strong>Câu {item.number}.</strong>{showPoints && <em> ({pts(item.question.points)})</em>} <span className="prewrap">{item.question.questionType === 'FILL_BLANK' ? numberedGaps(item.question.content) : item.question.content}</span></p>
          {listening && <p><em>(Câu nghe: giáo viên phát âm thanh{p?.maxPlays ? ` ${p.maxPlays} lần` : ''}.)</em></p>}
          {images.map(m => <MediaImage key={m.id} id={m.id} alt={m.name ?? 'Hình'} maxHeight={260} />)}
          {item.question.questionType === 'MULTIPLE_CHOICE' && <div className={compactOptions(item.options) && !item.options.some(optionImage) ? 'paper-options paper-options-row' : 'paper-options'}>
            {item.options.map(o => { const img = optionImage(o); return <span key={o.letter}><strong>{o.letter}.</strong> {o.content}{img && <MediaImage id={img} alt={`Phương án ${o.letter}`} maxHeight={120} />}</span> })}</div>}
          {item.question.questionType === 'MULTIPLE_RESPONSE' && <>
            <p><em>(Chọn tất cả các đáp án đúng.)</em></p>
            <div className={compactOptions(item.options) && !item.options.some(optionImage) ? 'paper-options paper-options-row' : 'paper-options'}>
              {item.options.map(o => { const img = optionImage(o); return <span key={o.letter}><strong>{o.letter}.</strong> {o.content}{img && <MediaImage id={img} alt={`Phương án ${o.letter}`} maxHeight={120} />}</span> })}</div></>}
          {item.question.questionType === 'TRUE_FALSE_GROUP' && <div className="paper-statements">
            {item.options.map(o => <div key={o.letter} className="paper-statement"><span><strong>{o.letter})</strong> {o.content}</span><span className="paper-ds">Đ ☐ &nbsp; S ☐</span></div>)}</div>}
          {item.question.questionType === 'TRUE_FALSE' && <div className="paper-options paper-options-row">{item.options.map(o => <span key={o.letter}>☐ {o.content}</span>)}</div>}
          {item.match && <><p><em>(Nối mỗi ý ở cột A với một ý ở cột B.)</em></p>
            <table className="paper-match"><thead><tr><th>Cột A</th><th>Cột B</th></tr></thead>
              <tbody>{item.match.lefts.map((l, k) => <tr key={k}><td><strong>{k + 1}.</strong> {l}</td><td>{item.match!.rights[k] !== undefined && <><strong>{LETTERS[k].toLowerCase()}.</strong> {item.match!.rights[k]}</>}</td></tr>)}</tbody></table>
            <p>Trả lời: {item.match.lefts.map((_, k) => `${k + 1}-…`).join('  ')}</p></>}
          {item.question.questionType === 'SHORT_ANSWER' && <div className="paper-lines"><span>Trả lời: ……………………………………………………………………</span></div>}
          {item.question.questionType === 'ESSAY' && <>
            {(p?.minWords || p?.maxWords) && <p><em>(Yêu cầu: {p.minWords && p.maxWords ? `${p.minWords}–${p.maxWords} từ` : p.minWords ? `ít nhất ${p.minWords} từ` : `tối đa ${p.maxWords} từ`}.)</em></p>}
            <div className="paper-lines">{Array.from({ length: 6 }, (_, i) => <span key={i}>………………………………………………………………………………………………</span>)}</div></>}
          {item.question.questionType === 'SPEAKING' && <p><em>(Phần nói: thí sinh trả lời trực tiếp với giáo viên, khoảng {Math.round((p?.maxRecordSeconds ?? 120) / 60 * 10) / 10} phút.)</em></p>}
        </li></Fragment>})}
      </ol>
      <p className="paper-end">— HẾT —</p>
    </article>
  )
}

/** "___" gaps printed as numbered dotted lines: (1)………… */
function numberedGaps(content: string) {
  let n = 0
  return content.replace(/_{3,}/g, () => `(${++n})…………`)
}

/** Answer key per code, 20 questions per row block; long short-answer keys are listed under the grid. */
function AnswerKey({ versions, title }: { versions: PaperVersion[]; title: string }) {
  const n = versions[0].items.length
  const blocks = Array.from({ length: Math.ceil(n / 20) }, (_, b) => Array.from({ length: Math.min(20, n - b * 20) }, (_, i) => b * 20 + i))
  const isLong = (i: PaperItem) => (i.question.questionType === 'SHORT_ANSWER' && i.key.length > 6) || i.question.questionType === 'FILL_BLANK' || i.question.questionType === 'MATCHING'
  const long = versions.flatMap(v => v.items.filter(isLong).map(i => ({ code: v.code, item: i })))
  return (
    <article className="paper-sheet paper-key">
      <h2>ĐÁP ÁN – {title}</h2>
      {blocks.map((cols, b) => <table key={b} className="paper-key-table">
        <thead><tr><th>Mã đề</th>{cols.map(c => <th key={c}>{c + 1}</th>)}</tr></thead>
        <tbody>{versions.map(v => <tr key={v.code}><th>{v.code}</th>{cols.map(c => {
          const it = v.items[c]
          return <td key={c}>{isLong(it) ? '*' : it.question.questionType === 'TRUE_FALSE' ? it.key.slice(0, 1) : it.key}</td>
        })}</tr>)}</tbody>
      </table>)}
      <p className="paper-note">TL: câu tự luận, chấm theo hướng dẫn riêng. Đ/S: Đúng/Sai; câu nhiều ý ghi Đ/S theo thứ tự a, b, c, d.{long.length > 0 && ' (*) đáp án trả lời ngắn, điền chỗ trống và nối cột ghi bên dưới.'}</p>
      {long.length > 0 && <dl className="paper-long">{long.map(({ code, item }) => <Fragment key={`${code}-${item.number}`}><dt>Mã {code} – Câu {item.number}</dt><dd>{item.key}</dd></Fragment>)}</dl>}
    </article>
  )
}

/** Bubble answer sheet for the choice questions of one code (essay and short answers are written on the paper). */
function AnswerSheet({ v, h }: { v: PaperVersion; h: Header }) {
  const choice = v.items.filter(i => i.question.questionType === 'MULTIPLE_CHOICE' || i.question.questionType === 'TRUE_FALSE' || i.question.questionType === 'MULTIPLE_RESPONSE')
  const groups = v.items.filter(i => i.question.questionType === 'TRUE_FALSE_GROUP')
  const width = Math.max(2, ...choice.map(i => i.question.questionType === 'TRUE_FALSE' ? 2 : i.options.length))
  return (
    <article className="paper-sheet paper-answer-sheet">
      <h2>PHIẾU TRẢ LỜI TRẮC NGHIỆM</h2>
      <p className="paper-sheet-sub">{h.title}{h.subject && ` – ${h.subject}`}</p>
      <div className="paper-candidate">
        <span>Họ và tên: ……………………………………………</span><span>Lớp: ………</span><span>Số báo danh: ……………</span>
        <span className="paper-code">Mã đề {v.code}</span>
      </div>
      <p className="paper-note">Tô kín một ô cho mỗi câu (câu Đúng/Sai: Đ hoặc S; câu nhiều đáp án: tô mọi ô đúng). Muốn đổi đáp án, gạch chéo ô cũ rồi tô ô mới.</p>
      <div className="bubble-grid">
        {choice.map(i => <div key={i.number} className="bubble-row">
          <span className="bubble-n">{i.number}</span>
          {Array.from({ length: width }, (_, k) => {
            const label = i.question.questionType === 'TRUE_FALSE' ? (['Đ', 'S'][k] ?? '') : (i.options[k]?.letter ?? '')
            return <span key={k} className={label ? 'bubble' : 'bubble bubble-empty'}>{label}</span>
          })}
        </div>)}
      </div>
      {groups.length > 0 && <>
        <h3 className="paper-part">PHẦN ĐÚNG/SAI – mỗi ý tô Đ hoặc S</h3>
        <div className="bubble-grid bubble-grid-tf">
          {groups.map(i => <div key={i.number} className="bubble-tf">
            <span className="bubble-n">{i.number}</span>
            <div className="bubble-tf-items">{i.options.map(o => <span key={o.letter} className="bubble-row">
              <span className="bubble-l">{o.letter})</span><span className="bubble">Đ</span><span className="bubble">S</span></span>)}</div>
          </div>)}
        </div>
      </>}
      {choice.length + groups.length < v.items.length && <p className="paper-note">Các câu {v.items.filter(i => !choice.includes(i) && !groups.includes(i)).map(i => i.number).join(', ')} làm trực tiếp trên đề.</p>}
    </article>
  )
}
