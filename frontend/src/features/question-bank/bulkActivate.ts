import type { Question } from './questionApi'

export interface BulkResult { activated: string[]; failed: { id: string; content: string; reason: string }[] }

/** Activatable from the list: the actor's own DRAFT questions. */
export const canActivate = (q: Pick<Question, 'status' | 'createdBy'>, userId: string | undefined) =>
  q.status === 'DRAFT' && !!userId && q.createdBy === userId

/**
 * Activates questions one after another (each keeps its own optimistic version), never stopping at the
 * first failure: a question that fails validation stays DRAFT and is reported with the server's reason.
 */
export async function activateMany(questions: Pick<Question, 'id' | 'questionVersion' | 'content'>[],
                                   activate: (id: string, version: number) => Promise<unknown>,
                                   reason: (e: unknown) => string,
                                   onProgress?: (done: number) => void): Promise<BulkResult> {
  const out: BulkResult = { activated: [], failed: [] }
  let done = 0
  for (const q of questions) {
    try { await activate(q.id, q.questionVersion); out.activated.push(q.id) }
    catch (e) { out.failed.push({ id: q.id, content: q.content, reason: reason(e) }) }
    onProgress?.(++done)
  }
  return out
}
