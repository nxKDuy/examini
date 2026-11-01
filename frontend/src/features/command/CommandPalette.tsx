import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { CornerDownLeft, Search } from 'lucide-react'
import { navigationItems, type Audience } from '../../components/layout/navigation'
import { examApi } from '../exams/examApi'
import { rank, shortcutLabel, type Command } from './commandModel'
import { t, useT } from '../../lib/i18n'
import './command.css'


function actions(audience: Audience): Command[] {
  const g = t('Thao tác')
  if (audience === 'TEACHER') return [
    { id: 'a-ai', group: g, label: t('Tạo câu hỏi bằng AI'), to: '/questions/imports?new=ai', keywords: 'sinh de ai gemini' },
    { id: 'a-import', group: g, label: t('Nhập đề có sẵn'), hint: 'Word, PDF, Excel', to: '/questions/imports', keywords: 'tai lieu upload' },
    { id: 'a-exam', group: g, label: t('Tạo đề thi'), to: '/exams/create', keywords: 'de moi ma tran' },
    { id: 'a-grade', group: g, label: t('Bài chờ chấm'), to: '/grading', keywords: 'cham tu luan' },
  ]
  if (audience === 'STUDENT') return [
    { id: 'a-code', group: g, label: t('Nhập mã tham gia kỳ thi'), to: '/dashboard', keywords: 'ma de join' },
    { id: 'a-discover', group: g, label: t('Kỳ thi công khai'), to: '/exam/lobby/discover', keywords: 'public' },
  ]
  return []
}

/** Ctrl/⌘+K or "/" anywhere (outside text fields) opens it; ↑ ↓ choose, Enter goes, Esc closes. */
export default function CommandPalette({ audience }: { audience: Audience }) {
  useT()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const input = useRef<HTMLInputElement>(null)
  const back = useRef<HTMLElement | null>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const typing = (e.target as HTMLElement).closest('input, textarea, select, [contenteditable=true]')
      if ((e.key.toLowerCase() === 'k' && (e.ctrlKey || e.metaKey)) || (e.key === '/' && !typing && !e.ctrlKey && !e.metaKey && !e.altKey)) {
        if (document.querySelector('.take-shell')) return // never during an exam
        e.preventDefault(); back.current = document.activeElement as HTMLElement; setOpen(o => !o)
      }
    }
    const onOpen = () => { back.current = document.activeElement as HTMLElement; setOpen(true) }
    window.addEventListener('keydown', onKey)
    window.addEventListener('examini:command', onOpen)
    return () => { window.removeEventListener('keydown', onKey); window.removeEventListener('examini:command', onOpen) }
  }, [])
  useEffect(() => { if (open) { setQuery(''); setActive(0) } else back.current?.focus?.() }, [open])

  // Same cache entries as the dashboards, so the list is usually already there when the palette opens.
  const teacherExams = useQuery({ queryKey: ['exams', 'list', 0], queryFn: () => examApi.list(0), enabled: audience === 'TEACHER', staleTime: 60_000 })
  const studentExams = useQuery({ queryKey: ['my-exams'], queryFn: examApi.my, enabled: audience === 'STUDENT', staleTime: 60_000 })
  const exams = { data: audience === 'TEACHER' ? teacherExams.data?.content : studentExams.data, isLoading: teacherExams.isLoading || studentExams.isLoading }
  const commands = useMemo<Command[]>(() => [
    ...navigationItems(audience).map(n => ({ id: 'n' + n.to, group: t('Trang'), label: n.label, to: n.to })),
    ...actions(audience),
    { id: 'n/notifications', group: t('Trang'), label: t('Thông báo'), to: '/notifications', keywords: 'chuong bell' },
    { id: 'n/profile', group: t('Trang'), label: t('Hồ sơ cá nhân'), to: '/profile', keywords: 'tai khoan mat khau' },
    ...(exams.data ?? []).map(e => ({ id: 'e' + e.id, group: t('Kỳ thi'), label: e.title, hint: e.code ? t('Mã {0}', { 0: e.code }) : undefined, keywords: e.code ?? '',
      to: audience === 'TEACHER' ? `/exams/${e.id}` : `/exam/lobby/${e.id}` })),
  ], [audience, exams.data])
  const results = rank(commands, query)
  useEffect(() => { setActive(0) }, [query])

  if (!open) return null
  const go = (c?: Command) => { if (!c) return; setOpen(false); navigate(c.to) }
  return (
    <div className="cmd-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) setOpen(false) }}>
      <div className="cmd" role="dialog" aria-modal="true" aria-label={t('Tìm nhanh')}>
        <div className="cmd-input">
          <Search size={18} aria-hidden />
          <input ref={input} autoFocus value={query} onChange={e => setQuery(e.target.value)} placeholder={t('Tìm trang, thao tác hoặc kỳ thi…')}
            role="combobox" aria-expanded aria-controls="cmd-list" aria-activedescendant={results[active] ? `cmd-${results[active].id}` : undefined}
            onKeyDown={e => {
              if (e.key === 'ArrowDown') { e.preventDefault(); setActive(a => Math.min(results.length - 1, a + 1)) }
              else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(a => Math.max(0, a - 1)) }
              else if (e.key === 'Enter') { e.preventDefault(); go(results[active]) }
              else if (e.key === 'Escape') { e.preventDefault(); setOpen(false) }
            }} />
          <kbd>Esc</kbd>
        </div>
        <ul id="cmd-list" role="listbox" className="cmd-list">
          {results.length === 0 && <li className="cmd-empty">{exams.isLoading ? t('Đang tải…') : t('Không tìm thấy “{0}”', { 0: query })}</li>}
          {results.map((c, i) => (
            <li key={c.id} id={`cmd-${c.id}`} role="option" aria-selected={i === active} className="cmd-item"
              onMouseEnter={() => setActive(i)} onMouseDown={e => { e.preventDefault(); go(c) }}>
              <span className="cmd-group">{c.group}</span>
              <span className="cmd-label">{c.label}{c.hint && <small> · {c.hint}</small>}</span>
              {i === active && <CornerDownLeft size={14} aria-hidden />}
            </li>
          ))}
        </ul>
        <div className="cmd-foot xsmall muted">{t('↑ ↓ chọn · Enter mở · {0} hoặc / để mở lại', { 0: shortcutLabel() })}</div>
      </div>
    </div>
  )
}
