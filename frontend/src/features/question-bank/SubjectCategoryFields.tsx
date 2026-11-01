import { useEffect, useId } from 'react'
import { useQuery } from '@tanstack/react-query'
import { questionBankApi } from './questionApi'
import { t, useT } from '../../lib/i18n'
import Select from '../../components/Select'

/** Subject -> category pickers. Category list loads per subject; an existing category resolves its subject. */
export default function SubjectCategoryFields({ subjectId, categoryId, onSubject, onCategory, allowAll, required, disabled }: {
  subjectId: string; categoryId: string; onSubject: (id: string) => void; onCategory: (id: string) => void
  allowAll?: boolean; required?: boolean; disabled?: boolean
}) {
  useT()
  const sid = useId(), cid = useId()
  const subjects = useQuery({ queryKey: ['subjects'], queryFn: questionBankApi.getSubjects, staleTime: 300_000 })
  const resolved = useQuery({
    queryKey: ['category', categoryId], queryFn: () => questionBankApi.getCategory(categoryId),
    enabled: !!categoryId && !subjectId, staleTime: 300_000,
  })
  useEffect(() => { if (!subjectId && resolved.data?.subjectId) onSubject(resolved.data.subjectId) }, [subjectId, resolved.data, onSubject])
  // A preselected category is shown as loading until its subject is known, never as an empty "Choose subject".
  const resolving = !!categoryId && !subjectId && !resolved.isError
  const categories = useQuery({
    queryKey: ['categories', subjectId], queryFn: () => questionBankApi.getCategories(subjectId), enabled: !!subjectId, staleTime: 300_000,
  })
  return (
    <>
      <div className="field">
        <label className="field-label" htmlFor={sid}>{t('Môn học')}{required && <span aria-hidden style={{ color: 'var(--color-danger)' }}> *</span>}</label>
        <Select id={sid} className="input" value={subjectId} disabled={disabled || resolving} required={required}
          onChange={e => { onSubject(e.target.value); onCategory('') }}>
          <option value="">{resolving ? t('Đang tải…') : allowAll ? t('Tất cả môn học') : t('Chọn môn học')}</option>
          {subjects.data?.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
        </Select>
        {subjects.isError && <span className="field-error">{t('Không tải được danh sách môn học.')}</span>}
        {resolved.isError && <span className="field-error">{t('Không tìm thấy danh mục đã chọn; hãy chọn lại môn học và danh mục.')}</span>}
        {subjects.data?.length === 0 && <span className="field-hint">{t('Chưa có môn học nào trong hệ thống.')}</span>}
      </div>
      <div className="field">
        <label className="field-label" htmlFor={cid}>{t('Danh mục')}{required && <span aria-hidden style={{ color: 'var(--color-danger)' }}> *</span>}</label>
        <Select id={cid} className="input" value={categoryId} disabled={disabled || !subjectId} required={required}
          onChange={e => onCategory(e.target.value)}>
          <option value="">{resolving ? t('Đang tải…') : allowAll ? t('Tất cả danh mục') : subjectId ? t('Chọn danh mục') : t('Chọn môn học trước')}</option>
          {categories.data?.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </Select>
        {categories.isError && <span className="field-error">{t('Không tải được danh mục.')}</span>}
      </div>
    </>
  )
}
