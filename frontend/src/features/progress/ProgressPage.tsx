import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Lightbulb, Minus, Sparkles, Target, TrendingDown, TrendingUp } from 'lucide-react'
import { progressApi, type StudyInsights } from './progressApi'
import { pointLabel, scoredTimeline, trendOf } from './progressModel'
import { BarList, LineChart } from '../../components/charts/Charts'
import { Badge, EmptyState, ErrorState, LoadingState, PageHeader, Section } from '../../components/ui'
import { QUESTION_TYPE_LABEL, formatDateTime, formatScore } from '../../lib/format'
import { t, useLangStore, useT } from '../../lib/i18n'
import './progress.css'

const PHASE: Record<string, () => string> = {
  PUBLISHED: () => t('Đã công bố'), REVIEWING: () => t('Chờ giáo viên chấm'), WAITING: () => t('Đang chấm'), UNRELEASED: () => t('Chờ công bố'),
}
const pct = (v: number | null | undefined) => (v === null || v === undefined ? '–' : `${formatScore(v)}%`)

/** "My progress": scores over time, by question type, and study tips (rules, or AI on request). */
export default function ProgressPage() {
  useT()
  const q = useQuery({ queryKey: ['progress', 'mine'], queryFn: progressApi.mine })
  const o = q.data?.overview
  const timeline = scoredTimeline(q.data?.items ?? [])
  const repeated = new Set(timeline.map(i => i.examId)).size < timeline.length
  const trend = trendOf(o?.trend ?? null)
  return (
    <div className="stack-lg">
      <PageHeader title={t('Tiến bộ của tôi')} description={t('Điểm các bài thi đã được công bố, theo thời gian và theo dạng câu hỏi, kèm gợi ý ôn tập. Chỉ bạn thấy trang này.')} />
      {q.isLoading ? <LoadingState rows={4} /> : q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : o && <>
        <div className="grid-stats progress-kpis">
          <div className="card stat"><div className="stat-label">{t('Kỳ thi đã làm')}</div><div className="stat-value">{o.exams}</div><div className="xsmall muted">{t('{0} lượt · {1} đã công bố', { 0: o.attempts, 1: o.published })}</div></div>
          <div className="card stat"><div className="stat-label">{t('Điểm trung bình')}</div><div className="stat-value">{pct(o.averagePercent)}</div><div className="xsmall muted">{t('theo % điểm tối đa')}</div></div>
          <div className="card stat"><div className="stat-label">{t('Cao nhất')}</div><div className="stat-value">{pct(o.bestPercent)}</div><div className="xsmall muted">{t('Gần nhất: {0}', { 0: pct(o.latestPercent) })}</div></div>
          <div className="card stat"><div className="stat-label">{t('Xu hướng gần đây')}</div>
            <div className="stat-value row" style={{ gap: 6 }}>{trend === 'UP' ? <TrendingUp size={22} aria-hidden /> : trend === 'DOWN' ? <TrendingDown size={22} aria-hidden /> : <Minus size={22} aria-hidden />}
              {o.trend === null ? '–' : `${o.trend > 0 ? '+' : ''}${formatScore(o.trend)}`}</div>
            <div className="xsmall muted">{trend === 'UP' ? t('Đang tiến bộ') : trend === 'DOWN' ? t('Đang giảm, cần ôn lại') : trend === 'FLAT' ? t('Ổn định') : t('Cần ít nhất 2 bài có điểm')}</div></div>
        </div>
        <div className="progress-grid">
          <Section title={t('Điểm theo thời gian')} description={t('% điểm tối đa của từng bài đã công bố (tối đa 20 bài gần nhất).')}>
            {timeline.length ? <LineChart title={t('Điểm theo thời gian')} labels={timeline.map(i => pointLabel(i, repeated))} max={100}
              series={[{ name: t('Điểm (%)'), values: timeline.map(i => i.percent) }]} format={v => `${formatScore(v)}%`} />
              : <EmptyState title={t('Chưa có điểm được công bố')} description={t('Biểu đồ xuất hiện khi giáo viên công bố kết quả có hiện điểm.')} />}
          </Section>
          <Section title={t('Theo dạng câu hỏi')} description={t('Tỷ lệ điểm đạt được trên tổng điểm của mỗi dạng câu (khi kỳ thi cho xem chi tiết).')}>
            <BarList title={t('Theo dạng câu hỏi')} max={100} format={v => `${formatScore(v)}%`}
              rows={o.byType.map(b => ({ label: QUESTION_TYPE_LABEL[b.questionType] ?? b.questionType, value: b.percent, hint: t('{0} câu · {1}/{2} điểm', { 0: b.questions, 1: formatScore(b.earned), 2: formatScore(b.possible) }) }))} />
          </Section>
        </div>
        <StudyTips />
        <Section title={t('Các bài đã làm')}>
          {!q.data!.items.length ? <EmptyState title={t('Bạn chưa làm bài thi nào')} description={t('Kết quả xuất hiện ở đây sau khi bạn nộp bài.')} action={<Link to="/my-exams" className="btn btn-primary btn-sm">{t('Đến Kỳ thi của tôi')}</Link>} />
            : <div className="table-wrap"><table className="table">
              <thead><tr><th scope="col">{t('Kỳ thi')}</th><th scope="col">{t('Lượt')}</th><th scope="col">{t('Trạng thái')}</th><th scope="col">{t('Điểm')}</th><th scope="col">{t('Nộp lúc')}</th><th scope="col"><span className="sr-only">{t('Mở')}</span></th></tr></thead>
              <tbody>{q.data!.items.map(i => <tr key={i.sessionId}>
                <td>{i.title}</td><td>{i.attemptNumber}{i.invalidated && <> <Badge tone="warning">{t('Vô hiệu hóa')}</Badge></>}</td>
                <td>{PHASE[i.phase]?.() ?? i.phase}</td>
                <td className="mono">{i.score === null ? '—' : `${formatScore(i.score)}/${formatScore(i.maxScore)} (${pct(i.percent)})`}</td>
                <td>{formatDateTime(i.submittedAt, '—')}</td>
                <td><Link to={`/exam/result/${i.sessionId}`} className="btn btn-ghost btn-sm">{t('Xem')}</Link></td>
              </tr>)}</tbody></table></div>}
        </Section>
      </>}
    </div>
  )
}

