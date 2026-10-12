import { parseUtc } from '../../lib/format'
import type { Contribution, ContributionStatus, Lineage, PublicQuestion, PublicViewResponse, QuestionSet } from './bankApi'
import type { Organization } from '../organizations/orgApi'

/** Pure Bank UI rules. They mirror what the server enforces so the UI does not offer actions in vain; the server stays the authority. */

export const CONTRIBUTION_LABEL: Record<ContributionStatus, { text: string; tone: 'info' | 'success' | 'danger' }> = {
  PENDING_REVIEW: { text: 'Chờ duyệt', tone: 'info' }, APPROVED: { text: 'Đã duyệt', tone: 'success' }, REJECTED: { text: 'Bị từ chối', tone: 'danger' },
}
export const BANK_TYPE_LABEL: Record<string, string> = { PERSONAL: 'Kho cá nhân', ORGANIZATION: 'Ngân hàng tổ chức', PUBLIC: 'Ngân hàng công khai', SYSTEM: 'Ngân hàng hệ thống' }

export type Capability = 'BANK_READ' | 'BANK_USE' | 'BANK_SUBMIT' | 'BANK_REVIEW' | 'BANK_MANAGE'
export const hasCapability = (org: Pick<Organization, 'me'>, cap: Capability) => !!org.me?.capabilities?.includes(cap)

export type OrgBankTab = 'approved' | 'mine' | 'queue' | 'history'
export const ORG_BANK_TAB_LABEL: Record<OrgBankTab, string> = { approved: 'Câu hỏi đã duyệt', mine: 'Đóng góp của tôi', queue: 'Hàng chờ duyệt', history: 'Lịch sử' }
/** Sub-tabs from the real capabilities: everyone with BANK_READ reads and sees own contributions; only reviewers get the queue and history. */
export function orgBankTabs(org: Pick<Organization, 'me'> & Partial<Pick<Organization, 'status'>>): OrgBankTab[] {
  if (!hasCapability(org, 'BANK_READ')) return []
  return org.status !== 'ARCHIVED' && hasCapability(org, 'BANK_REVIEW') ? ['approved', 'mine', 'queue', 'history'] : ['approved', 'mine', 'history']
}
/** Org detail shows the bank tab only to members who can read it. */
export const showBankTab = (org: Pick<Organization, 'me'>) => orgBankTabs(org).length > 0

/** Query for each contribution sub-tab. The server needs `mine=true` for own submissions and BANK_REVIEW for `mine=false`. */
export function contributionQuery(tab: OrgBankTab, canReview = true): { mine: boolean; status?: string } | null {
  if (tab === 'mine') return { mine: true }
  if (tab === 'queue') return { mine: false, status: 'PENDING_REVIEW' }
  if (tab === 'history') return { mine: !canReview }
  return null
}

/** Self-review is forbidden by the server (403); the UI says so up front instead of offering a button that cannot work. */
export const isOwnContribution = (c: Pick<Contribution, 'sourceOwnerUserId'>, userId: string | undefined) => !!userId && c.sourceOwnerUserId === userId
export function reviewState(c: Pick<Contribution, 'status' | 'sourceOwnerUserId'>, canReview: boolean, userId: string | undefined): { canAct: boolean; note: string | null } {
  if (c.status !== 'PENDING_REVIEW') return { canAct: false, note: 'Đóng góp này đã được xử lý.' }
  if (!canReview || !userId) return { canAct: false, note: 'Bạn không có quyền duyệt trong tổ chức này.' }
  if (isOwnContribution(c, userId)) return { canAct: false, note: 'Đây là đóng góp của chính bạn. Người khác phải duyệt; bạn không thể tự duyệt.' }
  return { canAct: true, note: null }
}
export const rejectReady = (note: string) => note.trim().length > 0

/** Organizations the caller can submit into: ACTIVE and BANK_SUBMIT (an ARCHIVED org grants none). */
export const submitTargets = (orgs: Organization[]) => orgs.filter(o => o.status === 'ACTIVE' && hasCapability(o, 'BANK_SUBMIT'))

/** Only an ACTIVE personal question can be shared (submit/publish). The version shown is the exact one sent. */
export const canShare = (q: { status: string }) => q.status === 'ACTIVE'

