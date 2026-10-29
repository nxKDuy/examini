import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { importApi, type Difficulty } from './importApi'
import { ACCEPT, fileProblem } from './importModel'
import SubjectCategoryFields from '../question-bank/SubjectCategoryFields'
import { Dialog } from '../../components/Dialog'
import { Field, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL } from '../../lib/format'
import { t, useT } from '../../lib/i18n'
import Select from '../../components/Select'

/** Upload step: file + optional default category/difficulty; processing continues on the job page. */
export default function ImportUploadDialog({ onClose }: { onClose: () => void }) {
  useT()
  const navigate = useNavigate()
  const [file, setFile] = useState<File | null>(null)
  const [subjectId, setSubjectId] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [difficulty, setDifficulty] = useState<Difficulty>('MEDIUM')
  const [touched, setTouched] = useState(false)
  const problem = fileProblem(file)
  const upload = useMutation({
    mutationFn: () => importApi.upload(file!, { categoryId: categoryId || undefined, defaultDifficulty: difficulty }),
    onSuccess: job => navigate(`/questions/imports/${job.id}`),
  })
  return (
    <Dialog title={t('Nhập câu hỏi từ tài liệu')} onClose={onClose} busy={upload.isPending}
      description={t('DOCX, PDF có chữ, PDF scan hoặc ảnh PNG/JPG; tối đa 20 MB và 30 trang. Ảnh và trang scan được nhận dạng chữ (OCR) trên máy chủ. Câu được tạo dưới dạng bản nháp sau khi bạn xem lại.')}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={upload.isPending}>{t('Hủy')}</button>
        <button type="button" className="btn btn-primary" disabled={upload.isPending} aria-busy={upload.isPending}
          onClick={() => { setTouched(true); if (!problem) upload.mutate() }}>{upload.isPending ? t('Đang tải lên…') : t('Tải lên và xử lý')}</button>
      </>}>
      <div className="stack">
        {upload.isError && <Notice tone="danger" live>{errorText(upload.error)}</Notice>}
        <Field label={t('Tệp tài liệu')} htmlFor="import-file" required error={touched ? problem ?? undefined : undefined}
          hint={t('Mẫu nhận dạng: “Câu 1: …”, các dòng “A. …”, “Đáp án: B”, “Giải thích: …”, “Mức độ: Dễ”.')}>
          <input id="import-file" type="file" className="input" accept={ACCEPT} onChange={e => setFile(e.target.files?.[0] ?? null)} data-autofocus />
        </Field>
        <div className="grid-2">
          <SubjectCategoryFields subjectId={subjectId} categoryId={categoryId} onSubject={id => { setSubjectId(id); setCategoryId('') }} onCategory={setCategoryId} allowAll />
        </div>
        <p className="xsmall muted">{t('Danh mục áp dụng cho mọi câu trong lô (sửa được từng câu). Bỏ trống thì câu chưa hợp lệ cho đến khi bạn chọn danh mục.')}</p>
        <Field label={t('Độ khó mặc định')} htmlFor="import-diff" hint={t('Dùng khi tài liệu không ghi “Mức độ”.')}>
          <Select id="import-diff" className="input" style={{ maxWidth: 220 }} value={difficulty} onChange={e => setDifficulty(e.target.value as Difficulty)}>
            {Object.entries(DIFFICULTY_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </Select>
        </Field>
      </div>
    </Dialog>
  )
}
