import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Activity, AlertTriangle, CheckCircle2, Info, Sparkles } from 'lucide-react'
import { statsApi } from './statsApi'
import { analyticsApi } from '../exams/analyticsApi'
import { highlights, rowsOf, share, type Highlight } from './adminStatsModel'
import { BarList, LineChart } from '../../components/charts/Charts'
import { dayLabel } from '../../components/charts/chartModel'
import { ErrorState, LoadingState, PageHeader, Section } from '../../components/ui'
import Select from '../../components/Select'
import { EXAM_STATUS } from '../../components/statusDefs'
import { DIFFICULTY_LABEL, EXAM_TYPE_LABEL, QUESTION_TYPE_LABEL, formatScore } from '../../lib/format'
import { i18nMap, t, useT } from '../../lib/i18n'
import './admin-stats.css'

const METHOD = i18nMap({ MANUAL: 'Soạn tay', IMPORT: 'Nhập từ file', AI_GENERATED: 'Tạo bằng AI', COPIED: 'Sao chép' }) as Record<string, string>
const ROLE = i18nMap({ students: 'Học sinh', teachers: 'Giáo viên', admins: 'Quản trị viên' }) as Record<string, string>
const HIGHLIGHT: Record<string, string> = i18nMap({
  ATTEMPTS_UP: 'Lượt thi 7 ngày qua tăng {0}% so với tuần trước ({1} lượt).',
  ATTEMPTS_DOWN: 'Lượt thi 7 ngày qua giảm {0}% so với tuần trước ({1} lượt).',
  ATTEMPTS_WEEK: '{0} lượt thi trong 7 ngày qua.',
  BACKLOG: '{0} bài nộp đang chờ chấm tay.',
  AI_ACCEPT: 'Giáo viên giữ lại {0}% câu hỏi do AI tạo ({1}/{2} câu).',
  AI_FAIL: '{0}% lượt tạo câu bằng AI bị lỗi; kiểm tra khóa API hoặc máy chủ AI.',
  AI_SHARE: '{0}% câu hỏi trong hệ thống được tạo bằng AI.',
  LOCKED: '{0} tài khoản đang bị khóa.',
  NEW_USERS: '{0} tài khoản mới trong kỳ.',
}) as Record<string, string>