export function publicationBadge(p: Pick<PublicQuestion, 'publicationStatus'>): { text: string; tone: 'success' | 'muted' } {
  return p.publicationStatus === 'ARCHIVED' ? { text: 'Đã gỡ công khai', tone: 'muted' } : { text: 'Đang công khai', tone: 'success' }
}
export const isPublicOwner = (p: Pick<PublicQuestion, 'sourceOwnerUserId'>, userId: string | undefined) => !!userId && p.sourceOwnerUserId === userId
export const canUnpublish = (p: Pick<PublicQuestion, 'sourceOwnerUserId' | 'publicationStatus'>, userId: string | undefined) => isPublicOwner(p, userId) && p.publicationStatus === 'PUBLISHED'
/** Copying needs a Teacher and a live publication (the server answers 403 / 409 otherwise). */
export const canForkPublic = (p: Pick<PublicQuestion, 'publicationStatus'>, teacher: boolean) => teacher && p.publicationStatus === 'PUBLISHED'
export function copiedBadge(p: Pick<PublicQuestion, 'isCopied' | 'postType' | 'items' | 'itemCount'>): string | null {
  if (!p.isCopied) return null
  return p.postType === 'QUESTION_SET' && (p.items?.filter(item => item.isCopied).length ?? 0) < (p.itemCount ?? p.items?.length ?? 0)
    ? 'Đã sao chép một phần' : 'Đã sao chép'
}
/** Card date: when the snapshot's CONTENT was last edited at the source; older rows fall back to the publish date (the backend does the same). */
export function updatedDate(p: Pick<PublicQuestion, 'contentUpdatedAt' | 'publishedAt'>): string | null {
  const d = parseUtc(p.contentUpdatedAt ?? p.publishedAt)
  return d ? new Intl.DateTimeFormat('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric' }).format(d) : null
}
/** The broadest taxonomy a card can state: subjects first, else the top-level category, else the snapshot's category name. `more` counts the ones not named. */
export function broadTopic(p: Pick<PublicQuestion, 'subjects' | 'categories' | 'question'>): { text: string; more: number } | null {
  const subjects = [...new Set((p.subjects ?? []).map(s => s.name.trim()).filter(Boolean))]
  if (subjects.length) return { text: subjects[0], more: subjects.length - 1 }
  const tops = [...new Set((p.categories ?? []).map(c => (c.path?.[0] ?? c.name).trim()).filter(Boolean))]
  if (tops.length) return { text: tops[0], more: tops.length - 1 }
  const own = p.question?.categoryName?.trim()
  return own ? { text: own, more: 0 } : null
}
export interface Stat { key: 'views' | 'copies' | 'rating'; value: string; label: string }
/** Compact real numbers only: a missing figure is "—" (never 0 invented), no rating is stated as such, the average stays raw out of 10. */
export function postStats(p: Pick<PublicQuestion, 'viewsTotal' | 'copiesTotal' | 'averageRating' | 'ratingCount'>): Stat[] {
  const count = (key: 'views' | 'copies', n: number | null | undefined, noun: string): Stat => (n == null ? { key, value: '—', label: `Chưa có số liệu ${noun}` } : { key, value: String(n), label: `${n} lượt ${noun}` })
  const rating: Stat = p.ratingCount == null ? { key: 'rating', value: '—', label: 'Chưa có số liệu đánh giá' }
    : p.ratingCount === 0 || p.averageRating == null ? { key: 'rating', value: 'Chưa có', label: 'Chưa có đánh giá' }
    : { key: 'rating', value: `${Number(p.averageRating.toFixed(1))}/10 (${p.ratingCount})`, label: `${Number(p.averageRating.toFixed(1))}/10 từ ${p.ratingCount} đánh giá` }
  return [count('views', p.viewsTotal, 'xem'), count('copies', p.copiesTotal, 'sao chép'), rating]
}
export const authorName = (p: Pick<PublicQuestion, 'sourceOwnerDisplayName'>) => p.sourceOwnerDisplayName?.trim() || 'Unknown user'

export const MAX_SEARCH = 200
/** Query params for discovery: blanks dropped, text capped at 200, owner filter only when set. */
export function publicParams(f: { text: string; questionType: string; difficulty: string; author: string; page: number; mine?: boolean; saved?: boolean; publicationStatus?: 'PUBLISHED' | 'ARCHIVED'; subjectId?: string; categoryId?: string; postType?: string; ranking?: string }) {
  const text = f.text.trim().slice(0, MAX_SEARCH)
  return { ...(text ? { text } : {}), ...(f.questionType ? { questionType: f.questionType } : {}), ...(f.difficulty ? { difficulty: f.difficulty } : {}), ...(f.author ? { sourceOwnerUserId: f.author } : {}), ...(f.subjectId ? { subjectId: f.subjectId } : {}), ...(f.categoryId ? { categoryId: f.categoryId } : {}), ...(f.postType ? { postType: f.postType } : {}), ...(f.ranking ? { ranking: f.ranking } : {}), ...(f.saved ? { saved: true } : {}), ...(f.mine && !f.saved ? { mine: true, ...(f.publicationStatus ? { publicationStatus: f.publicationStatus } : {}) } : {}), page: f.page }
}

