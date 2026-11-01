import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Settings2 } from 'lucide-react'
import { notificationApi, type PrefCategory, type PrefSetting } from './notificationApi'
import { preferenceRows } from './notificationModel'
import { Notice } from '../../components/ui'
import { useAuthStore } from '../../store/authStore'
import { audienceOf } from '../../components/layout/navigation'
import { t, useT } from '../../lib/i18n'

/**
 * Email / browser-push switches per category. The bell inbox always gets every notice; these only gate the
 * channels that reach you outside EXAMINI. Each switch saves on its own, optimistically.
 */
export function PreferencesCard() {
  useT()
  const user = useAuthStore(s => s.user)
  const student = audienceOf(user) === 'STUDENT'
  const qc = useQueryClient()
  const key = ['notifications', 'preferences', user?.id]
  const prefs = useQuery({ queryKey: key, queryFn: notificationApi.preferences, staleTime: 60_000 })
  // Pending toggles live in React state: it updates in the same event, so a controlled checkbox never flickers back.
  const [pending, setPending] = useState<Partial<Record<PrefCategory, PrefSetting>>>({})
  const settle = (c: PrefCategory) => setPending(p => { const n = { ...p }; delete n[c]; return n })
  const save = useMutation({
    mutationFn: (next: PrefSetting) => notificationApi.savePreferences([next]),
    onSuccess: list => qc.setQueryData(key, list),
    onSettled: (_d, _e, next) => settle(next.category),
  })
  const byCat = new Map<PrefCategory, PrefSetting>((prefs.data ?? []).map(s => [s.category, s]))
  const value = (c: PrefCategory) => pending[c] ?? byCat.get(c) ?? { category: c, email: true, push: true }
  const toggle = (c: PrefCategory, channel: 'email' | 'push') => {
    const cur = value(c), next = { ...cur, [channel]: !cur[channel] }
    setPending(p => ({ ...p, [c]: next }))
    save.mutate(next)
  }

  return (
    <details className="card notif-prefs">
      <summary className="notif-prefs-head">
        <Settings2 size={18} aria-hidden />
        <span><strong>{t('Cài đặt thông báo')}</strong><span className="small muted">{t('Chọn loại thông báo được gửi qua email và trình duyệt')}</span></span>
      </summary>
      <div className="card-body stack-sm">
        <p className="small muted">{t('Chuông thông báo trong EXAMINI luôn nhận đủ mọi thông báo; các lựa chọn dưới đây chỉ áp dụng cho email và thông báo trình duyệt.')}</p>
        {prefs.isError ? <Notice tone="danger">{t('Không tải được cài đặt thông báo.')}</Notice> : (
          <table className="notif-prefs-table">
            <thead><tr><th scope="col">{t('Loại thông báo')}</th><th scope="col">{t('Email')}</th><th scope="col">{t('Trình duyệt')}</th></tr></thead>
            <tbody>
              {preferenceRows(student).map(r => {
                const s = value(r.category)
                return (
                  <tr key={r.category}>
                    <th scope="row"><span className="notif-prefs-name">{t(r.label)}</span><span className="small muted">{t(r.hint)}</span></th>
                    {(['email', 'push'] as const).map(ch => (
                      <td key={ch}>
                        <label className="check notif-prefs-check">
                          <input type="checkbox" checked={s[ch]} disabled={!prefs.data}
                            aria-label={`${t(r.label)} — ${t(ch === 'email' ? 'Email' : 'Trình duyệt')}`} onChange={() => toggle(r.category, ch)} />
                        </label>
                      </td>
                    ))}
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
        {save.isError && <Notice tone="danger" live>{t('Chưa lưu được thay đổi, vui lòng thử lại.')}</Notice>}
        {save.isSuccess && !save.isPending && <p className="small muted" role="status">{t('Đã lưu.')}</p>}
      </div>
    </details>
  )
}
