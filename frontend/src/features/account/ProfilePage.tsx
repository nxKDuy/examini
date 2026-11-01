import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Camera, KeyRound, Link2, Save, Trash2, Unlink } from 'lucide-react'
import { Avatar } from './Avatar'
import { accountApi, passwordProblem, toAvatarJpeg, useAvatarUrl, useProfile, type Profile } from './accountApi'
import { authApi } from '../auth/authApi'
import { GoogleButton } from '../auth/GoogleSignIn'
import { Badge, ErrorState, Field, LoadingState, Notice, PageHeader, Section } from '../../components/ui'
import { AUDIENCE_LABEL, audienceOf } from '../../components/layout/navigation'
import { useAuthStore } from '../../store/authStore'
import { errorInfo, errorText } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { t, useT } from '../../lib/i18n'

export default function ProfilePage() {
  useT()
  const profile = useProfile()
  return (
    <div className="stack-lg">
      <PageHeader title={t('Hồ sơ cá nhân')} description={t('Thông tin tài khoản, ảnh đại diện, mật khẩu và cách đăng nhập.')} />
      {profile.isLoading ? <LoadingState rows={4} /> : profile.isError ? <ErrorState error={profile.error} onRetry={() => void profile.refetch()} />
        : profile.data && <>
          <InfoSection profile={profile.data} />
          <div className="grid-2" style={{ alignItems: 'start' }}>
            <PasswordSection profile={profile.data} />
            <GoogleSection profile={profile.data} />
          </div>
        </>}
    </div>
  )
}

function useSaveProfile() {
  const qc = useQueryClient()
  const userId = useAuthStore(s => s.user?.id)
  return (p: Profile) => qc.setQueryData(['account', 'profile', userId], p)
}

