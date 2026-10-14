import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ShieldOff, ShieldX } from 'lucide-react'
import { integrityApi } from './integrityApi'
import { examApi, type Exam } from '../exams/examApi'
import { Dialog } from '../../components/Dialog'
import { Field, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { t, useT } from '../../lib/i18n'

/** Whole-Exam lock for OWNER/MANAGER/PROCTOR: blocks new starts and submits every attempt in progress. */
export default function IntegrityLockControl({ exam, onDone }: { exam: Exam; onDone: (text: string) => void }) {
  useT()
  const [open, setOpen] = useState(false)
  const locked = !!exam.integrityLocked
  return <>
    {locked
      ? <button type="button" className="btn btn-secondary" onClick={() => setOpen(true)}><ShieldOff size={16} aria-hidden /> {t('Mở khóa kỳ thi')}</button>
      : <button type="button" className="btn btn-danger" onClick={() => setOpen(true)}><ShieldX size={16} aria-hidden /> {t('Khóa kỳ thi')}</button>}
    {open && <LockDialog exam={exam} locked={locked} onClose={() => setOpen(false)} onDone={text => { setOpen(false); onDone(text) }} />}
  </>
}

function LockDialog({ exam, locked, onClose, onDone }: { exam: Exam; locked: boolean; onClose: () => void; onDone: (text: string) => void }) {
  useT()
  const qc = useQueryClient()
  const [reason, setReason] = useState('')
  const sessions = useQuery({ queryKey: ['exam-sessions', exam.id], queryFn: () => examApi.sessions(exam.id), enabled: !locked })
  const active = sessions.data?.filter(s => s.status === 'IN_PROGRESS').length
  const run = useMutation({
    mutationFn: () => locked ? integrityApi.unlock(exam.id) : integrityApi.lock(exam.id, reason.trim()),
    onSuccess: async r => {
      await Promise.all(['exam', 'exams', 'exam-sessions', 'exam-audit', 'integrity'].map(k => qc.invalidateQueries({ queryKey: [k] })))
      onDone(r.unchanged ? (r.locked ? t('Kỳ thi đã ở trạng thái khóa.') : t('Kỳ thi đã được mở khóa trước đó.'))
        : r.locked ? t('Đã khóa kỳ thi. {0} lượt đang làm đã được nộp; không nhận lượt bắt đầu mới.', { 0: r.activeSessionsSubmitted })
        : t('Đã mở khóa. Thí sinh còn lượt có thể bắt đầu lại; bài đã nộp không được khôi phục.'))
    },
  })
  const ready = locked || reason.trim() !== ''
  return (
    <Dialog title={locked ? t('Mở khóa kỳ thi?') : t('Khóa toàn bộ kỳ thi?')} onClose={onClose} busy={run.isPending}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={run.isPending} data-autofocus>{t('Hủy')}</button>
        <button type="button" className={`btn ${locked ? 'btn-primary' : 'btn-danger'}`} disabled={!ready || run.isPending || (!locked && sessions.isLoading)} aria-busy={run.isPending} onClick={() => run.mutate()}>
          {run.isPending ? t('Đang xử lý…') : locked ? t('Mở khóa') : t('Khóa và nộp bài')}
        </button>
      </>}>
      <div className="stack">
        {run.isError && <Notice tone="danger" live>{errorText(run.error)}</Notice>}
        {locked ? <p>{t('Thí sinh còn lượt sẽ bắt đầu được lượt mới. Mở khóa')} <strong>{t('không khôi phục')}</strong> {t('các bài đã bị nộp khi khóa.')}</p> : <>
          <Notice tone="danger" title={sessions.isLoading ? t('Đang đếm phiên đang làm…') : sessions.isError ? t('Chưa đếm được phiên đang làm') : t('{0} phiên đang làm sẽ bị nộp ngay', { 0: active })}>
            {t('Khóa chặn mọi lượt bắt đầu mới và tự động nộp tất cả lượt đang làm. Mở khóa sau đó không khôi phục các bài đã nộp.')}
          </Notice>
          <Field label={t('Lý do khóa')} htmlFor="integrity-lock-reason" required hint={t('Ghi vào nhật ký kỳ thi.')}>
            <textarea id="integrity-lock-reason" className="input" rows={3} maxLength={500} value={reason} onChange={e => setReason(e.target.value)} />
          </Field>
        </>}
      </div>
    </Dialog>
  )
}
