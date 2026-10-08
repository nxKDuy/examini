import { useState } from 'react'
import { Dialog } from '../../components/Dialog'
import { Field, Notice } from '../../components/ui'
import { nameMatches } from './orgModel'

/** Irreversible or high-impact action: the exact organization name must be typed again. */
export default function TypedConfirm({ title, description, name, confirmLabel, busy, error, onConfirm, onCancel, children }: {
  title: string; description: string; name: string; confirmLabel: string; busy?: boolean; error?: string | null
  onConfirm: () => void; onCancel: () => void; children?: React.ReactNode
}) {
  const [typed, setTyped] = useState('')
  const ok = nameMatches(typed, name)
  return (
    <Dialog title={title} description={description} onClose={onCancel} busy={busy}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onCancel} disabled={busy}>Hủy</button>
        <button type="button" className="btn btn-danger" onClick={onConfirm} disabled={!ok || busy} aria-busy={busy}>{busy ? 'Đang xử lý…' : confirmLabel}</button>
      </>}>
      <div className="stack">
        {error && <Notice tone="danger" live>{error}</Notice>}
        {children}
        <Field label={<>Gõ lại tên tổ chức <strong>{name}</strong> để xác nhận</>} htmlFor="typed-confirm">
          <input id="typed-confirm" className="input" value={typed} autoComplete="off" onChange={e => setTyped(e.target.value)} data-autofocus />
        </Field>
      </div>
    </Dialog>
  )
}