function InfoSection({ profile }: { profile: Profile }) {
  useT()
  const save = useSaveProfile()
  const avatarUrl = useAvatarUrl(profile.avatarUpdatedAt)
  const file = useRef<HTMLInputElement>(null)
  const [form, setForm] = useState({ fullName: profile.fullName, phone: profile.phone ?? '', school: profile.school ?? '', bio: profile.bio ?? '' })
  const [saved, setSaved] = useState(false)
  useEffect(() => { setForm({ fullName: profile.fullName, phone: profile.phone ?? '', school: profile.school ?? '', bio: profile.bio ?? '' }) }, [profile.fullName, profile.phone, profile.school, profile.bio])
  const update = useMutation({ mutationFn: () => accountApi.update(form), onSuccess: p => { save(p); setSaved(true) } })
  const upload = useMutation({ mutationFn: async (f: File) => accountApi.setAvatar(await toAvatarJpeg(f)), onSuccess: save })
  const remove = useMutation({ mutationFn: accountApi.removeAvatar, onSuccess: save })
  const fields = update.error ? errorInfo(update.error).fields ?? {} : {}
  const dirty = form.fullName !== profile.fullName || form.phone !== (profile.phone ?? '') || form.school !== (profile.school ?? '') || form.bio !== (profile.bio ?? '')
  const audience = audienceOf(useAuthStore(s => s.user))

  function submit(e: FormEvent) { e.preventDefault(); setSaved(false); update.mutate() }

  return (
    <Section title={t('Thông tin cá nhân')}>
      <div className="profile-layout">
        <div className="stack-sm profile-avatar">
          <Avatar name={profile.fullName} url={avatarUrl} size={120} />
          <input ref={file} type="file" accept="image/jpeg,image/png,image/webp" hidden
            onChange={e => { const f = e.target.files?.[0]; if (f) upload.mutate(f); e.target.value = '' }} />
          <button type="button" className="btn btn-secondary btn-sm" disabled={upload.isPending} aria-busy={upload.isPending} onClick={() => file.current?.click()}>
            <Camera size={14} aria-hidden /> {upload.isPending ? t('Đang tải ảnh…') : t('Đổi ảnh')}</button>
          {profile.avatarUpdatedAt && <button type="button" className="btn btn-ghost btn-sm" disabled={remove.isPending} onClick={() => remove.mutate()}>
            <Trash2 size={14} aria-hidden /> {t('Xóa ảnh')}</button>}
          {(upload.error || remove.error) && <p className="field-error">{errorText(upload.error ?? remove.error)}</p>}
        </div>
        <form className="stack" onSubmit={submit} style={{ minWidth: 0 }}>
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <Badge tone="info">{t(AUDIENCE_LABEL[audience])}</Badge>
            {profile.googleLinked && <Badge tone="neutral">Google</Badge>}
          </div>
          <div className="grid-2" style={{ alignItems: 'start' }}>
            <Field label={t('Họ và tên')} htmlFor="pf-name" required error={fields.fullName}>
              <input id="pf-name" className="input" required maxLength={255} autoComplete="name" value={form.fullName} onChange={e => setForm(f => ({ ...f, fullName: e.target.value }))} />
            </Field>
            <Field label="Email" htmlFor="pf-email" hint={t('Email dùng để đăng nhập, không đổi được.')}>
              <input id="pf-email" className="input" value={profile.email} readOnly />
            </Field>
            <Field label={t('Số điện thoại')} htmlFor="pf-phone" error={fields.phone}>
              <input id="pf-phone" className="input" type="tel" inputMode="tel" maxLength={30} autoComplete="tel" value={form.phone} onChange={e => setForm(f => ({ ...f, phone: e.target.value }))} />
            </Field>
            <Field label={audience === 'TEACHER' ? t('Trường / đơn vị công tác') : t('Trường / lớp')} htmlFor="pf-school" error={fields.school}>
              <input id="pf-school" className="input" maxLength={255} autoComplete="organization" value={form.school} onChange={e => setForm(f => ({ ...f, school: e.target.value }))} />
            </Field>
          </div>
          <Field label={t('Giới thiệu')} htmlFor="pf-bio" hint={t('{0}/1000 ký tự', { 0: form.bio.length })} error={fields.bio}>
            <textarea id="pf-bio" className="input" rows={3} maxLength={1000} value={form.bio} onChange={e => setForm(f => ({ ...f, bio: e.target.value }))} />
          </Field>
          {update.isError && !Object.keys(fields).length && <Notice tone="danger" live>{errorText(update.error)}</Notice>}
          {saved && !dirty && <Notice tone="success" live>{t('Đã lưu hồ sơ.')}</Notice>}
          <div className="spread" style={{ flexWrap: 'wrap', gap: 12 }}>
            <span className="xsmall muted">
              {profile.createdAt && t('Tạo tài khoản {0}', { 0: formatDateTime(profile.createdAt) })}
              {profile.lastLoginAt && <> · {t('Đăng nhập gần nhất {0}', { 0: formatDateTime(profile.lastLoginAt) })}</>}
            </span>
            <button type="submit" className="btn btn-primary" disabled={!dirty || !form.fullName.trim() || update.isPending} aria-busy={update.isPending}>
              <Save size={16} aria-hidden /> {update.isPending ? t('Đang lưu…') : t('Lưu thay đổi')}</button>
          </div>
        </form>
      </div>
    </Section>
  )
}

const PASSWORD_PROBLEM: Record<string, () => string> = {
  TOO_SHORT: () => t('Mật khẩu mới tối thiểu 8 ký tự.'),
  MISMATCH: () => t('Mật khẩu nhập lại không khớp.'),
}

