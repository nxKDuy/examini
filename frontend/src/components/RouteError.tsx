import { useRouteError } from 'react-router-dom'
import { AlertTriangle, RotateCcw } from 'lucide-react'
import { t, useT } from '../lib/i18n'

/** Shown instead of a blank page when a screen crashes; the details stay in the console for developers. */
export default function RouteError() {
  useT()
  const error = useRouteError()
  if (error) console.error(error)
  return (
    <main className="auth-shell">
      <section className="card card-body stack" style={{ width: 'min(560px, 100%)' }} role="alert">
        <h1 className="row" style={{ fontSize: '1.4rem', gap: 10 }}><AlertTriangle size={22} aria-hidden /> {t('Trang gặp lỗi')}</h1>
        <p>{t('Màn hình này không hiển thị được. Dữ liệu của bạn không bị ảnh hưởng; hãy tải lại trang hoặc quay về Tổng quan.')}</p>
        <div className="row">
          <button type="button" className="btn btn-primary" onClick={() => window.location.reload()}><RotateCcw size={16} aria-hidden /> {t('Tải lại trang')}</button>
          <a className="btn btn-secondary" href="/dashboard">{t('Về Tổng quan')}</a>
        </div>
      </section>
    </main>
  )
}
