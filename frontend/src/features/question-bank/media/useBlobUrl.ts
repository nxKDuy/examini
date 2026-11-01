import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'

/** Fetches a protected file with the user's token and exposes it as an object URL. */
export function useBlobUrl(key: unknown[], load: () => Promise<Blob>, enabled = true) {
  const q = useQuery({ queryKey: key, queryFn: load, enabled, staleTime: Infinity, gcTime: 600_000, retry: 1 })
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    if (!q.data) return
    const u = URL.createObjectURL(q.data); setUrl(u)
    return () => URL.revokeObjectURL(u)
  }, [q.data])
  return { url, loading: q.isLoading && enabled, error: q.error }
}
