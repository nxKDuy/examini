import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { KeyRound } from 'lucide-react'
import { examApi } from './examApi'
import { errorText } from '../../lib/errors'

export default function JoinCodeForm({ compact }: { compact?: boolean }) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!code.trim()) return
    setBusy(true); setError('')
    try {
      const exam = await examApi.joinCode(code.trim())
      await qc.invalidateQueries({ queryKey: ['my-exams'] })
      navigate(`/exam/lobby/${exam.id}`)
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="stack-sm" aria-label="Tham gia bằng mã">
      <label htmlFor={compact ? 'join-code-compact' : 'join-code'} className="field-label">Mã tham gia</label>
      <div className="row" style={{ flexWrap: 'nowrap' }}>
        <input id={compact ? 'join-code-compact' : 'join-code'} className="input" value={code} maxLength={50} autoComplete="off" spellCheck={false}
          onChange={e => setCode(e.target.value)} aria-invalid={!!error} aria-describedby={error ? 'join-code-error' : undefined} style={{ flex: 1 }} />
        <button type="submit" className="btn btn-primary" disabled={busy || !code.trim()}>
          <KeyRound size={16} aria-hidden /> {busy ? 'Đang kiểm tra…' : 'Tham gia'}
        </button>
      </div>
      {error ? <span id="join-code-error" className="field-error" role="alert">{error}</span>
        : <span className="field-hint">Nhập mã giáo viên cung cấp cho kỳ thi dùng mã tham gia.</span>}
    </form>
  )
}
