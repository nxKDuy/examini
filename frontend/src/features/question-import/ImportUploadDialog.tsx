import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { Download, FileSpreadsheet, FileText } from 'lucide-react'
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
  const template = useMutation({
    mutationFn: async (kind: 'excel' | 'word') => {
      const blob = await importApi.template(kind)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url; a.download = kind === 'excel' ? 'mau-cau-hoi-examini.xlsx' : 'mau-cau-hoi-examini.docx'
      document.body.appendChild(a); a.click(); a.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 5000)
    },
  })
  const upload = useMutation({
    mutationFn: () => importApi.upload(file!, { categoryId: categoryId || undefined, defaultDifficulty: difficulty }),
    onSuccess: job => navigate(`/questions/imports/${job.id}`),
  })
  return (
    <Dialog title={t('Nhập câu hỏi từ tài liệu')} onClose={onClose} busy={upload.isPending}
      description={t('Excel (XLSX), CSV, Word (DOCX), PDF có chữ, PDF scan hoặc ảnh PNG/JPG; tối đa 20 MB và 30 trang. Ảnh và trang scan được nhận dạng chữ (OCR) trên máy chủ. Câu được tạo dưới dạng bản nháp sau khi bạn xem lại.')}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={upload.isPending}>{t('Hủy')}</button>
        <button type="button" className="btn btn-primary" disabled={upload.isPending} aria-busy={upload.isPending}
          onClick={() => { setTouched(true); if (!problem) upload.mutate() }}>{upload.isPending ? t('Đang tải lên…') : t('Tải lên và xử lý')}</button>
      </>}>
      <div className="stack">
        {upload.isError && <Notice tone="danger" live>{errorText(upload.error)}</Notice>}
        <div className="stack-sm">
          <span className="small">{t('Chưa có tệp? Tải file mẫu, điền câu hỏi rồi tải lên:')}</span>
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <button type="button" className="btn btn-secondary btn-sm" disabled={template.isPending} onClick={() => template.mutate('excel')}>
              <FileSpreadsheet size={14} aria-hidden /> {t('Mẫu Excel')} <Download size={14} aria-hidden /></button>
            <button type="button" className="btn btn-secondary btn-sm" disabled={template.isPending} onClick={() => template.mutate('word')}>
              <FileText size={14} aria-hidden /> {t('Mẫu Word')} <Download size={14} aria-hidden /></button>
          </div>
          {template.isError && <span className="field-error">{errorText(template.error)}</span>}
        </div>
        <details className="import-guide">
          <summary className="small" style={{ cursor: 'pointer' }}>{t('Hướng dẫn soạn tệp')}</summary>
          <div className="stack-sm small" style={{ marginTop: 8 }}>
            <p><strong>Excel / CSV:</strong> {t('mỗi dòng một câu. Cột: Câu hỏi, Loại (Trắc nghiệm, Đúng/Sai, Trả lời ngắn, Tự luận, Nói), Độ khó (Dễ, Trung bình, Khó), A–H (phương án), Đáp án (chữ cái, Đúng/Sai hoặc đáp án ngắn), Giải thích. CSV dùng dấu phẩy hoặc chấm phẩy, mã UTF-8.')}</p>
            <p><strong>Word / PDF:</strong> {t('mỗi câu bắt đầu bằng “Câu 1:”, phương án “A. …”, rồi “Đáp án: B”. Có thể thêm “Loại: Tự luận” hoặc “Loại: Nói”, “Mức độ: Khó”, “Giải thích: …”. Với câu Tự luận/Nói, dòng “Đáp án” được lưu làm gợi ý chấm.')}</p>
            <p><strong>{t('Ảnh / PDF scan:')}</strong> {t('chụp thẳng, rõ chữ; hệ thống nhận dạng chữ (OCR) nên hãy kiểm tra kỹ từng câu trước khi nhập.')}</p>
            <p className="muted">{t('Dòng thiếu đáp án hoặc sai định dạng không bị đoán: chúng được đánh dấu để bạn sửa ở bước xem lại. Hình ảnh và âm thanh thêm sau trong trình soạn câu hỏi.')}</p>
          </div>
        </details>
        <Field label={t('Tệp tài liệu')} htmlFor="import-file" required error={touched ? problem ?? undefined : undefined}
          hint={t('Excel/CSV theo cột của file mẫu; Word/PDF theo mẫu “Câu 1: …”, “A. …”, “Đáp án: B”.')}>
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
