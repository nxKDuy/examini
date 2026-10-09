import type { QueryClient, QueryKey } from '@tanstack/react-query'
import type { Paged, PublicQuestion } from './bankApi'
import { applyBookmarkState, publicDetailQueryKey } from './bankModel'

type PublicPage = Paged<PublicQuestion>
export interface BookmarkCacheSnapshot {
  lists: [QueryKey, PublicPage | undefined][]
  detail: PublicQuestion | undefined
  detailKey: QueryKey
}

/** Optimistic state is isolated to the current JWT actor and can be restored exactly on failure. */
export async function optimisticallySetBookmark(client: QueryClient, id: string, actorId: string | undefined, saved: boolean): Promise<BookmarkCacheSnapshot> {
  const actor = actorId ?? 'anonymous'
  const listKey = ['banks', 'public', actor] as const
  const detailKey = publicDetailQueryKey(id, actorId)
  await Promise.all([client.cancelQueries({ queryKey: listKey }), client.cancelQueries({ queryKey: detailKey })])
  const lists = client.getQueriesData<PublicPage>({ queryKey: listKey })
  const detail = client.getQueryData<PublicQuestion>(detailKey)
  client.setQueriesData<PublicPage>({ queryKey: listKey }, old => old ? {
    ...old,
    content: old.content.map(post => post.id === id ? applyBookmarkState(post, saved) : post),
  } : old)
  if (detail) client.setQueryData(detailKey, applyBookmarkState(detail, saved))
  return { lists, detail, detailKey }
}

export function rollbackBookmark(client: QueryClient, snapshot: BookmarkCacheSnapshot) {
  for (const [key, data] of snapshot.lists) client.setQueryData(key, data)
  if (snapshot.detail) client.setQueryData(snapshot.detailKey, snapshot.detail)
}
