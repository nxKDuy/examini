import { Star } from 'lucide-react'

/** Gold star = there is a rating (fill + darker edge so it stays visible on white); grey outline = none yet. Digits stay in normal text colour. */
export default function RatingStar({ filled, size = 14, pop }: { filled: boolean; size?: number; pop?: boolean }) {
  return <Star size={size} className={`star ${filled ? 'star-on' : 'star-off'}${pop ? ' pop' : ''}`} aria-hidden />
}
