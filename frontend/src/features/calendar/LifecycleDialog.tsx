import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ConfirmDialog } from '../../components/Dialog'
import { Field, Notice } from '../../components/ui'
import { examApi } from '../exams/examApi'
import { errorText } from '../../lib/errors'
import { t, useT, i18nMap } from '../../lib/i18n'

export type LifecycleKind = 'open' | 'close' | 'reopen'

const COPY: Record<LifecycleKind, { title: string; confirm: string; tone: 'primary' | 'danger' }> = i18nMap({
  open: { title: 'Mở kỳ thi?', confirm: 'Mở kỳ thi', tone: 'primary' },
  close: { title: 'Đóng kỳ thi?', confirm: 'Đóng kỳ thi', tone: 'danger' },
  reopen: { title: 'Mở lại kỳ thi?', confirm: 'Mở lại', tone: 'primary' },
})

/** Open / early close / reopen. Close before endAt and reopen need a reason; the backend enforces both. */
export default function LifecycleDialog({ kind, examId, examTitle, endAtMs, nowMs, onCancel, onDone }: {
  kind: LifecycleKind; examId: string; examTitle: string; endAtMs: number | null; nowMs: number; onCancel: () => void; onDone: (kind: LifecycleKind) => void
}) {
  useT()
  const qc = useQueryClient()
  const [reason, setReason] = useState('')
  const early = kind === 'close' && endAtMs !== null && nowMs < endAtMs
  const needsReason = kind === 'reopen' || early
  const showReason = needsReason || kind === 'close'
  const m = useMutation({
    mutationFn: async () => {
      const text = reason.trim() || undefined
      if (kind === 'open') await examApi.open(examId)
      else if (kind === 'close') await examApi.close(examId, text)
      else await examApi.reopen(examId, text ?? '')
    },
    onSuccess: async () => {
      await Promise.all(['calendar', 'schedule', 'exam', 'exams', 'exam-audit'].map(k => qc.invalidateQueries({ queryKey: [k] })))
      onDone(kind)
    },
  })
  const description = {
    open: t('Thí sinh đủ điều kiện có thể bắt đầu khi đến giờ bắt đầu (mở sớm không bỏ qua giờ bắt đầu). Kỳ thi đang mở chỉ có thể chuyển sang Đã đóng.'),
    close: early ? t('Đóng trước giờ kết thúc: không ai bắt đầu được lượt mới. Thí sinh đang làm giữ hạn nộp đã cấp. Nhập lý do (bắt buộc).') : t('Không ai bắt đầu được lượt mới. Thí sinh đang làm giữ hạn nộp đã cấp.'),
    reopen: t('Chỉ mở lại được trước giờ kết thúc (hoặc khi đề không có giờ kết thúc). Lượt đang làm cũ không đổi hạn nộp. Nhập lý do (bắt buộc).'),
  }[kind]
  return (
    <ConfirmDialog title={`${COPY[kind].title} ${examTitle ? `"${examTitle}"` : ''}`.trim()} confirmLabel={COPY[kind].confirm} tone={COPY[kind].tone} busy={m.isPending}
      description={description} disabled={needsReason && !reason.trim()} onConfirm={() => m.mutate()} onCancel={onCancel}>
      <div className="stack">
        {showReason && (
          <Field label={needsReason ? t('Lý do') : t('Lý do (tùy chọn)')} htmlFor="lc-reason" required={needsReason}>
            <textarea id="lc-reason" className="input" rows={3} maxLength={500} value={reason} onChange={e => setReason(e.target.value)} data-autofocus />
          </Field>
        )}
        {m.isError && <Notice tone="danger" live>{errorText(m.error)}</Notice>}
      </div>
    </ConfirmDialog>
  )
}
