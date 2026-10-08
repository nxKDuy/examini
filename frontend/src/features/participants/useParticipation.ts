import { useQuery } from '@tanstack/react-query'
import { errorInfo } from '../../lib/errors'
import { participantApi } from './participantApi'

/** The Student's own participation (null when they are not a participant: preview, or the server answers 403/404). */
export function useParticipation(examId: string | undefined) {
  return useQuery({
    queryKey: ['participation', 'me', examId], enabled: !!examId, retry: false, refetchOnWindowFocus: true, staleTime: 15_000,
    queryFn: async () => {
      try { return await participantApi.mine(examId!) } catch (e) { const s = errorInfo(e).status; if (s === 403 || s === 404) return null; throw e }
    },
  })
}
