import { create } from 'zustand'
import { EN } from './i18n-en'

// Vietnamese-first i18n (scope D). The Vietnamese text is the key and the default, so untranslated text
// never shows a raw key; English comes from the EN catalog. Kept per browser and per account.
export type Lang = 'vi' | 'en'
export const LANGS: { value: Lang; label: string }[] = [{ value: 'vi', label: 'Tiếng Việt' }, { value: 'en', label: 'English' }]
const BROWSER_KEY = 'examini.lang'
const accountKey = (userId: string) => `examini.lang.${userId}`

const read = (key: string): Lang | null => { try { const v = localStorage.getItem(key); return v === 'vi' || v === 'en' ? v : null } catch { return null } }
const write = (key: string, v: Lang) => { try { localStorage.setItem(key, v) } catch { /* storage blocked: session only */ } }

interface LangState { lang: Lang; userId: string | null; setLang: (lang: Lang) => void; bindAccount: (userId: string | null) => void }

export const useLangStore = create<LangState>((set, get) => ({
  lang: read(BROWSER_KEY) ?? 'vi',
  userId: null,
  setLang: lang => {
    write(BROWSER_KEY, lang)
    const { userId } = get()
    if (userId) write(accountKey(userId), lang)
    set({ lang })
  },
  /** On sign-in the account's own choice wins over the browser's; the browser choice is kept for the login screen. */
  bindAccount: userId => {
    const own = userId ? read(accountKey(userId)) : null
    set({ userId, ...(own ? { lang: own } : {}) })
    if (own) write(BROWSER_KEY, own)
  },
}))

useLangStore.subscribe(s => { if (typeof document !== 'undefined') document.documentElement.lang = s.lang })
if (typeof document !== 'undefined') document.documentElement.lang = useLangStore.getState().lang

export function translate(lang: Lang, vi: string, vars?: Record<string, string | number>) {
  const text = lang === 'en' ? EN[vi] ?? vi : vi
  return vars ? text.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? String(vars[k]) : m)) : text
}

/** Non-hook form for models and constants; components should call useT() so they re-render on change. */
export const t = (vi: string, vars?: Record<string, string | number>) => translate(useLangStore.getState().lang, vi, vars)

export function useT() {
  useLangStore(s => s.lang)
  return t
}
