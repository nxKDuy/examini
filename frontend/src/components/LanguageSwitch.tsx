import { Languages } from 'lucide-react'
import { LANGS, useLangStore, useT } from '../lib/i18n'

/** Topbar/login language switch. The choice is kept for this browser and, when signed in, for the account. */
export default function LanguageSwitch() {
  const t = useT()
  const lang = useLangStore(s => s.lang), setLang = useLangStore(s => s.setLang)
  return (
    <label className="row lang-switch" style={{ gap: 4, flexWrap: 'nowrap' }} title={t('Ngôn ngữ')}>
      <Languages size={15} aria-hidden />
      <span className="sr-only">{t('Ngôn ngữ')}</span>
      <select className="lang-select" value={lang} onChange={e => setLang(e.target.value as typeof lang)}>
        {LANGS.map(l => <option key={l.value} value={l.value} lang={l.value}>{l.value === 'vi' ? 'VI' : 'EN'} · {l.label}</option>)}
      </select>
    </label>
  )
}
