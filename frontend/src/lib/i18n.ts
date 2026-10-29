import { create } from 'zustand'
import { EN } from './i18n-en'
import { EN_UI } from './i18n-en-ui'

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

export type Vars = Record<string, string | number | null | undefined>
export function translate(lang: Lang, vi: string, vars?: Vars) {
  const text = lang === 'en' ? EN[vi] ?? EN_UI[vi] ?? vi : vi
  return vars ? text.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? String(vars[k] ?? '') : m)) : text
}

/** Non-hook form for models and constants; components should call useT() so they re-render on change. */
export const t = (vi: string, vars?: Vars) => translate(useLangStore.getState().lang, vi, vars)

export function useT() {
  useLangStore(s => s.lang)
  return t
}

/**
 * Module-level label maps/lists keep their Vietnamese source; string values are translated when read
 * (at render time), so a language switch never needs the constants to be rebuilt.
 */
export function i18nMap<T extends object>(source: T): T {
  return new Proxy(source, {
    get(target, key, receiver) {
      const v = Reflect.get(target, key, receiver)
      if (typeof v === 'string') return t(v)
      // Only plain objects and arrays are wrapped; RegExp, Date, Map and class instances are returned as they are.
      // React elements (icons in status maps) are plain objects too, but frozen in development: never proxy them.
      if (v && typeof v === 'object' && !('$$typeof' in v) && (Array.isArray(v) || Object.getPrototypeOf(v) === Object.prototype)) return i18nMap(v as object)
      return v
    },
  }) as T
}

/** Intl locale for dates and numbers in the UI language. */
export const locale = () => (useLangStore.getState().lang === 'en' ? 'en-GB' : 'vi-VN')
