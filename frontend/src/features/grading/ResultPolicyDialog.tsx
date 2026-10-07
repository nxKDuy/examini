import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Dialog } from '../../components/Dialog'
import { Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { gradingApi, type ResultPolicy } from './gradingApi'

const ROWS: { key: keyof ResultPolicy; label: string; hint?: string; disabled?: string }[] = [
  { key: 'autoRelease', label: 'Tự công bố sau khi hoàn tất chấm', hint: 'Tắt: giáo viên bấm Công bố riêng cho từng bài.' },
  { key: 'showScore', label: 'Hiện điểm tổng' },
  { key: 'showQuestionBreakdown', label: 'Hiện điểm từng câu' },
  { key: 'showFeedback', label: 'Hiện nhận xét' },
  { key: 'showCorrectAnswers', label: 'Hiện đáp án đúng', hint: 'Chỉ có hiệu lực khi kỳ thi đã đóng; máy chủ kiểm tra lại.' },
  { key: 'showExplanation', label: 'Hiện giải thích', disabled: 'Chưa hỗ trợ: đề đã đóng băng chưa có giải thích.' },
]

/** Result visibility for one Exam. The server enforces every field; this only edits the booleans it stores. */
export default function ResultPolicyDialog({ examId, policy, onClose }: { examId: string; policy: ResultPolicy; onClose: () => void }) {
  const qc = useQueryClient()
  const [draft, setDraft] = useState<ResultPolicy>(policy)
  const save = useMutation({
    mutationFn: () => gradingApi.savePolicy(examId, draft),
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: ['grading', 'policy', examId] }); onClose() },
  })
  return (
    <Dialog title="Chính sách hiển thị kết quả" description="Áp dụng cho mọi bài của kỳ thi này." onClose={onClose} busy={save.isPending}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={save.isPending}>Hủy</button>
        <button type="button" className="btn btn-primary" onClick={() => save.mutate()} disabled={save.isPending}>{save.isPending ? 'Đang lưu…' : 'Lưu chính sách'}</button>
      </>}>
      <div className="stack">
        {save.isError && <Notice tone="danger" live>{errorText(save.error)}</Notice>}
        {ROWS.map(r => (
          <label key={r.key} className="row" style={{ alignItems: 'flex-start', gap: 10 }}>
            <input type="checkbox" checked={r.disabled ? false : draft[r.key]} disabled={!!r.disabled || save.isPending} onChange={e => setDraft({ ...draft, [r.key]: e.target.checked })} style={{ marginTop: 4 }} />
            <span><span>{r.label}</span><span className="field-hint" style={{ display: 'block' }}>{r.disabled ?? r.hint}</span></span>
          </label>
        ))}
      </div>
    </Dialog>
  )
}
