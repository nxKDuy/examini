import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BookOpenCheck, CheckCircle2, XCircle } from 'lucide-react'
import './banks.css'
import { bankApi, type Contribution, type OrgQuestion } from './bankApi'
import BankPanel, { QuestionView } from './BankPanel'
import { CONTRIBUTION_LABEL, ORG_BANK_TAB_LABEL, contributionQuery, excerpt, hasCapability, orgBankTabs, rejectReady, reviewState, shortId, type OrgBankTab } from './bankModel'
import type { Organization } from '../organizations/orgApi'
import { useAuthStore, hasPersona, isSystemAdmin } from '../../store/authStore'
import { ConfirmDialog, Dialog } from '../../components/Dialog'
import { Badge, EmptyState, ErrorState, Field, LoadingState, Notice, Pagination } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL, formatDateTime } from '../../lib/format'
import { useIsNarrow } from '../../lib/hooks'

/** Organization question bank inside one organization. Every button comes from the caller's capabilities; an ARCHIVED organization only has BANK_READ. */
export default function OrgBankTab({ org }: { org: Organization }) {
  const tabs = orgBankTabs(org)
  const [tab, setTab] = useState<OrgBankTab>('approved')
  const active: OrgBankTab = tabs.includes(tab) ? tab : 'approved'
  const bank = useQuery({
    queryKey: ['banks', 'org', org.id], queryFn: async () => (await bankApi.banks({ type: 'ORGANIZATION', organizationId: org.id })).content.find(b => b.organizationId === org.id) ?? null,
  })
  return (
    <div className="stack">
      {org.status === 'ARCHIVED' && <Notice tone="info" title="Tổ chức đã lưu trữ">Chỉ đọc: không gửi, không duyệt, không sao chép câu hỏi mới.</Notice>}
      <div className="tabs" role="tablist" aria-label="Ngân hàng của tổ chức" style={{ border: '1px solid #000' }}>
        {tabs.map(t => <button key={t} type="button" role="tab" className="tab" aria-selected={active === t} onClick={() => setTab(t)}>{ORG_BANK_TAB_LABEL[t]}</button>)}
      </div>
      {bank.isLoading ? <LoadingState rows={3} /> : bank.isError ? <section className="card"><ErrorState error={bank.error} onRetry={() => void bank.refetch()} /></section>
        : !bank.data ? <EmptyState icon={<BookOpenCheck size={28} />} title="Chưa có ngân hàng câu hỏi" description="Ngân hàng của tổ chức này chưa được tạo hoặc bạn chưa có quyền xem." />
        : active === 'approved' ? <ApprovedList org={org} bankId={bank.data.id} /> : <ContributionList key={active} org={org} bankId={bank.data.id} tab={active} />}
    </div>
  )
}