const TIP_ICON = { STRENGTH: Target, FOCUS: Lightbulb, HABIT: Sparkles } as const
function StudyTips() {
  useT()
  const lang = useLangStore(s => s.lang)
  const [ai, setAi] = useState(false)
  const q = useQuery({ queryKey: ['progress', 'insights', ai, lang], queryFn: () => progressApi.insights(ai, lang), retry: false, staleTime: 120_000 })
  const d: StudyInsights | undefined = q.data
  return (
    <Section title={<span className="row" style={{ gap: 8 }}><Lightbulb size={18} aria-hidden /> {t('Gợi ý ôn tập')}</span>}
      description={t('Dựa trên điểm của chính bạn. AI chỉ nhận số liệu tổng hợp, không nhận tên hay bài làm của bạn.')}
      actions={d && (!ai
        ? <button type="button" className="btn btn-primary btn-sm" disabled={!d.aiAvailable || q.isFetching} title={d.aiAvailable ? undefined : t('Máy chủ chưa bật AI.')} onClick={() => setAi(true)}><Sparkles size={14} aria-hidden /> {t('Hỏi AI gợi ý')}</button>
        : <button type="button" className="btn btn-secondary btn-sm" onClick={() => setAi(false)}>{t('Xem gợi ý cơ bản')}</button>)}>
      {q.isLoading ? <LoadingState rows={2} label={ai ? t('AI đang soạn gợi ý…') : undefined} /> : q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : d && <div className="stack">
        <p>{d.summary} {d.source === 'AI' && <Badge tone="info" icon={<Sparkles size={12} aria-hidden />}>AI</Badge>}</p>
        {d.tips.length > 0 && <ul className="tips">{d.tips.map((tip, i) => { const Icon = TIP_ICON[tip.kind] ?? Lightbulb; return <li key={i} data-kind={tip.kind}><Icon size={16} aria-hidden /><span>{tip.text}</span></li> })}</ul>}
      </div>}
    </Section>
  )
}
