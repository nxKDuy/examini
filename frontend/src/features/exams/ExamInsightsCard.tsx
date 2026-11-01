import { useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, CheckCircle2, ListTodo, RefreshCw, Sparkles } from 'lucide-react'
import { analyticsApi, type ExamInsights, type QuestionFlag } from './analyticsApi'
import { Badge, ErrorState, LoadingState, Section } from '../../components/ui'
import { formatDateTime } from '../../lib/format'
import { t, useLangStore, useT } from '../../lib/i18n'
import { useState } from 'react'

const LEVEL: Record<ExamInsights['level'], { label: () => string; tone: 'success' | 'info' | 'warning' | 'danger' | 'muted' }> = {
  GOOD: { label: () => t('Tốt'), tone: 'success' }, FAIR: { label: () => t('Khá'), tone: 'info' },
  AVERAGE: { label: () => t('Trung bình'), tone: 'warning' }, WEAK: { label: () => t('Yếu'), tone: 'danger' }, UNKNOWN: { label: () => t('Chưa đủ dữ liệu'), tone: 'muted' },
}
const ISSUE: Record<string, () => string> = {
  TOO_HARD: () => t('Quá khó'), TOO_EASY: () => t('Quá dễ, không phân loại'), OFTEN_SKIPPED: () => t('Bị bỏ trống nhiều'),
  NEGATIVE_DISCRIMINATION: () => t('Có thể sai đáp án'), LOW_DISCRIMINATION: () => t('Phân loại kém'),
}

/** Teacher-facing reading of an exam's results: rule-based at once, rewritten by AI on request. */
export default function ExamInsightsCard({ examId, actorId }: { examId: string; actorId: string }) {
  useT()
  const lang = useLangStore(s => s.lang)
  const qc = useQueryClient()
  const [ai, setAi] = useState(false)
  const q = useQuery({ queryKey: ['analytics', actorId, examId, 'insights', ai, lang], queryFn: () => analyticsApi.insights(examId, ai, lang), retry: false, staleTime: 60_000 })
  const d = q.data
  return (
    <Section title={<span className="row" style={{ gap: 8 }}><Sparkles size={18} aria-hidden /> {t('Nhận xét kết quả')}</span>}
      description={t('Đọc số liệu như một chuyên viên khảo thí: mức độ chung, câu bất thường và việc nên làm. AI chỉ nhận số liệu tổng hợp, không nhận nội dung câu hỏi hay tên học sinh.')}
      actions={d && <div className="row" style={{ gap: 8 }}>
        {!ai ? <button type="button" className="btn btn-primary btn-sm" disabled={!d.aiAvailable || q.isFetching} title={d.aiAvailable ? undefined : t('Máy chủ chưa cấu hình AI (GEMINI_API_KEY hoặc OLLAMA_URL).')} onClick={() => setAi(true)}>
            <Sparkles size={14} aria-hidden /> {t('Viết nhận xét bằng AI')}</button>
          : <button type="button" className="btn btn-secondary btn-sm" disabled={q.isFetching} onClick={() => setAi(false)}>{t('Xem bản theo số liệu')}</button>}
        <button type="button" className="btn btn-ghost btn-sm" disabled={q.isFetching} onClick={() => void qc.invalidateQueries({ queryKey: ['analytics', actorId, examId] })}><RefreshCw size={14} aria-hidden /> {t('Làm mới')}</button>
      </div>}>
      {q.isLoading || (q.isFetching && ai && d?.source !== 'AI') ? <LoadingState rows={3} label={ai ? t('AI đang viết nhận xét…') : undefined} />
        : q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        : d && <div className="stack insight">
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <Badge tone={LEVEL[d.level].tone}>{t('Mức chung: {0}', { 0: LEVEL[d.level].label() })}</Badge>
            <Badge tone={d.source === 'AI' ? 'info' : 'neutral'} icon={d.source === 'AI' ? <Sparkles size={12} aria-hidden /> : undefined}>{d.source === 'AI' ? t('Viết bởi AI ({0})', { 0: d.model ?? '' }) : t('Tính từ số liệu')}</Badge>
            {ai && d.source !== 'AI' && <span className="xsmall muted">{t('AI chưa trả lời được, đang hiện bản theo số liệu.')}</span>}
            <span className="xsmall muted">{formatDateTime(d.generatedAt)}</span>
          </div>
          <p className="insight-summary">{d.summary}</p>
          <div className="insight-cols">
            <InsightList icon={<CheckCircle2 size={16} aria-hidden />} title={t('Điểm mạnh')} items={d.strengths} tone="good" />
            <InsightList icon={<AlertTriangle size={16} aria-hidden />} title={t('Cần chú ý')} items={d.concerns} tone="warn" />
            <InsightList icon={<ListTodo size={16} aria-hidden />} title={t('Việc nên làm')} items={d.actions} tone="act" />
          </div>
          {d.flags.length > 0 && <Flags flags={d.flags} />}
        </div>}
    </Section>
  )
}

function InsightList({ icon, title, items, tone }: { icon: React.ReactNode; title: string; items: string[]; tone: string }) {
  return <div className="insight-list" data-tone={tone}>
    <h3 className="row small" style={{ gap: 6 }}>{icon} {title}</h3>
    {items.length ? <ul>{items.map((s, i) => <li key={i}>{s}</li>)}</ul> : <p className="xsmall muted">{t('Không có.')}</p>}
  </div>
}

function Flags({ flags }: { flags: QuestionFlag[] }) {
  useT()
  return <div className="table-wrap"><table className="table">
    <caption className="small" style={{ textAlign: 'left', paddingBottom: 6 }}>{t('Câu cần xem lại')}</caption>
    <thead><tr><th scope="col">{t('Câu')}</th><th scope="col">{t('Vấn đề')}</th><th scope="col">{t('Số liệu')}</th></tr></thead>
    <tbody>{flags.map((f, i) => <tr key={i}><td>{t('Câu')} {f.position}</td><td>{ISSUE[f.issue]?.() ?? f.issue}</td><td className="mono">{f.detail}</td></tr>)}</tbody>
  </table></div>
}