function ApprovedList({ org, bankId }: { org: Organization; bankId: string }) {
  const [type, setType] = useState('')
  const [difficulty, setDifficulty] = useState('')
  const [page, setPage] = useState(0)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const narrowList = useIsNarrow(899), narrowPanel = useIsNarrow(1100)
  const list = useQuery({ queryKey: ['banks', 'org-questions', bankId, type, difficulty, page], queryFn: () => bankApi.orgQuestions({ bankId, questionType: type || undefined, difficulty: difficulty || undefined, page }), placeholderData: prev => prev })
  const items = list.data?.content ?? []
  const selected = items.find(i => i.id === selectedId) ?? null
  const reset = (fn: () => void) => { fn(); setPage(0); setSelectedId(null) }
  return (
    <div className="stack">
      <div className="bk-filters">
        <label className="row small" style={{ gap: 6 }}>Loại<select className="input" style={{ width: 'auto' }} value={type} onChange={e => reset(() => setType(e.target.value))}><option value="">Tất cả</option>{Object.entries(QUESTION_TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
        <label className="row small" style={{ gap: 6 }}>Độ khó<select className="input" style={{ width: 'auto' }} value={difficulty} onChange={e => reset(() => setDifficulty(e.target.value))}><option value="">Tất cả</option>{Object.entries(DIFFICULTY_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
      </div>
      <div className="bk-layout" data-panel={selected ? 'open' : 'closed'}>
        <section className="card" aria-label="Câu hỏi đã duyệt" style={{ overflow: 'hidden', minWidth: 0 }}>
          {list.isLoading ? <LoadingState rows={3} /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
            : items.length === 0 ? <EmptyState icon={<BookOpenCheck size={28} />} title="Chưa có câu hỏi đã duyệt" description={type || difficulty ? 'Không có câu khớp bộ lọc.' : 'Câu hỏi xuất hiện ở đây sau khi người duyệt chấp nhận đóng góp.'} />
            : narrowList ? items.map(i => <OrgCard key={i.id} i={i} active={selectedId === i.id} onOpen={() => setSelectedId(i.id)} />)
            : <table className="bk-table"><thead><tr><th scope="col">Câu hỏi</th><th scope="col">Loại</th><th scope="col">Độ khó</th><th scope="col">Phiên bản nguồn</th><th scope="col">Duyệt lúc</th></tr></thead>
                <tbody>{items.map(i => (
                  <tr key={i.id} aria-selected={selectedId === i.id}>
                    <td><button type="button" className="bk-row-btn" onClick={() => setSelectedId(i.id)}>{excerpt(i.question.content)}</button></td>
                    <td>{QUESTION_TYPE_LABEL[i.question.questionType]}</td><td>{DIFFICULTY_LABEL[i.question.difficultyLevel]}</td><td className="mono">{i.sourceQuestionVersion}</td><td>{formatDateTime(i.approvedAt, '-')}</td>
                  </tr>
                ))}</tbody></table>}
          {list.data && <Pagination label="Phân trang câu hỏi tổ chức" page={page} totalPages={list.data.totalPages} onChange={p => { setPage(p); setSelectedId(null) }} />}
        </section>
        {selected && <OrgQuestionPanel org={org} item={selected} narrow={narrowPanel} onClose={() => setSelectedId(null)} />}
      </div>
    </div>
  )
}

function OrgCard({ i, active, onOpen }: { i: OrgQuestion; active: boolean; onOpen: () => void }) {
  return (
    <button type="button" className="bk-card" aria-current={active} onClick={onOpen}>
      <strong style={{ fontWeight: 400 }}>{excerpt(i.question.content, 110)}</strong>
      <span className="row xsmall muted" style={{ gap: 8 }}><span>{QUESTION_TYPE_LABEL[i.question.questionType]}</span><span>{DIFFICULTY_LABEL[i.question.difficultyLevel]}</span><span>Phiên bản nguồn {i.sourceQuestionVersion}</span></span>
    </button>
  )
}

function OrgQuestionPanel({ org, item, narrow, onClose }: { org: Organization; item: OrgQuestion; narrow: boolean; onClose: () => void }) {
  const user = useAuthStore(s => s.user)
  const canUse = org.status === 'ACTIVE' && hasCapability(org, 'BANK_USE') && (hasPersona(user, 'TEACHER') || isSystemAdmin(user))
  const [flash, setFlash] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const fork = useMutation({
    mutationFn: () => bankApi.orgFork(item.id, item.question.questionVersion),
    onSuccess: () => setFlash({ tone: 'success', text: 'Đã sao chép về kho cá nhân thành một câu độc lập (bản nháp).' }),
    onError: e => setFlash({ tone: 'danger', text: errorText(e) }),
  })
  return (
    <BankPanel title="Câu hỏi của tổ chức" subtitle={`Phiên bản nguồn ${item.sourceQuestionVersion} · duyệt ${formatDateTime(item.approvedAt, '-')}`} narrow={narrow} onClose={onClose}>
      {flash && <Notice tone={flash.tone} live action={flash.tone === 'success' ? <Link to="/questions" className="btn btn-secondary btn-sm">Mở kho cá nhân</Link> : undefined}>{flash.text}</Notice>}
      <QuestionView question={item.question} />
      {canUse
        ? <div><button type="button" className="btn btn-primary" disabled={fork.isPending} onClick={() => { setFlash(null); fork.mutate() }}>{fork.isPending ? 'Đang sao chép…' : 'Sao chép về kho cá nhân'}</button></div>
        : <p className="xsmall muted">Bạn không có quyền sao chép câu hỏi từ tổ chức này.</p>}
    </BankPanel>
  )
}

function ContributionList({ org, bankId, tab }: { org: Organization; bankId: string; tab: Exclude<OrgBankTab, 'approved'> }) {
  const q = contributionQuery(tab, org.status === 'ACTIVE' && hasCapability(org, 'BANK_REVIEW'))!
  const [page, setPage] = useState(0)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [statusFilter, setStatusFilter] = useState('')
  const narrowList = useIsNarrow(899), narrowPanel = useIsNarrow(1100)
  const status = tab === 'history' ? statusFilter || undefined : q.status
  const list = useQuery({ queryKey: ['banks', 'contributions', bankId, tab, status, page], queryFn: () => bankApi.contributions({ bankId, status, mine: q.mine, page }), placeholderData: prev => prev })
  const rows = list.data?.content ?? []
  const selected = rows.find(c => c.id === selectedId) ?? null
  return (
    <div className="stack">
      {tab === 'history' && (
        <label className="row small" style={{ gap: 6 }}>Trạng thái<select className="input" style={{ width: 'auto' }} value={statusFilter} onChange={e => { setStatusFilter(e.target.value); setPage(0); setSelectedId(null) }}>
          <option value="">Tất cả trạng thái</option><option value="APPROVED">Đã duyệt</option><option value="REJECTED">Bị từ chối</option><option value="PENDING_REVIEW">Chờ duyệt</option></select></label>
      )}
      <div className="bk-layout" data-panel={selected ? 'open' : 'closed'}>
        <section className="card" aria-label={ORG_BANK_TAB_LABEL[tab]} style={{ overflow: 'hidden', minWidth: 0 }}>
          {list.isLoading ? <LoadingState rows={3} /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
            : rows.length === 0 ? <EmptyState icon={<BookOpenCheck size={28} />} title={tab === 'queue' ? 'Không có đóng góp chờ duyệt' : tab === 'mine' ? 'Bạn chưa gửi đóng góp nào' : 'Chưa có lịch sử'}
                description={tab === 'mine' ? 'Gửi câu hỏi từ kho cá nhân bằng nút "Gửi vào tổ chức" ở chi tiết câu hỏi đang dùng.' : undefined} />
            : narrowList ? rows.map(c => <ContributionCard key={c.id} c={c} active={selectedId === c.id} onOpen={() => setSelectedId(c.id)} />)
            : <table className="bk-table"><thead><tr><th scope="col">Câu hỏi</th><th scope="col">Phiên bản</th><th scope="col">Trạng thái</th><th scope="col">Gửi lúc</th><th scope="col">Xử lý lúc</th></tr></thead>
                <tbody>{rows.map(c => (
                  <tr key={c.id} aria-selected={selectedId === c.id}>
                    <td><button type="button" className="bk-row-btn" onClick={() => setSelectedId(c.id)}>{excerpt(c.question.content)}</button>{c.submissionNote && <div className="xsmall muted">Ghi chú gửi: {excerpt(c.submissionNote, 120)}</div>}{c.status === 'REJECTED' && c.reviewNote && <div className="xsmall muted">Lý do: {c.reviewNote}</div>}</td>
                    <td className="mono">{c.sourceQuestionVersion}</td><td><Badge tone={CONTRIBUTION_LABEL[c.status].tone}>{CONTRIBUTION_LABEL[c.status].text}</Badge></td>
                    <td>{formatDateTime(c.submittedAt, '-')}</td><td>{formatDateTime(c.reviewedAt, '-')}</td>
                  </tr>
                ))}</tbody></table>}
          {list.data && <Pagination label="Phân trang đóng góp" page={page} totalPages={list.data.totalPages} onChange={p => { setPage(p); setSelectedId(null) }} />}
        </section>
        {selected && <ContributionPanel key={selected.id} org={org} c={selected} narrow={narrowPanel} onClose={() => setSelectedId(null)} />}
      </div>
    </div>
  )
}

function ContributionCard({ c, active, onOpen }: { c: Contribution; active: boolean; onOpen: () => void }) {
  return (
    <button type="button" className="bk-card" aria-current={active} onClick={onOpen}>
      <span className="spread"><strong style={{ fontWeight: 400 }}>{excerpt(c.question.content, 90)}</strong><Badge tone={CONTRIBUTION_LABEL[c.status].tone}>{CONTRIBUTION_LABEL[c.status].text}</Badge></span>
      <span className="row xsmall muted" style={{ gap: 8 }}><span>Phiên bản {c.sourceQuestionVersion}</span><span>Gửi {formatDateTime(c.submittedAt, '-')}</span>{c.submissionNote && <span>Ghi chú gửi: {excerpt(c.submissionNote, 120)}</span>}{c.status === 'REJECTED' && c.reviewNote && <span>Lý do: {c.reviewNote}</span>}</span>
    </button>
  )
}

function ContributionPanel({ org, c, narrow, onClose }: { org: Organization; c: Contribution; narrow: boolean; onClose: () => void }) {
  const qc = useQueryClient()
  const userId = useAuthStore(s => s.user?.id)
  const canReview = org.status === 'ACTIVE' && hasCapability(org, 'BANK_REVIEW')
  const detail = useQuery({ queryKey: ['banks', 'contribution', c.id, canReview], queryFn: () => bankApi.contribution(c.id, canReview) })
  const current = detail.data ?? c
  const state = reviewState(current, canReview && detail.isSuccess, userId)
  const [dialog, setDialog] = useState<'approve' | 'reject' | null>(null)
  const [note, setNote] = useState('')
  const [flash, setFlash] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const refresh = async () => { await qc.invalidateQueries({ queryKey: ['banks'] }) }
  const review = useMutation({
    mutationFn: (decision: 'APPROVED' | 'REJECTED') => bankApi.review(c.id, decision, note.trim() || undefined),
    onSuccess: async (_r, decision) => { setDialog(null); setNote(''); setFlash({ tone: 'success', text: decision === 'APPROVED' ? 'Đã duyệt. Câu hỏi đã vào danh sách của tổ chức.' : 'Đã từ chối đóng góp.' }); await refresh() },
    // 403/409: show the server's message and reload the true state; nothing is re-sent on its own.
    onError: async e => { setDialog(null); setFlash({ tone: 'danger', text: errorText(e) }); await refresh(); await detail.refetch() },
  })
  const q = current.question
  return (
    <BankPanel title="Đóng góp" subtitle={`Phiên bản ${c.sourceQuestionVersion} · gửi ${formatDateTime(c.submittedAt, '-')}`} narrow={narrow} onClose={onClose}>
      {flash && <Notice tone={flash.tone} live>{flash.text}</Notice>}
      <div className="row" style={{ gap: 6 }}><Badge tone={CONTRIBUTION_LABEL[current.status].tone}>{CONTRIBUTION_LABEL[current.status].text}</Badge>{current.reviewedAt && <span className="xsmall muted">Xử lý {formatDateTime(current.reviewedAt, '-')}</span>}</div>
      {current.submissionNote && <Notice tone="info" title="Ghi chú của người gửi"><span className="prewrap">{current.submissionNote}</span></Notice>}
      {current.status === 'REJECTED' && <Notice tone="danger" title="Lý do từ chối">{current.reviewNote || 'Không có ghi chú.'}</Notice>}
      {detail.isLoading ? <LoadingState rows={2} /> : detail.isError ? <ErrorState error={detail.error} onRetry={() => void detail.refetch()} /> : (
        <>
          {canReview && <Notice tone="info">Người duyệt thấy cả đáp án; học sinh và người ngoài tổ chức không thấy.</Notice>}
          <QuestionView question={q} showAnswers={canReview} />
        </>
      )}
      {canReview && current.status === 'PENDING_REVIEW' && (
        state.canAct ? (
          <div className="stack-sm">
            <Field label="Ghi chú duyệt" htmlFor="contrib-note" hint="Bắt buộc khi từ chối; người gửi sẽ thấy ghi chú này."><textarea id="contrib-note" className="input" rows={3} maxLength={5000} value={note} onChange={e => setNote(e.target.value)} /></Field>
            <div className="row">
              <button type="button" className="btn btn-primary" disabled={review.isPending} onClick={() => setDialog('approve')}><CheckCircle2 size={16} aria-hidden /> Duyệt</button>
              <button type="button" className="btn btn-danger" disabled={review.isPending || !rejectReady(note)} onClick={() => setDialog('reject')}><XCircle size={16} aria-hidden /> Từ chối</button>
            </div>
          </div>
        ) : <Notice tone="warning" title="Chưa thể duyệt">{state.note}</Notice>
      )}
      {dialog === 'approve' && <ConfirmDialog title="Duyệt đóng góp này?" confirmLabel="Duyệt" busy={review.isPending} onConfirm={() => review.mutate('APPROVED')} onCancel={() => setDialog(null)}
        description="Câu hỏi (đúng phiên bản đã gửi) sẽ vào ngân hàng của tổ chức và thành viên có quyền dùng được sao chép." />}
      {dialog === 'reject' && <Dialog title="Từ chối đóng góp?" onClose={() => setDialog(null)} busy={review.isPending}
        description="Câu hỏi gốc của người gửi không bị ảnh hưởng. Họ sẽ thấy lý do dưới đây."
        footer={<><button type="button" className="btn btn-secondary" onClick={() => setDialog(null)} disabled={review.isPending}>Hủy</button>
          <button type="button" className="btn btn-danger" onClick={() => review.mutate('REJECTED')} disabled={review.isPending || !rejectReady(note)}>{review.isPending ? 'Đang xử lý…' : 'Từ chối'}</button></>}>
        <p className="prewrap small">{note.trim()}</p></Dialog>}
      <p className="xsmall muted">Mã đóng góp {shortId(c.id)}</p>
    </BankPanel>
  )
}