export function applyBookmarkState<T extends PublicQuestion>(post: T, isSaved: boolean): T {
  return { ...post, isSaved }
}

export const excerpt = (text: string, max = 140) => { const t = text.replace(/\s+/g, ' ').trim(); return t.length <= max ? t : `${t.slice(0, max - 1)}…` }
export const shortId = (id: string) => id.slice(0, 8)

export interface ChainLink { key: string; label: string; detail: string; tone: 'source' | 'bridge' | 'here' }
/** "Nguồn gốc": source -> (the public/org publication it came through) -> this copy. Only what the lineage API actually says; no e-mail, no guessing. */
export function provenanceChain(l: Lineage | null): ChainLink[] {
  if (!l) return []
  if (l.nodes?.length) return l.nodes.map((n, i) => {
    const label = n.relation === 'SOURCE' ? `Câu gốc ${shortId(n.id)}` : n.relation === 'PUBLICATION' ? 'Bản đã xuất bản/đóng góp' : 'Bản sao này'
    const status = n.available ? ({ DRAFT: 'Bản nháp', ACTIVE: 'Đang dùng', PUBLISHED: 'Đang công khai', APPROVED: 'Đã duyệt', ARCHIVED: n.bankType === 'PUBLIC' ? 'Đã gỡ công khai' : 'Đã lưu trữ' } as Record<string, string>)[n.status ?? ''] ?? 'Trạng thái chưa được cung cấp' : 'Nguồn hiện không khả dụng hoặc không còn quyền xem'
    const bank = n.available ? `${BANK_TYPE_LABEL[n.bankType] ?? 'Ngân hàng'}${n.name?.trim() ? ` · ${n.name.trim()}` : ''}` : ''
    return { key: `${n.relation}-${i}`, label, detail: [`Phiên bản ${n.version}`, status, bank, n.frozen ? 'Bản chụp cố định' : ''].filter(Boolean).join(' · '), tone: n.relation === 'SOURCE' ? 'source' : n.relation === 'PUBLICATION' ? 'bridge' : 'here' }
  })
  const bank = BANK_TYPE_LABEL[l.sourceBankType ?? ''] ?? 'Ngân hàng khác'
  const links: ChainLink[] = [{ key: 'source', label: `Câu gốc ${shortId(l.sourceQuestionId)}`, detail: `Phiên bản ${l.sourceQuestionVersion} · ${bank}`, tone: 'source' }]
  if (l.sourcePublicQuestionId) links.push({ key: 'public', label: 'Bản xuất bản công khai', detail: `Mã ${shortId(l.sourcePublicQuestionId)}`, tone: 'bridge' })
  if (l.sourceOrganizationQuestionId) links.push({ key: 'org', label: 'Bản đã duyệt của tổ chức', detail: `Mã ${shortId(l.sourceOrganizationQuestionId)}`, tone: 'bridge' })
  links.push({ key: 'here', label: 'Bản sao này', detail: l.copiedAt ? `Sao chép ${new Date(l.copiedAt).toLocaleDateString('vi-VN')}` : 'Bản sao độc lập', tone: 'here' })
  return links
}

/** Publish failing with 503 means Auth could not confirm the author's display name: nothing was published. */
export const isAuthorLookupFailure = (e: unknown) => (e as { response?: { status?: number } })?.response?.status === 503
/** Save/Saved exist for Teacher and Student accounts only (backend answers 403 for other actors). Never inferred from TEACHER = ADMIN: the shared audience decides. */
export const canBookmark = (audience: string) => audience === 'TEACHER' || audience === 'STUDENT'
export const isStatus = (e: unknown, status: number) => (e as { response?: { status?: number } })?.response?.status === status

/** Eligibility is the provider's decision, never a status approximation. */
export const canPickQuestion = (q: { eligible?: boolean }) => q.eligible === true

export type QuestionSetConfirmation = { action: 'archive' | 'publish'; set: QuestionSet }
export function dispatchQuestionSetConfirmation(confirmation: QuestionSetConfirmation, actions: { archive: (set: QuestionSet) => void; publish: (set: QuestionSet) => void }) {
  actions[confirmation.action](confirmation.set)
}

export const publicDetailQueryKey = (id: string, userId: string | undefined) => ['banks', 'public-detail', userId ?? 'anonymous', id] as const
export function applyPublicViewStats(post: PublicQuestion, view: PublicViewResponse): PublicQuestion {
  return { ...post, views24h: view.views24h, views30d: view.views30d, views365d: view.views365d, viewsTotal: view.viewsTotal }
}
export function personalForkQueryKeys(userId: string | undefined, wholeSet: boolean) {
  return wholeSet
    ? [['questions', userId], ['banks', 'personal-set-questions'], ['banks', 'question-sets']]
    : [['questions', userId], ['banks', 'personal-set-questions']]
}
