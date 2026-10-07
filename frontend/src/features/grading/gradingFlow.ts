import type { SubmissionDetail } from './gradingApi'

/** Pure orchestration for the grading screen, kept free of React so the edge cases can be tested. */

/**
 * Should a navigation be held back? Only when something is unsaved and the destination really differs
 * (same path and query is a no-op, e.g. re-clicking the open submission).
 */
export function shouldBlockNavigation(dirty: boolean, current: { pathname: string; search: string }, next: { pathname: string; search: string }): boolean {
  return dirty && (current.pathname !== next.pathname || current.search !== next.search)
}

/**
 * Holds ONE full deferred action (the exact thing the user asked for, e.g. "go to page 3") while unsaved edits exist.
 * confirm() runs that action once; cancel() drops it and leaves the draft alone.
 */
export function createDeferredGuard() {
  let pending: (() => void) | null = null
  return {
    request(action: () => void, dirty: boolean): boolean {
      if (!dirty) { pending = null; action(); return true }
      pending = action; return false
    },
    get waiting() { return pending !== null },
    confirm() { const run = pending; pending = null; run?.() },
    cancel() { pending = null },
  }
}

export interface FinalizeDeps {
  dirty: boolean
  currentRevision: number
  saveDraft: () => Promise<SubmissionDetail>
  /** Adopt a freshly saved draft as the new baseline (form, cache, revision) so a retry never reuses the old revision. */
  adopt: (saved: SubmissionDetail) => Promise<void>
  finalize: (revision: number) => Promise<SubmissionDetail>
}
export type DraftSavedError = { draftSaved?: boolean }

/** Save the draft first if needed, then finalize that exact revision. If finalize fails after the draft was saved, the saved revision is already adopted. */
export async function finalizeWithDraft(d: FinalizeDeps): Promise<SubmissionDetail> {
  let revision = d.currentRevision, saved = false
  if (d.dirty) {
    const draft = await d.saveDraft()
    await d.adopt(draft)
    revision = draft.resultRevision; saved = true
  }
  try { return await d.finalize(revision) }
  catch (e) { if (saved && e && typeof e === 'object') (e as DraftSavedError).draftSaved = true; throw e }
}
