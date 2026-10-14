import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Dialog } from '../../components/Dialog'
import { EmptyState, ErrorState, LoadingState, Notice, Pagination } from '../../components/ui'
import { useAuthStore } from '../../store/authStore'
import { errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL } from '../../lib/format'
import { bankApi, type QuestionSet } from './bankApi'
import { MAX_SEARCH, excerpt } from './bankModel'
import { PublishPublicDialog } from './ShareDialogs'
import { t, useT } from '../../lib/i18n'

type Tab = 'question' | 'set'
const PAGE = 8

/**
 * Teacher entry point on /public-bank. It only lists what the existing flow already allows to be published: ACTIVE personal
 * questions and ACTIVE question sets of the caller's own bank. Nothing here activates, edits or guesses eligibility; a DRAFT
 * question has to be activated in the personal bank first (link below), and publishing is always an explicit, version-pinned call.
 */
export default function PublishToPublicDialog({ onClose, onPublished }: { onClose: () => void; onPublished: (message: string) => void }) {
  useT()
  const user = useAuthStore(s => s.user)
  const [tab, setTab] = useState<Tab>('question')
  const [search, setSearch] = useState(''), [text, setText] = useState(''), [page, setPage] = useState(0)
  const [pending, setPending] = useState<{ id: string; version: number } | null>(null)
  const [setConfirm, setSetConfirm] = useState<QuestionSet | null>(null)

  const banks = useQuery({ queryKey: ['banks', 'publish-targets', user?.id], queryFn: bankApi.allBanks, enabled: !!user })
  const personal = banks.data?.find(b => b.type === 'PERSONAL' && b.ownerUserId === user?.id)
  const questions = useQuery({ queryKey: ['banks', 'publishable-questions', personal?.id, text, page], enabled: !!personal && tab === 'question', placeholderData: prev => prev, queryFn: () => bankApi.personalQuestions(personal!.id, page, PAGE, text) })
  const sets = useQuery({ queryKey: ['banks', 'publishable-sets', personal?.id, text, page], enabled: !!personal && tab === 'set', placeholderData: prev => prev, queryFn: () => bankApi.questionSets(personal!.id, page, PAGE, { text, status: 'ACTIVE' }) })
  const publishSet = useMutation({
    mutationFn: (s: QuestionSet) => bankApi.publishQuestionSet(s.id, s.version),
    onSuccess: () => { setSetConfirm(null); onPublished(t('Đã xuất bản bộ câu hỏi (snapshot đúng phiên bản). Thay đổi kho cá nhân sau này không đổi bài đăng.')) },
  })
  const switchTab = (next: Tab) => { setTab(next); setSearch(''); setText(''); setPage(0) }
  const list = tab === 'question' ? questions : sets

  return (
    <>
      <Dialog title={t('Đăng lên Ngân hàng công khai')} size="lg" onClose={onClose}
        description={t('Chỉ câu hỏi và bộ đang dùng (ACTIVE) đăng được. Bài đăng là bản chụp đúng phiên bản, ai đăng nhập cũng xem được, không có đáp án và không cần duyệt.')}>
        <div className="stack">
          <div className="tabs" role="tablist" aria-label={t('Loại bài đăng')} style={{ border: '1px solid #000' }}>
            <button type="button" role="tab" className="tab" aria-selected={tab === 'question'} onClick={() => switchTab('question')}>{t('Một câu hỏi')}</button>
            <button type="button" role="tab" className="tab" aria-selected={tab === 'set'} onClick={() => switchTab('set')}>{t('Bộ câu hỏi')}</button>
          </div>
          <form className="filter-row" onSubmit={e => { e.preventDefault(); setText(search.trim().slice(0, MAX_SEARCH)); setPage(0) }}>
            <div className="field filter-grow">
              <label className="field-label" htmlFor="publish-search">{tab === 'question' ? t('Tìm trong nội dung câu hỏi') : t('Tìm bộ theo tên')}</label>
              <div className="input-group"><input id="publish-search" className="input" type="search" maxLength={MAX_SEARCH} title={t('Tối đa {0} ký tự', { 0: MAX_SEARCH })} value={search} onChange={e => setSearch(e.target.value)} /><button type="submit" className="btn btn-secondary">{t('Tìm')}</button></div>
            </div>
          </form>
          {banks.isLoading ? <LoadingState rows={3} /> : banks.isError ? <ErrorState error={banks.error} onRetry={() => void banks.refetch()} />
            : !personal ? <EmptyState title={t('Chưa có kho cá nhân')} description={t('Tạo câu hỏi ở Kho cá nhân trước khi đăng.')} />
            : list.isLoading ? <LoadingState rows={3} /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
            : (list.data?.content.length ?? 0) === 0
              ? <EmptyState title={tab === 'question' ? t('Chưa có câu hỏi đang dùng để đăng') : t('Chưa có bộ đang dùng để đăng')}
                  description={text ? t('Không có kết quả khớp.') : tab === 'question' ? t('Câu còn là bản nháp cần được kích hoạt trong Kho cá nhân trước.') : t('Tạo bộ từ các câu đang dùng trong Kho cá nhân.')} />
              : <ul className="pt-list" aria-label={tab === 'question' ? t('Câu hỏi có thể đăng') : t('Bộ có thể đăng')}>
                  {tab === 'question'
                    ? questions.data?.content.map(({ question: q }) => (
                      <li key={q.id} className="publish-row">
                        <span className="publish-row-main"><strong>{excerpt(q.content, 120)}</strong><small className="muted">{QUESTION_TYPE_LABEL[q.questionType as keyof typeof QUESTION_TYPE_LABEL] ?? ''}{' · '}{DIFFICULTY_LABEL[q.difficultyLevel as keyof typeof DIFFICULTY_LABEL] ?? ''} {t('· phiên bản')} {q.questionVersion}</small></span>
                        <button type="button" className="btn btn-primary btn-sm" aria-label={t('Đăng câu hỏi: {0}', { 0: excerpt(q.content, 50) })} onClick={() => setPending({ id: q.id, version: q.questionVersion })}>{t('Đăng')}</button>
                      </li>))
                    : sets.data?.content.map(s => (
                      <li key={s.id} className="publish-row">
                        <span className="publish-row-main"><strong>{s.title}</strong><small className="muted">{t('Phiên bản')} {s.version} · {s.items.length} {t('câu')}</small></span>
                        <button type="button" className="btn btn-primary btn-sm" aria-label={t('Đăng bộ câu hỏi: {0}', { 0: s.title })} onClick={() => setSetConfirm(s)}>{t('Đăng')}</button>
                      </li>))}
                </ul>}
          {list.data && <Pagination label={t('Phân trang')} page={page} totalPages={list.data.totalPages} onChange={setPage} />}
          <p className="small muted">{t('Chưa có nội dung phù hợp?')}{' '}
            <Link to="/questions" onClick={onClose}>{t('Soạn hoặc kích hoạt câu hỏi ở Kho cá nhân')}</Link>{' · '}
            <Link to="/questions/sets/new" onClick={onClose}>{t('Tạo bộ câu hỏi mới')}</Link></p>
        </div>
      </Dialog>
      {pending && <PublishPublicDialog questionId={pending.id} version={pending.version} onClose={() => setPending(null)} onDone={message => { setPending(null); onPublished(message) }} />}
      {setConfirm && (
        <Dialog title={t('Xuất bản bộ câu hỏi?')} onClose={() => setSetConfirm(null)} busy={publishSet.isPending}
          description={t('Xuất bản đúng phiên bản {0} của bộ “{1}” ({2} câu), không tự cập nhật khi bạn sửa sau.', { 0: setConfirm.version, 1: setConfirm.title, 2: setConfirm.items.length })}
          footer={<><button type="button" className="btn btn-secondary" onClick={() => setSetConfirm(null)} disabled={publishSet.isPending}>{t('Hủy')}</button>
            <button type="button" className="btn btn-primary" onClick={() => publishSet.mutate(setConfirm)} disabled={publishSet.isPending}>{publishSet.isPending ? t('Đang xuất bản…') : t('Xuất bản bộ')}</button></>}>
          {publishSet.isError && <Notice tone="danger" live>{errorText(publishSet.error)}</Notice>}
          <p className="small">{t('Mọi câu trong bộ phải đang dùng. Nếu có câu còn là bản nháp, máy chủ sẽ từ chối và bạn cần kích hoạt chúng trước.')}</p>
        </Dialog>
      )}
    </>
  )
}
