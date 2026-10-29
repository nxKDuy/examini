import { shortId } from '../../lib/format'
import type { NameMap } from './useParticipantNames'

/** Name with the email (or full id) underneath; short id when the person is not on the roster. */
export function PersonCell({ names, userId }: { names: NameMap | undefined; userId: string }) {
  const p = names?.get(userId)
  if (!p) return <span className="mono" title={userId}>{shortId(userId)}</span>
  return <span className="person-cell" title={userId}><span>{p.name}</span>{p.email && p.email !== p.name && <small className="muted">{p.email}</small>}</span>
}