/** System statistics for administrators: accounts, exams, attempts and how AI is used. Counts only, no personal data. */
export default function AdminStatsPage() {
  useT()
  const [days, setDays] = useState(30)
  const users = useQuery({ queryKey: ['admin-stats', 'users', days], queryFn: () => statsApi.users(days) })
  const activity = useQuery({ queryKey: ['admin-stats', 'activity', days], queryFn: () => analyticsApi.adminOverview(days) })
  const topIds = activity.data?.topExams.map(e => e.examId) ?? []
  const exams = useQuery({ queryKey: ['admin-stats', 'exams', days, topIds.join(',')], queryFn: () => statsApi.exams(days, topIds), enabled: !activity.isLoading })
  const questions = useQuery({ queryKey: ['admin-stats', 'questions', days], queryFn: () => statsApi.questions(days) })
  const u = users.data, a = activity.data, e = exams.data, q = questions.data
  const aiQuestions = q?.byCreationMethod.AI_GENERATED ?? 0
  const notes = u && a && q ? highlights({ attemptsPerDay: a.perDay.map(d => d.attempts), usersNew: u.newInPeriod, locked: u.locked, needsReview: a.needsReview,
    submissions: a.submissions, aiGenerated: q.ai.draftsGenerated, aiImported: q.ai.draftsImported, aiJobs: q.ai.jobs, aiFailed: q.ai.jobsFailed, questionsTotal: q.total, aiQuestions }) : []
  const n = (v: number | undefined) => (v === undefined ? '–' : v.toLocaleString())

  return (
    <div className="stack-lg">
      <PageHeader title={t('Thống kê hệ thống')} description={t('Người dùng, đề thi, lượt thi và mức độ dùng AI trên toàn hệ thống. Chỉ số đếm, không có dữ liệu cá nhân.')}
        actions={<label className="row small" style={{ gap: 8 }}>{t('Khoảng thời gian')}
          <Select className="input" value={String(days)} onChange={ev => setDays(Number(ev.target.value))} style={{ width: 150 }}>
            <option value="7">{t('7 ngày')}</option><option value="30">{t('30 ngày')}</option><option value="90">{t('90 ngày')}</option><option value="365">{t('365 ngày')}</option>
          </Select></label>} />

      <div className="grid-stats admin-kpis">
        <Kpi label={t('Người dùng')} value={n(u?.total)} detail={u && t('+{0} trong kỳ · {1} hoạt động 7 ngày', { 0: u.newInPeriod, 1: u.activeLast7Days })} />
        <Kpi label={t('Đề thi')} value={n(e?.total)} detail={e && t('+{0} trong kỳ · {1} đang mở', { 0: e.createdInPeriod, 1: e.byStatus.OPEN ?? 0 })} />
        <Kpi label={t('Lượt thi')} value={n(a?.attempts)} detail={a && t('{0} học sinh · {1} kỳ thi có người làm', { 0: a.activeStudents, 1: a.activeExams })} />
        <Kpi label={t('Bài nộp')} value={n(a?.submissions)} detail={a && t('{0} đã chấm · {1} chờ chấm', { 0: a.graded, 1: a.needsReview })} />
        <Kpi label={t('Điểm TB đã công bố')} value={a?.publishedAveragePercent == null ? '–' : `${formatScore(a.publishedAveragePercent)}%`} detail={a && t('{0} kết quả', { 0: a.publishedResults })} />
        <Kpi label={t('Câu hỏi')} value={n(q?.total)} detail={q && t('{0}% tạo bằng AI · {1} có hình/âm thanh', { 0: share(aiQuestions, q.total), 1: q.withMedia })} />
      </div>

      {notes.length > 0 && <Section title={<span className="row" style={{ gap: 8 }}><Activity size={18} aria-hidden /> {t('Điểm đáng chú ý')}</span>}>
        <ul className="admin-notes">{notes.map((h: Highlight) => <li key={h.key} data-tone={h.tone}>
          {h.tone === 'good' ? <CheckCircle2 size={16} aria-hidden /> : h.tone === 'warn' ? <AlertTriangle size={16} aria-hidden /> : <Info size={16} aria-hidden />}
          <span>{t(HIGHLIGHT[h.key] ?? h.key, h.vars)}</span></li>)}</ul>
      </Section>}

      <Section title={t('Lượt thi và bài nộp theo ngày')}>
        {activity.isLoading ? <LoadingState rows={3} /> : activity.isError ? <ErrorState error={activity.error} onRetry={() => void activity.refetch()} /> : a &&
          <LineChart title={t('Lượt thi và bài nộp theo ngày')} labels={a.perDay.map(d => dayLabel(d.day))}
            series={[{ name: t('Lượt bắt đầu'), values: a.perDay.map(d => d.attempts) }, { name: t('Bài nộp'), values: a.perDay.map(d => d.submissions) }]} />}
      </Section>

      <div className="admin-grid">
        <Section title={t('Người dùng mới theo ngày')}>
          {users.isLoading ? <LoadingState rows={2} /> : users.isError ? <ErrorState error={users.error} onRetry={() => void users.refetch()} /> : u &&
            <LineChart title={t('Người dùng mới theo ngày')} height={180} labels={u.newPerDay.map(d => dayLabel(d.day))} series={[{ name: t('Tài khoản mới'), values: u.newPerDay.map(d => d.count) }]} />}
        </Section>
        <Section title={t('Người dùng theo vai trò')}>
          {u ? <><BarList title={t('Người dùng theo vai trò')} rows={(['students', 'teachers', 'admins'] as const).map(k => ({ label: ROLE[k], value: u[k] }))} />
            <p className="xsmall muted" style={{ marginTop: 10 }}>{t('{0} liên kết Google · {1} bị khóa · {2} hoạt động trong 30 ngày', { 0: u.googleLinked, 1: u.locked, 2: u.activeLast30Days })}</p>
            <Link to="/admin/users" className="btn btn-ghost btn-sm" style={{ marginTop: 6 }}>{t('Quản lý người dùng')}</Link></>
            : users.isError ? <ErrorState error={users.error} /> : <LoadingState rows={2} />}
        </Section>
        <Section title={t('Đề thi theo trạng thái')}>
          {e ? <><BarList title={t('Đề thi theo trạng thái')} rows={rowsOf(e.byStatus, Object.fromEntries(Object.entries(EXAM_STATUS).map(([k, v]) => [k, v.label])))} />
            <p className="xsmall muted" style={{ marginTop: 10 }}>{rowsOf(e.byType, EXAM_TYPE_LABEL).map(r => `${r.label}: ${r.value}`).join(' · ')}{e.withCamera ? ` · ${t('{0} đề bật camera', { 0: e.withCamera })}` : ''}</p></>
            : exams.isError ? <ErrorState error={exams.error} /> : <LoadingState rows={2} />}
        </Section>
        <Section title={t('Phổ điểm đã công bố')} description={t('% điểm tối đa, toàn hệ thống trong kỳ.')}>
          {a ? <BarList title={t('Phổ điểm đã công bố')} rows={a.publishedDistribution.map(b => ({ label: `${b.fromPercent}–${b.toPercent}%`, value: b.count }))} /> : <LoadingState rows={2} />}
        </Section>
        <Section title={t('Câu hỏi theo dạng')}>
          {q ? <BarList title={t('Câu hỏi theo dạng')} rows={rowsOf(q.byType, QUESTION_TYPE_LABEL)} /> : questions.isError ? <ErrorState error={questions.error} /> : <LoadingState rows={2} />}
        </Section>
        <Section title={t('Câu hỏi theo độ khó và cách tạo')}>
          {q ? <><BarList title={t('Câu hỏi theo cách tạo')} rows={rowsOf(q.byCreationMethod, METHOD)} />
            <p className="xsmall muted" style={{ marginTop: 10 }}>{rowsOf(q.byDifficulty, DIFFICULTY_LABEL).map(r => `${r.label}: ${r.value}`).join(' · ')} · {t('{0} bài đăng công khai', { 0: q.publicPosts })}</p></>
            : <LoadingState rows={2} />}
        </Section>
      </div>

      <Section title={<span className="row" style={{ gap: 8 }}><Sparkles size={18} aria-hidden /> {t('Sử dụng AI')}</span>} description={t('Tạo câu hỏi bằng AI: mọi câu đều qua bước giáo viên duyệt trước khi vào kho.')}>
        {q ? <div className="admin-grid">
          <div className="grid-stats">
            <Kpi label={t('Lượt tạo bằng AI')} value={n(q.ai.jobs)} detail={t('{0} lỗi', { 0: q.ai.jobsFailed })} />
            <Kpi label={t('Câu AI đề xuất')} value={n(q.ai.draftsGenerated)} detail={t('{0} được giữ lại ({1}%)', { 0: q.ai.draftsImported, 1: share(q.ai.draftsImported, q.ai.draftsGenerated) })} />
            <Kpi label={t('Nhập từ file')} value={n(q.importJobs)} detail={t('Excel, Word, PDF, ảnh')} />
          </div>
          <div>
            <LineChart title={t('Câu hỏi mới theo ngày')} height={170} labels={q.perDay.map(d => dayLabel(d.day))}
              series={[{ name: t('Tất cả'), values: q.perDay.map(d => d.created) }, { name: t('Tạo bằng AI'), values: q.perDay.map(d => d.aiGenerated) }]} />
            {Object.keys(q.ai.byModel).length > 0 && <p className="xsmall muted">{t('Theo mô hình:')} {rowsOf(q.ai.byModel).map(r => `${r.label} (${r.value})`).join(', ')}</p>}
          </div>
        </div> : <LoadingState rows={2} />}
      </Section>

      <Section title={t('Kỳ thi nhiều lượt làm nhất')}>
        {!a ? <LoadingState rows={2} /> : !a.topExams.length ? <p className="small muted">{t('Chưa có lượt thi trong kỳ.')}</p> : <div className="table-wrap"><table className="table">
          <thead><tr><th scope="col">{t('Kỳ thi')}</th><th scope="col">{t('Lượt làm')}</th><th scope="col">{t('Học sinh')}</th><th scope="col">{t('Điểm TB công bố')}</th></tr></thead>
          <tbody>{a.topExams.map(x => <tr key={x.examId}><td><Link to={`/exams/${x.examId}`}>{e?.titles[x.examId] ?? x.examId.slice(0, 8)}</Link></td><td>{x.attempts}</td><td>{x.students}</td>
            <td>{x.averagePercent == null ? '–' : `${formatScore(x.averagePercent)}%`}</td></tr>)}</tbody></table></div>}
      </Section>
    </div>
  )
}

function Kpi({ label, value, detail }: { label: string; value: string; detail?: string | false | undefined }) {
  return <div className="card stat"><div className="stat-label">{label}</div><div className="stat-value">{value}</div>{detail && <div className="xsmall muted">{detail}</div>}</div>
}
