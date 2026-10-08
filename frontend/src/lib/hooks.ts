import { useEffect, useState } from 'react'

/** Server-corrected "now" for display (countdowns, today marker). Never used to grant access. */
export function useNow(clockOffset: number, everyMs = 1000) {
  const [tick, setTick] = useState(() => Date.now())
  useEffect(() => { const t = setInterval(() => setTick(Date.now()), everyMs); return () => clearInterval(t) }, [everyMs])
  return tick + clockOffset
}

export function useIsNarrow(maxWidth = 767) {
  const query = `(max-width: ${maxWidth}px)`
  const [narrow, setNarrow] = useState(() => typeof window !== 'undefined' && window.matchMedia(query).matches)
  useEffect(() => {
    const mq = window.matchMedia(query)
    const on = () => setNarrow(mq.matches)
    on(); mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [query])
  return narrow
}
