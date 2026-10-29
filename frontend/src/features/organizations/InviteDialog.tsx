import { useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Dialog } from '../../components/Dialog'
import { Field, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { orgApi, type InviteRole, type Organization } from './orgApi'
import { ROLE_LABEL, inviteRolesFor, parseRecipient } from './orgModel'
import { t, useT } from '../../lib/i18n'
import Select from '../../components/Select'

/** One box for a user id or an exact e-mail. ADMIN may only invite MEMBER. Duplicate pending invites answer 200 and are reported as such. */
export default function InviteDialog({ org, onDone, onClose }: { org: Organization; onDone: (created: boolean) => void; onClose: () => void }) {
  useT()
  const qc = useQueryClient()
  const roles = inviteRolesFor(org.me?.role)
  const [value, setValue] = useState('')
  const [role, setRole] = useState<InviteRole>('MEMBER')
  const [touched, setTouched] = useState(false)
  const recipient = parseRecipient(value)
  const invite = useMutation({
    mutationFn: () => orgApi.invite(org.id, { ...recipient!, role }),
    onSuccess: async r => { await qc.invalidateQueries({ queryKey: ['organizations', 'invitations', org.id] }); onDone(r.created) },
  })
  function submit(e: FormEvent) { e.preventDefault(); setTouched(true); if (recipient && !invite.isPending) invite.mutate() }
  return (
    <Dialog title={t('Mời vào tổ chức')} description={t('Người được mời phải đã có tài khoản EXAMINI. Lời mời hết hạn sau 7 ngày và không tự thêm vào tổ chức.')} onClose={onClose} busy={invite.isPending}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={invite.isPending}>{t('Hủy')}</button>
        <button type="submit" form="org-invite-form" className="btn btn-primary" disabled={invite.isPending}>{invite.isPending ? t('Đang gửi…') : t('Gửi lời mời')}</button>
      </>}>
      <form id="org-invite-form" onSubmit={submit} className="stack">
        {invite.isError && <Notice tone="danger" live>{errorText(invite.error)}</Notice>}
        <Field label={t('Mã người dùng hoặc email')} htmlFor="invite-recipient" required hint={t('Dán mã người dùng (UUID) hoặc email đúng như đã đăng ký.')}
          error={touched && !recipient ? t('Nhập mã người dùng (UUID) hoặc một email hợp lệ.') : undefined}>
          <input id="invite-recipient" className="input" value={value} autoComplete="off" spellCheck={false} aria-invalid={touched && !recipient} onChange={e => setValue(e.target.value)} data-autofocus />
        </Field>
        <Field label={t('Vai trò')} htmlFor="invite-role" hint={roles.length === 1 ? t('Quản trị chỉ mời được thành viên thường.') : t('Chủ sở hữu có thể mời cả Quản trị.')}>
          <Select id="invite-role" className="input" value={role} onChange={e => setRole(e.target.value as InviteRole)}>
            {roles.map(r => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
          </Select>
        </Field>
      </form>
    </Dialog>
  )
}
