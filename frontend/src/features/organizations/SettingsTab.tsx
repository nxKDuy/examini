import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { orgApi, type Organization } from './orgApi'
import { canManageOrg, isStaleRevision, memberName, ROLE_LABEL, transferTargets } from './orgModel'
import TypedConfirm from './TypedConfirm'
import { ConfirmDialog } from '../../components/Dialog'
import { Field, LoadingState, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { t, useT } from '../../lib/i18n'

/** OWNER only. Rename (revisioned), transfer ownership, archive. Archived organizations show this read-only: there is no unarchive. */
export default function SettingsTab({ org, onLeft }: { org: Organization; onLeft: () => void }) {
  useT()
  const qc = useQueryClient()
  const editable = canManageOrg(org)
  const [name, setName] = useState(org.name)
  const [description, setDescription] = useState(org.description ?? '')
  const baseRev = useRef(org.revision)
  const [stale, setStale] = useState(false)
  const [flash, setFlash] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const [dialog, setDialog] = useState<'archive' | 'transfer' | 'reload' | null>(null)
  const [target, setTarget] = useState('')
  const dirty = name.trim() !== org.name || description.trim() !== (org.description ?? '')

  // A newer revision replaces the form only when nothing is unsaved; otherwise it only raises the stale warning.
  useEffect(() => {
    if (org.revision === baseRev.current) return
    if (dirty) setStale(true); else { baseRev.current = org.revision; setName(org.name); setDescription(org.description ?? '') }
  }, [org, dirty])

  const members = useQuery({ queryKey: ['organizations', 'members', org.id, 'ACTIVE', '', 'transfer'], queryFn: () => orgApi.members(org.id, { status: 'ACTIVE', size: 50 }), enabled: editable })
  const targets = transferTargets(members.data?.items ?? [], org.me?.membershipId)
  const refresh = () => Promise.all([qc.invalidateQueries({ queryKey: ['organizations'] })])
  const fail = (e: unknown) => { setDialog(null); if (isStaleRevision(e)) { setStale(true); setFlash(null) } else setFlash({ tone: 'danger', text: errorText(e) }) }

  const save = useMutation({
    mutationFn: () => orgApi.update(org.id, { name: name.trim(), description: description.trim(), revision: org.revision }),
    onSuccess: async o => { baseRev.current = o.revision; setStale(false); setFlash({ tone: 'success', text: t('Đã lưu thông tin tổ chức.') }); qc.setQueryData(['organizations', 'detail', org.id], (prev: Organization | undefined) => (prev ? { ...prev, ...o, me: prev.me } : o)); await refresh() },
    onError: fail,
  })
  const archive = useMutation({
    mutationFn: () => orgApi.archive(org.id, org.revision),
    onSuccess: async () => { setDialog(null); setFlash({ tone: 'success', text: t('Đã lưu trữ tổ chức. Từ giờ chỉ còn xem lịch sử.') }); await refresh() },
    onError: fail,
  })
  const transfer = useMutation({
    mutationFn: () => orgApi.transfer(org.id, target, org.revision),
    onSuccess: async () => { setDialog(null); setTarget(''); setFlash({ tone: 'success', text: t('Đã chuyển quyền sở hữu. Bạn hiện là Quản trị.') }); await refresh(); onLeft() },
    onError: fail,
  })
  async function reload() { setDialog(null); const fresh = await qc.fetchQuery({ queryKey: ['organizations', 'detail', org.id], queryFn: () => orgApi.get(org.id), staleTime: 0 }); baseRev.current = fresh.revision; setName(fresh.name); setDescription(fresh.description ?? ''); setStale(false); setFlash(null) }
  const targetMember = targets.find(m => m.membershipId === target)
  const invalid = name.trim() === '' || name.trim().length > 120 || description.length > 1000
  function submit(e: FormEvent) { e.preventDefault(); if (!invalid && dirty && !stale && !save.isPending) save.mutate() }

  if (!editable) return <Notice tone="info">{org.status === 'ARCHIVED' ? t('Tổ chức đã lưu trữ nên chỉ xem lịch sử. Không có thao tác khôi phục.') : t('Chỉ Chủ sở hữu mới chỉnh cài đặt tổ chức.')}</Notice>
  return (
    <div className="stack-lg">
      {flash && <Notice tone={flash.tone} live>{flash.text}</Notice>}
      {stale && <Notice tone="warning" live title={t('Tổ chức vừa được cập nhật ở nơi khác')} action={<button type="button" className="btn btn-secondary btn-sm" onClick={() => (dirty ? setDialog('reload') : void reload())}>{t('Tải lại bản mới')}</button>}>
        {t('Thay đổi của bạn chưa được gửi và chưa bị mất. Tải lại sẽ thay bằng bản mới nhất.')}</Notice>}
      <form onSubmit={submit} className="card card-body stack" aria-label={t('Thông tin tổ chức')}>
        <h2>{t('Thông tin tổ chức')}</h2>
        <Field label={t('Tên tổ chức')} htmlFor="set-name" required><input id="set-name" className="input" value={name} maxLength={120} disabled={save.isPending} onChange={e => setName(e.target.value)} /></Field>
        <Field label={t('Mô tả')} htmlFor="set-desc"><textarea id="set-desc" className="input" rows={3} value={description} maxLength={1000} disabled={save.isPending} onChange={e => setDescription(e.target.value)} /></Field>
        <div><button type="submit" className="btn btn-primary" disabled={invalid || !dirty || stale || save.isPending}>{save.isPending ? t('Đang lưu…') : t('Lưu thông tin')}</button></div>
      </form>

      <section className="org-danger" aria-label={t('Chuyển quyền sở hữu')}>
        <h2>{t('Chuyển quyền sở hữu')}</h2>
        <p className="small">{t('Chủ sở hữu mới nhận toàn quyền; bạn trở thành Quản trị. Tổ chức luôn có đúng một Chủ sở hữu và bạn không thể rời tổ chức trước khi chuyển quyền.')}</p>
        {members.isLoading ? <LoadingState rows={1} /> : (
          <Field label={t('Người nhận quyền')} htmlFor="transfer-target" hint={targets.length === 0 ? t('Chưa có thành viên đang hoạt động để nhận quyền. Hãy mời họ trước.') : (members.data?.totalElements ?? 0) > 50 ? t('Chỉ hiện 50 thành viên đầu tiên.') : undefined}>
            <select id="transfer-target" className="input" value={target} onChange={e => setTarget(e.target.value)} disabled={targets.length === 0}>
              <option value="">{t('Chọn thành viên')}</option>
              {targets.map(m => <option key={m.membershipId} value={m.membershipId}>{memberName(m).text} ({ROLE_LABEL[m.role]})</option>)}
            </select>
          </Field>
        )}
        <div><button type="button" className="btn btn-danger" disabled={!target || stale} onClick={() => { setFlash(null); setDialog('transfer') }}>{t('Chuyển quyền sở hữu')}</button></div>
      </section>

      <section className="org-danger" aria-label={t('Lưu trữ tổ chức')}>
        <h2>{t('Lưu trữ tổ chức')}</h2>
        <p className="small">{t('Sau khi lưu trữ, tổ chức chỉ còn đọc lịch sử: không mời, không đổi quyền, không dùng ngân hàng câu hỏi mới. Không có thao tác khôi phục.')}</p>
        <div><button type="button" className="btn btn-danger" disabled={stale} onClick={() => { setFlash(null); setDialog('archive') }}>{t('Lưu trữ tổ chức')}</button></div>
      </section>

      {dialog === 'archive' && <TypedConfirm title={t('Lưu trữ tổ chức?')} name={org.name} confirmLabel={t('Lưu trữ tổ chức')} busy={archive.isPending} onConfirm={() => archive.mutate()} onCancel={() => setDialog(null)}
        description={t('Hành động này không hoàn tác được. Tổ chức chuyển sang chỉ đọc; lịch sử và nội dung đã có được giữ lại.')} />}
      {dialog === 'transfer' && targetMember && <TypedConfirm title={t('Chuyển quyền sở hữu?')} name={org.name} confirmLabel={t('Chuyển quyền')} busy={transfer.isPending} onConfirm={() => transfer.mutate()} onCancel={() => setDialog(null)}
        description={t('{0} sẽ trở thành Chủ sở hữu; bạn trở thành Quản trị và không thể tự lấy lại quyền.', { 0: memberName(targetMember).text })} />}
      {dialog === 'reload' && <ConfirmDialog title={t('Tải lại bản mới nhất?')} tone="danger" confirmLabel={t('Tải lại, bỏ thay đổi')} onConfirm={() => void reload()} onCancel={() => setDialog(null)} description={t('Các thay đổi chưa lưu trong biểu mẫu sẽ bị thay bằng bản mới nhất.')} />}
    </div>
  )
}
