import { useEffect, useId, useRef, useState } from 'react'
import { SERIES_COLORS, labelEvery, nearestIndex, niceScale, xPositions } from './chartModel'
import { t, useT } from '../../lib/i18n'
import './charts.css'

export interface Series { name: string; values: (number | null)[] }

/**
 * Line chart (one or two series) over categorical x labels, with a crosshair tooltip, a legend for 2 series and a
 * table view. `max` fixes the y axis (e.g. 100 for percent).
 */
export function LineChart({ series, labels, max, format = v => String(v), height = 220, title }: {
  series: Series[]; labels: string[]; max?: number; format?: (v: number) => string; height?: number; title: string
}) {
  useT()
  const [hover, setHover] = useState<number | null>(null)
  const svgRef = useRef<SVGSVGElement>(null)
  const boxRef = useRef<HTMLDivElement>(null)
  // Draw in real pixels so text and strokes keep their size whatever the card width is.
  const [width, setWidth] = useState(640)
  useEffect(() => {
    const el = boxRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(([e]) => setWidth(Math.max(280, Math.round(e.contentRect.width))))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  const W = width, H = height, L = 40, R = 12, T = 12, B = 26
  const all = series.flatMap(s => s.values).filter((v): v is number => v !== null)
  const scale = niceScale(max ?? Math.max(0, ...all))
  const xs = xPositions(labels.length, L + 6, W - R - 6)
  const y = (v: number) => T + (H - T - B) * (1 - v / scale.max)
  const show = labelEvery(labels.length, Math.max(3, Math.floor(W / 80)))
  const path = (vals: (number | null)[]) => vals.map((v, i) => v === null ? '' : `${i === 0 || vals[i - 1] === null ? 'M' : 'L'}${xs[i].toFixed(1)},${y(v).toFixed(1)}`).join(' ')
  function move(e: React.PointerEvent) {
    const r = svgRef.current?.getBoundingClientRect(); if (!r) return
    setHover(nearestIndex(xs, ((e.clientX - r.left) / r.width) * W))
  }
  return (
    <figure className="chart" aria-label={title}>
      {series.length > 1 && <div className="chart-legend">{series.map((s, i) => <span key={s.name}><i style={{ background: SERIES_COLORS[i] }} />{s.name}</span>)}</div>}
      <div className="chart-plot" ref={boxRef}>
        <svg ref={svgRef} viewBox={`0 0 ${W} ${H}`} width={W} height={H} role="img" aria-label={title} onPointerMove={move} onPointerLeave={() => setHover(null)}>
          {scale.ticks.map(v => <g key={v}><line className="chart-grid" x1={L} x2={W - R} y1={y(v)} y2={y(v)} /><text className="chart-tick" x={L - 6} y={y(v) + 4} textAnchor="end">{format(v)}</text></g>)}
          {labels.map((l, i) => show(i) && <text key={i} className="chart-tick" x={xs[i]} y={H - 6} textAnchor={i === 0 && labels.length > 1 ? 'start' : i === labels.length - 1 && labels.length > 1 ? 'end' : 'middle'}>{l}</text>)}
          {series.map((s, si) => <g key={s.name}>
            <path d={path(s.values)} fill="none" stroke={SERIES_COLORS[si]} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
            {s.values.map((v, i) => v !== null && (labels.length <= 14 || hover === i) && <circle key={i} cx={xs[i]} cy={y(v)} r={hover === i ? 5 : labels.length <= 12 ? 4 : 2.5} fill={SERIES_COLORS[si]} stroke="var(--color-surface)" strokeWidth={2} />)}
          </g>)}
          {hover !== null && <line className="chart-cross" x1={xs[hover]} x2={xs[hover]} y1={T} y2={H - B} />}
        </svg>
        {hover !== null && <div className="chart-tip" style={{ left: `${(xs[hover] / W) * 100}%`, transform: xs[hover] < 90 ? 'translateX(-10%)' : xs[hover] > W - 90 ? 'translateX(-90%)' : undefined }} role="status">
          <strong>{labels[hover]}</strong>
          {series.map((s, i) => <span key={s.name}><i style={{ background: SERIES_COLORS[i] }} />{series.length > 1 ? `${s.name}: ` : ''}{s.values[hover] === null ? '–' : format(s.values[hover]!)}</span>)}
        </div>}
      </div>
      <DataTable title={title} head={[t('Mốc'), ...series.map(s => s.name)]} rows={labels.map((l, i) => [l, ...series.map(s => s.values[i] === null ? '–' : format(s.values[i]!))])} />
    </figure>
  )
}

/** Horizontal bars for a few labelled magnitudes (counts by status/type…), values printed in ink beside each bar. */
export function BarList({ rows, format = v => v.toLocaleString(), title, max }: {
  rows: { label: string; value: number; hint?: string }[]; format?: (v: number) => string; title: string; max?: number
}) {
  useT()
  const top = max ?? Math.max(1, ...rows.map(r => r.value))
  if (!rows.length) return <p className="small muted">{t('Chưa có dữ liệu.')}</p>
  return (
    <figure className="chart" aria-label={title}>
      <ul className="bar-list">
        {rows.map(r => <li key={r.label} title={r.hint ?? `${r.label}: ${format(r.value)}`}>
          <span className="bar-label">{r.label}</span>
          <span className="bar-track"><span className="bar-fill" style={{ width: `${Math.max(r.value > 0 ? 1.5 : 0, (r.value / top) * 100)}%` }} /></span>
          <span className="bar-value">{format(r.value)}</span>
        </li>)}
      </ul>
    </figure>
  )
}

function DataTable({ title, head, rows }: { title: string; head: string[]; rows: string[][] }) {
  const id = useId()
  return (
    <details className="chart-table">
      <summary className="xsmall muted">{t('Xem bảng số liệu')}</summary>
      <div className="table-wrap"><table className="table" aria-describedby={id}>
        <caption id={id} className="sr-only">{title}</caption>
        <thead><tr>{head.map(h => <th key={h} scope="col">{h}</th>)}</tr></thead>
        <tbody>{rows.map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j}>{c}</td>)}</tr>)}</tbody>
      </table></div>
    </details>
  )
}
