import { useQuery } from '@tanstack/react-query'
import './banks.css'
import { bankApi } from './bankApi'
import { provenanceChain } from './bankModel'
import { Notice } from '../../components/ui'
import { errorInfo } from '../../lib/errors'

/** "Nguồn gốc" of a personal question: a short chain from its lineage. Silent when the question is not a copy. */
export default function ProvenanceBlock({ questionId }: { questionId: string }) {
  const q = useQuery({
    queryKey: ['lineage', questionId], retry: false,
    queryFn: async () => { try { return await bankApi.lineage(questionId) } catch (e) { if (errorInfo(e).status === 404) return null; throw e } },
  })
  if (q.isLoading) return null
  if (q.isError) return <Notice tone="warning">Chưa tải được nguồn gốc của câu hỏi này.</Notice>
  const chain = provenanceChain(q.data ?? null)
  if (chain.length === 0) return <section aria-label="Nguồn gốc"><span className="field-label">Nguồn gốc</span><p className="small muted">Câu hỏi này do bạn tự soạn, không phải bản sao.</p></section>
  return (
    <section aria-label="Nguồn gốc" className="stack-sm">
      <span className="field-label">Nguồn gốc</span>
      <ol className="bk-chain">{chain.map(l => <li key={l.key} data-tone={l.tone}><strong>{l.label}</strong><span className="xsmall muted" style={{ display: 'block' }}>{l.detail}</span></li>)}</ol>
    </section>
  )
}