function PasswordSection({ profile }: { profile: Profile }) {
  useT()
  const qc = useQueryClient()
  const [form, setForm] = useState({ current: '', next: '', confirm: '' })
  const [touched, setTouched] = useState(false)
  const [done, setDone] = useState(false)
  const problem = passwordProblem(form.next, form.confirm)
  const change = useMutation({
    mutationFn: () => accountApi.changePassword(profile.passwordSet ? form.current : null, form.next),
    onSuccess: () => { setForm({ current: '', next: '', confirm: '' }); setTouched(false); setDone(true); void qc.invalidateQueries({ queryKey: ['account', 'profile'] }) },
  })
  function submit(e: FormEvent) {
    e.preventDefault(); setTouched(true); setDone(false)
    if (!problem && (!profile.passwordSet || form.current)) change.mutate()
  }
  return (
    <Section title={profile.passwordSet ? t('Đổi mật khẩu') : t('Đặt mật khẩu')}
      description={profile.passwordSet ? t('Sau khi đổi, các thiết bị khác đang đăng nhập sẽ bị đăng xuất.') : t('Tài khoản tạo bằng Google chưa có mật khẩu. Đặt mật khẩu để đăng nhập được bằng email.')}>
      <form className="stack" onSubmit={submit}>
        {profile.passwordSet && <Field label={t('Mật khẩu hiện tại')} htmlFor="pw-current" required error={touched && !form.current ? t('Nhập mật khẩu hiện tại.') : undefined}>
          <input id="pw-current" className="input" type="password" autoComplete="current-password" value={form.current} onChange={e => setForm(f => ({ ...f, current: e.target.value }))} />
        </Field>}
        <Field label={t('Mật khẩu mới')} htmlFor="pw-new" required hint={t('Tối thiểu 8 ký tự.')}>
          <input id="pw-new" className="input" type="password" autoComplete="new-password" value={form.next} onChange={e => setForm(f => ({ ...f, next: e.target.value }))} />
        </Field>
        <Field label={t('Nhập lại mật khẩu mới')} htmlFor="pw-confirm" required error={touched && problem ? PASSWORD_PROBLEM[problem]() : undefined}>
          <input id="pw-confirm" className="input" type="password" autoComplete="new-password" value={form.confirm} onChange={e => setForm(f => ({ ...f, confirm: e.target.value }))} />
        </Field>
        {change.isError && <Notice tone="danger" live>{errorText(change.error)}</Notice>}
        {done && <Notice tone="success" live>{t('Đã cập nhật mật khẩu. Các thiết bị khác đã được đăng xuất.')}</Notice>}
        <button type="submit" className="btn btn-primary" style={{ alignSelf: 'flex-start', justifySelf: 'start' }} disabled={change.isPending} aria-busy={change.isPending}>
          <KeyRound size={16} aria-hidden /> {change.isPending ? t('Đang lưu…') : profile.passwordSet ? t('Đổi mật khẩu') : t('Đặt mật khẩu')}</button>
      </form>
    </Section>
  )
}

function GoogleSection({ profile }: { profile: Profile }) {
  useT()
  const save = useSaveProfile()
  const providers = useQuery({ queryKey: ['auth-providers'], queryFn: authApi.providers, staleTime: 300_000, retry: false })
  const [error, setError] = useState('')
  const link = useMutation({ mutationFn: accountApi.linkGoogle, onSuccess: p => { setError(''); save(p) }, onError: e => setError(errorText(e)) })
  const unlink = useMutation({ mutationFn: accountApi.unlinkGoogle, onSuccess: p => { setError(''); save(p) }, onError: e => setError(errorText(e)) })
  const clientId = providers.data?.google ? providers.data.googleClientId : null
  return (
    <Section title={t('Đăng nhập bằng Google')} description={t('Liên kết tài khoản Google để đăng nhập nhanh, không cần nhập mật khẩu.')}>
      <div className="stack">
        {profile.googleLinked
          ? <>
              <p className="row small" style={{ gap: 8 }}><Link2 size={16} aria-hidden /> {t('Đã liên kết với một tài khoản Google.')}</p>
              <button type="button" className="btn btn-secondary" style={{ alignSelf: 'flex-start', justifySelf: 'start' }} disabled={unlink.isPending || !profile.passwordSet} onClick={() => unlink.mutate()}>
                <Unlink size={16} aria-hidden /> {t('Hủy liên kết')}</button>
              {!profile.passwordSet && <p className="xsmall muted">{t('Đặt mật khẩu trước khi hủy liên kết, nếu không bạn sẽ không đăng nhập được.')}</p>}
            </>
          : clientId
            ? <div aria-busy={link.isPending}><GoogleButton clientId={clientId} text="continue_with" onCredential={c => link.mutate(c)} onError={setError} /></div>
            : <p className="small muted">{t('Máy chủ chưa bật đăng nhập Google.')}</p>}
        {error && <Notice tone="danger" live>{error}</Notice>}
      </div>
    </Section>
  )
}
