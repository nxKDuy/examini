import { useQuery } from '@tanstack/react-query'
import { orgApi } from './orgApi'
import { useAuthStore } from '../../store/authStore'

export const MY_INVITATIONS_KEY = 'my-org-invitations'
/** Pending invitations addressed to the signed-in user. Same query everywhere so accepting once updates every surface. */
export function useMyInvitations() {
  const userId = useAuthStore(s => s.user?.id)
  return useQuery({ queryKey: [MY_INVITATIONS_KEY, userId], queryFn: () => orgApi.myInvitations('PENDING'), staleTime: 15_000, refetchOnWindowFocus: true })
}

