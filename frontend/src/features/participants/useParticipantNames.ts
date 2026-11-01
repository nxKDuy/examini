import { useQuery } from '@tanstack/react-query'
import { participantApi } from './participantApi'
import { shortId } from '../../lib/format'

export interface PersonName { name: string; email?: string }
export type NameMap = ReadonlyMap<string, PersonName>
const MAX_PAGES = 20 // 2 000 participants; larger rosters fall back to short ids for the rest

/** userId → display name/email of an exam's participants (the roster staff may read), for tables that only carry ids. */
export function useParticipantNames(examId: string | undefined) {
  return useQuery({
    queryKey: ['participants', 'names', examId],
    enabled: Boolean(examId),
    staleTime: 60_000,
    retry: false,
    queryFn: async (): Promise<NameMap> => {
      const map = new Map<string, PersonName>()
      for (let page = 0; page < MAX_PAGES; page++) {
        const r = await participantApi.roster(examId!, { page, size: 100 })
        for (const p of r.content) {
          const name = p.displayName?.trim() || p.email || ''
          if (name) map.set(p.userId, { name, email: p.email })
        }
        if (page + 1 >= r.totalPages) break
      }
      return map
    },
  })
}

/** Display text for a user id: the name when known, else the short id. */
export const nameOf = (names: NameMap | undefined, userId: string) => names?.get(userId)?.name ?? shortId(userId)
