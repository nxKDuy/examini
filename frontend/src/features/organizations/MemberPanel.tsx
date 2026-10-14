import { useEffect, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Copy, X } from 'lucide-react'
import { orgApi, type InviteRole, type Member, type Organization } from './orgApi'
import { CAPABILITY_LABEL, MEMBER_STATUS_LABEL, ROLE_LABEL, isStaleRevision, memberAbilities, memberName, memberPatch, type MemberEdit } from './orgModel'
import { ConfirmDialog } from '../../components/Dialog'
import { useModalFocus } from '../../components/useModalFocus'
import { Badge, Field, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { t, useT } from '../../lib/i18n'

const fromMember = (m: Member): MemberEdit => ({ role: m.role, canReview: m.canReview })

/** Side drawer on desktop, bottom sheet on narrow screens. Edits are saved with an explicit button and the member's revision. */
export default function MemberPanel({ org, member, narrow, onClose, onReload }: {
  org: Organization; member: Member; narrow: boolean; onClose: () => void; onReload: () => Promise<Member | undefined>
}) {
  useT()
  const qc = useQueryClient()
  const panelRef = useRef<HTMLElement>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const abilities = memberAbilities({ role: org.me?.role, membershipId: org.me?.membershipId }, member, org.status === 'ARCHIVED')
  const [edit, setEdit] = useState<MemberEdit>(() => fromMember(member))
  const baseRev = useRef(member.revision)
  const [stale, setStale] = useState(false)
  const [flash, setFlash] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const [confirm, setConfirm] = useState<'remove' | 'reload' | null>(null)
  const patch = memberPatch(member, edit)
  const dirty = patch !== null
  const name = memberName(member)

  useModalFocus(panelRef, { trap: narrow, escape: true, onEscape: onClose, initialFocus: () => headingRef.current })
  // A newer revision arriving while the form is clean replaces it; while there are unsaved edits it only raises the stale warning.
  useEffect(() => {
    if (member.revision === baseRev.current) return
    if (dirty) setStale(true); else { baseRev.current = member.revision; setEdit(fromMember(member)) }
  }, [member, dirty])

  const refresh = () => Promise.all([qc.invalidateQueries({ queryKey: ['organizations', 'members', org.id] }), qc.invalidateQueries({ queryKey: ['organizations', 'detail', org.id] })])
  const fail = (e: unknown) => { setConfirm(null); if (isStaleRevision(e)) { setStale(true); setFlash(null) } else setFlash({ tone: 'danger', text: errorText(e) }) }
  const save = useMutation({
    mutationFn: () => orgApi.updateMember(org.id, member.membershipId, patch!),
    onSuccess: async saved => { baseRev.current = saved.revision; setEdit(fromMember(saved)); setStale(false); setFlash({ tone: 'success', text: t('Đã lưu thay đổi.') }); await refresh() },
    onError: fail,
  })
  const remove = useMutation({
    mutationFn: () => orgApi.removeMember(org.id, member.membershipId, member.revision),
    onSuccess: async () => { setConfirm(null); await refresh(); onClose() },
    onError: fail,
  })
  const busy = save.isPending || remove.isPending
  async function reload() { setConfirm(null); const fresh = await onReload(); if (fresh) { baseRev.current = fresh.revision; setEdit(fromMember(fresh)) } setStale(false); setFlash(null) }

  const isMemberRole = edit.role === 'MEMBER'
  return (
    <>
      {narrow && <button type="button" className="org-sheet-backdrop" aria-label={t('Đóng chi tiết thành viên')} onClick={onClose} tabIndex={-1} />}
      <aside ref={panelRef} className="org-panel" role={narrow ? 'dialog' : 'complementary'} aria-modal={narrow || undefined} aria-label={t('Chi tiết thành viên')}>
        <div className="org-panel-head">
          <div style={{ minWidth: 0 }}>
            <h2 ref={headingRef} tabIndex={-1} style={{ fontSize: '1.2rem', overflowWrap: 'anywhere' }}>{name.text}</h2>
            <p className="xsmall muted">{name.fallback ? t('Chưa có tên hiển thị. ') : ''}{t('Mã')} <span className="org-id">{member.userId}</span>{' '}
              <button type="button" className="btn btn-ghost btn-sm" aria-label={t('Sao chép mã người dùng')} onClick={() => { void navigator.clipboard?.writeText(member.userId).catch(() => undefined) }}><Copy size={12} aria-hidden /></button></p>
          </div>
          <button type="button" className="btn btn-ghost btn-icon" aria-label={t('Đóng')} onClick={onClose}><X size={18} /></button>
        </div>
        <div className="org-panel-body">
          {flash && <Notice tone={flash.tone} live>{flash.text}</Notice>}
          {stale && <Notice tone="warning" live title={t('Thành viên này vừa được cập nhật ở nơi khác')}
            action={<button type="button" className="btn btn-secondary btn-sm" onClick={() => (dirty ? setConfirm('reload') : void reload())}>{t('Tải lại bản mới')}</button>}>
            {t('Thay đổi của bạn chưa được gửi và chưa bị mất. Tải lại sẽ thay bằng bản mới nhất.')}</Notice>}
          <div className="row" style={{ gap: 6 }}>
            <Badge tone="info">{ROLE_LABEL[member.role]}</Badge>
            <Badge tone={member.status === 'ACTIVE' ? 'success' : 'muted'}>{MEMBER_STATUS_LABEL[member.status] ?? member.status}</Badge>
            {member.canReview && <Badge tone="neutral">{t('Được duyệt câu hỏi')}</Badge>}
          </div>
          <dl className="small stack-sm" style={{ margin: 0 }}>
            <div><dt className="xsmall muted">Tham gia</dt><dd style={{ margin: 0 }}>{formatDateTime(member.joinedAt, '-')}</dd></div>
            {member.endedAt && <div><dt className="xsmall muted">{t('Kết thúc')}</dt><dd style={{ margin: 0 }}>{formatDateTime(member.endedAt, '-')}</dd></div>}
          </dl>
          <div>
            <span className="field-label">{t('Quyền hiện có')}</span>
            {(member.capabilities?.length ?? 0) === 0 ? <p className="small muted">{t('Không có quyền nào.')}</p>
              : <ul className="org-caps">{member.capabilities!.map(c => <li key={c}>{CAPABILITY_LABEL[c] ?? c}</li>)}</ul>}
          </div>
          {abilities.note && <p className="xsmall muted">{abilities.note}</p>}
          {abilities.changeRole && (
            <Field label={t('Vai trò')} htmlFor="member-role">
              <select id="member-role" className="input" value={edit.role} disabled={busy} onChange={e => setEdit({ role: e.target.value as InviteRole, canReview: e.target.value === 'MEMBER' ? edit.canReview : false })}>
                <option value="MEMBER">{ROLE_LABEL.MEMBER}</option><option value="ADMIN">{ROLE_LABEL.ADMIN}</option>
              </select>
            </Field>
          )}
          {abilities.toggleReview && isMemberRole && (
            <label className="row" style={{ gap: 8, alignItems: 'flex-start' }}>
              <input type="checkbox" checked={edit.canReview} disabled={busy} onChange={e => setEdit({ ...edit, canReview: e.target.checked })} style={{ marginTop: 4 }} />
              <span>{t('Cho phép duyệt câu hỏi')}<span className="field-hint" style={{ display: 'block' }}>{t('Chỉ áp dụng cho thành viên thường; Quản trị đã có quyền duyệt. Không thể tự duyệt câu của mình.')}</span></span>
            </label>
          )}
          {abilities.any && (
            <div className="stack-sm">
              {abilities.changeRole || abilities.toggleReview
                ? <button type="button" className="btn btn-primary" disabled={!dirty || stale || busy} onClick={() => save.mutate()}>{save.isPending ? t('Đang lưu…') : t('Lưu thay đổi')}</button> : null}
              {abilities.remove && <button type="button" className="btn btn-danger-ghost" disabled={busy} onClick={() => setConfirm('remove')}>{t('Gỡ khỏi tổ chức')}</button>}
            </div>
          )}
        </div>
      </aside>
      {confirm === 'remove' && <ConfirmDialog title={t('Gỡ {0} khỏi tổ chức?', { 0: name.text })} tone="danger" confirmLabel={t('Gỡ khỏi tổ chức')} busy={remove.isPending} onConfirm={() => remove.mutate()} onCancel={() => setConfirm(null)}
        description={t('Họ mất quyền ngay ở lần kiểm tra quyền kế tiếp. Lịch sử và nội dung đã đóng góp được giữ lại. Muốn họ quay lại cần mời lại.')} />}
      {confirm === 'reload' && <ConfirmDialog title={t('Tải lại bản mới nhất?')} tone="danger" confirmLabel={t('Tải lại, bỏ thay đổi')} onConfirm={() => void reload()} onCancel={() => setConfirm(null)}
        description={t('Các thay đổi chưa lưu trong khung này sẽ bị thay bằng bản mới nhất.')} />}
    </>
  )
}
