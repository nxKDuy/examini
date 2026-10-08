import { useEffect, useId } from 'react'
import { useQuery } from '@tanstack/react-query'
import { questionBankApi } from './questionApi'

/** Subject -> category pickers. Category list loads per subject; an existing category resolves its subject. */
export default function SubjectCategoryFields({ subjectId, categoryId, onSubject, onCategory, allowAll, required, disabled }: {
  subjectId: string; categoryId: string; onSubject: (id: string) => void; onCategory: (id: string) => void
  allowAll?: boolean; required?: boolean; disabled?: boolean
}) {
  const sid = useId(), cid = useId()
  const subjects = useQuery({ queryKey: ['subjects'], queryFn: questionBankApi.getSubjects, staleTime: 300_000 })
  const resolved = useQuery({
    queryKey: ['category', categoryId], queryFn: () => questionBankApi.getCategory(categoryId),
    enabled: !!categoryId && !subjectId, staleTime: 300_000,
  })
  useEffect(() => { if (!subjectId && resolved.data?.subjectId) onSubject(resolved.data.subjectId) }, [subjectId, resolved.data, onSubject])
  const categories = useQuery({
    queryKey: ['categories', subjectId], queryFn: () => questionBankApi.getCategories(subjectId), enabled: !!subjectId, staleTime: 300_000,
  })
  return (
    <>
      <div className="field">
        <label className="field-label" htmlFor={sid}>Môn học{required && <span aria-hidden style={{ color: 'var(--color-danger)' }}> *</span>}</label>
        <select id={sid} className="input" value={subjectId} disabled={disabled} required={required}
          onChange={e => { onSubject(e.target.value); onCategory('') }}>
          <option value="">{allowAll ? 'Tất cả môn học' : 'Chọn môn học'}</option>
          {subjects.data?.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
        {subjects.isError && <span className="field-error">Không tải được danh sách môn học.</span>}
        {subjects.data?.length === 0 && <span className="field-hint">Chưa có môn học nào trong hệ thống.</span>}
      </div>
      <div className="field">
        <label className="field-label" htmlFor={cid}>Danh mục{required && <span aria-hidden style={{ color: 'var(--color-danger)' }}> *</span>}</label>
        <select id={cid} className="input" value={categoryId} disabled={disabled || !subjectId} required={required}
          onChange={e => onCategory(e.target.value)}>
          <option value="">{allowAll ? 'Tất cả danh mục' : subjectId ? 'Chọn danh mục' : 'Chọn môn học trước'}</option>
          {categories.data?.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        {categories.isError && <span className="field-error">Không tải được danh mục.</span>}
      </div>
    </>
  )
}
