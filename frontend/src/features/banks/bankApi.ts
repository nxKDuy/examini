import api from '../../lib/api'
import type { Question, QuestionOption, Page } from '../question-bank/questionApi'

/** Contract: question-bank-org-public.md (MAIN). Public/Org question bodies are candidate-safe: no answer key, no e-mail, author only by display name. */
export type BankType = 'PERSONAL' | 'ORGANIZATION' | 'SYSTEM' | 'PUBLIC'
export interface Bank { id: string; type: BankType; ownerUserId?: string; organizationId?: string; name: string; status: string; defaultBank: boolean; createdAt?: string }
export interface SafeQuestion {
  id: string; categoryName?: string; content: string; questionType: Question['questionType']; difficultyLevel: Question['difficultyLevel']
  options: Pick<QuestionOption, 'id' | 'content' | 'displayOrder'>[]; questionVersion: number; status?: string
  sourceLabel?: string | null; sourceUrl?: string | null; eligible?: boolean
}
export interface Paged<T> { content: T[]; totalElements: number; totalPages: number; number: number }

export type PublicationStatus = 'PUBLISHED' | 'ARCHIVED'
export interface PublicQuestion {
  id: string; bankId: string; sourceQuestionId: string; sourceQuestionVersion: number; sourceBankId?: string
  sourceOwnerUserId: string; sourceOwnerDisplayName?: string; publicationStatus: PublicationStatus
  publishedBy?: string; publishedAt?: string; archivedBy?: string; archivedAt?: string; question: SafeQuestion
  postType?: 'SINGLE_QUESTION' | 'QUESTION_SET'; title?: string; description?: string; postVersion?: number; itemCount?: number
  items?: PublicPostItem[]; subjects?: { id: string; name: string }[]; categories?: { id: string; name: string; subjectId: string; subjectName: string; parentCategoryId?: string; path: string[] }[]
  copies24h?: number; copies30d?: number; copies365d?: number; copiesTotal?: number
  averageRating?: number | null; ratingCount?: number; myRating?: number | null
  views24h?: number; views30d?: number; views365d?: number; viewsTotal?: number
  isSaved?: boolean
}
export interface PublicPostItem { id: string; position: number; sourceQuestionId: string; sourceQuestionVersion: number; question: SafeQuestion }
export interface PublicViewResponse { publicationId: string; views24h: number; views30d: number; views365d: number; viewsTotal: number }
export interface QuestionSet { id: string; bankId: string; ownerUserId: string; title: string; description?: string; version: number; status: 'ACTIVE' | 'ARCHIVED'; updatedAt?: string; items: PublicPostItem[] }
export interface Taxonomy { id: string; name: string; subjectId?: string; subjectName?: string; parentCategoryId?: string; path?: string[] }
export interface OrgQuestion {
  id: string; bankId: string; sourceQuestionId: string; sourceQuestionVersion: number; sourceOwnerUserId: string
  publicationStatus?: string; approvedBy?: string; approvedAt?: string; question: SafeQuestion
}
export type ContributionStatus = 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED'
/** `question` is a projection: reviewers asking for answers receive the full question, everyone else a safe one. */
export interface Contribution {
  id: string; bankId: string; sourceQuestionId: string; sourceQuestionVersion: number; sourceOwnerUserId: string
  status: ContributionStatus; submissionNote?: string | null; reviewerId?: string; reviewNote?: string; submittedAt?: string; reviewedAt?: string
  question: SafeQuestion & { options: (QuestionOption | SafeQuestion['options'][number])[]; expectedAnswer?: string | null }
}
export interface LineageNode { relation: 'SOURCE' | 'PUBLICATION' | 'COPY'; id: string; version: number; bankId: string | null; bankType: BankType; status: string | null; name: string | null; available: boolean; frozen: boolean }
export interface Lineage {
  nodes?: LineageNode[]
  questionId: string; sourceQuestionId: string; sourceQuestionVersion: number; sourceBankId?: string; sourceBankType?: BankType
  sourcePublicQuestionId?: string; sourceOrganizationQuestionId?: string; copiedAt?: string; copiedBy?: string
}
export interface BankQuestion { bankId: string; question: Question }

export interface PublicFilters { mine?: boolean; saved?: boolean; publicationStatus?: PublicationStatus; bankId?: string; text?: string; questionType?: string; difficulty?: string; sourceOwnerUserId?: string; subjectId?: string; categoryId?: string; postType?: string; ranking?: string; page?: number; size?: number }

export const bankApi = {
  allBanks: async () => {
    const result: Bank[] = []
    for (let page = 0; ; page++) {
      const data = await bankApi.banks({ page, size: 100 })
      result.push(...data.content)
      if (page + 1 >= data.totalPages) return result
    }
  },
  eligibleBatch: (ids: string[]) => api.post<Question[]>('/questions/batch', ids, { params: { eligible: true, includeAnswers: false } }).then(r => r.data),
  pickerList: async (bank: Bank, params: { questionType?: string; difficulty?: string; page: number; size: number }): Promise<Page<Question>> => {
    // Publication IDs are the Exam resources, never sourceQuestionId.
    let rows: { id: string }[]
    let metadata: Paged<unknown>
    if (bank.type === 'PUBLIC') {
      const data = await bankApi.publicList({ ...params, bankId: bank.id })
      rows = data.content.flatMap(post => post.postType === 'QUESTION_SET' ? (post.items ?? []).map(item => ({ id: item.id })) : [{ id: post.id }]); metadata = data
    } else if (bank.type === 'ORGANIZATION') {
      const data = await bankApi.orgQuestions({ ...params, bankId: bank.id })
      rows = data.content; metadata = data
    } else {
      const data = (await api.get<Paged<BankQuestion>>(`/question-banks/${bank.id}/questions`, { params: { ...params, status: 'ACTIVE' } })).data
      rows = data.content.map(r => r.question); metadata = data
    }
    const content = rows.length ? await bankApi.eligibleBatch(rows.map(r => r.id)) : []
    return { ...metadata, content: content.filter(q => q.eligible === true) }
  },
  banks: (p: { type?: BankType; organizationId?: string; size?: number; page?: number } = {}) => api.get<Paged<Bank>>('/question-banks', { params: { size: 50, ...p } }).then(r => r.data),
  personalQuestions: (bankId: string, page = 0, size = 20, text = '') => api.get<Paged<BankQuestion>>(`/question-banks/${bankId}/questions`, { params: { status: 'ACTIVE', ...(text.trim() ? { text: text.trim().slice(0, 200) } : {}), size, page } }).then(r => r.data),
  // Public
  publicList: (f: PublicFilters) => api.get<Paged<PublicQuestion>>('/public-questions', { params: { size: 20, ...f } }).then(r => r.data),
  publicGet: (id: string) => api.get<PublicQuestion>(`/public-questions/${id}`).then(r => r.data),
  bookmark: (id: string) => api.put<void>(`/public-questions/${id}/bookmark`).then(() => undefined),
  unbookmark: (id: string) => api.delete<void>(`/public-questions/${id}/bookmark`).then(() => undefined),
  publicTaxonomy: (kind: 'SUBJECT' | 'CATEGORY', text = '', subjectId?: string) => api.get<Paged<Taxonomy>>('/public-questions/taxonomy', { params: { kind, ...(text ? { text } : {}), ...(subjectId ? { subjectId } : {}), size: 100 } }).then(r => r.data),
  publicView: (id: string) => api.post<PublicViewResponse>(`/public-questions/${id}/view`).then(r => r.data),
  publicRate: (id: string, rating: number) => api.put(`/public-questions/${id}/rating`, { rating }).then(r => r.data),
  publicUnpublish: (id: string) => api.delete<void>(`/public-questions/${id}`).then(() => undefined),
  publicFork: (id: string, sourceQuestionVersion: number) => api.post<BankQuestion>(`/public-questions/${id}/fork`, { sourceQuestionVersion }).then(r => r.data),
  publicForkItem: (id: string, itemId: string, sourceQuestionVersion: number) => api.post<BankQuestion>(`/public-questions/${id}/fork-items/${itemId}`, { sourceQuestionVersion }).then(r => r.data),
  publicForkSet: (id: string) => api.post<QuestionSet>(`/public-questions/${id}/fork-set`, {}).then(r => r.data),
  questionSets: (bankId: string, page = 0, size = 20, filters: { text?: string; status?: 'ACTIVE' | 'ARCHIVED' } = {}) => api.get<Paged<QuestionSet>>(`/question-banks/${bankId}/question-sets`, { params: { ...(filters.text?.trim() ? { text: filters.text.trim().slice(0, 200) } : {}), ...(filters.status ? { status: filters.status } : {}), page, size } }).then(r => r.data),
  questionSet: (id: string) => api.get<QuestionSet>(`/question-sets/${id}`).then(r => r.data),
  createQuestionSet: (bankId: string, body: { title: string; description?: string; items: { questionId: string; questionVersion: number }[] }) => api.post<QuestionSet>(`/question-banks/${bankId}/question-sets`, body).then(r => r.data),
  updateQuestionSet: (id: string, body: { title: string; description?: string; items: { questionId: string; questionVersion: number }[]; expectedVersion: number }) => api.put<QuestionSet>(`/question-sets/${id}`, body).then(r => r.data),
  archiveQuestionSet: (id: string) => api.delete<void>(`/question-sets/${id}`).then(() => undefined),
  publishQuestionSet: (id: string, version: number) => api.post<PublicQuestion>(`/question-sets/${id}/publish-public`, { version }).then(r => r.data),
  publish: (questionId: string, sourceQuestionVersion: number) => api.post<PublicQuestion>(`/questions/${questionId}/publish-public`, { sourceQuestionVersion }).then(r => r.data),
  // Organization
  orgQuestions: (p: { bankId: string; questionType?: string; difficulty?: string; page?: number; size?: number }) => api.get<Paged<OrgQuestion>>('/organization-questions', { params: { size: 20, ...p } }).then(r => r.data),
  orgQuestion: (id: string) => api.get<OrgQuestion>(`/organization-questions/${id}`).then(r => r.data),
  orgFork: (id: string, sourceQuestionVersion: number) => api.post<BankQuestion>(`/organization-questions/${id}/fork`, { sourceQuestionVersion }).then(r => r.data),
  submit: (questionId: string, bankId: string, sourceQuestionVersion: number, submissionNote?: string) => api.post<Contribution>(`/questions/${questionId}/organization-contributions`, { bankId, sourceQuestionVersion, ...(submissionNote?.trim() ? { submissionNote: submissionNote.trim() } : {}) }).then(r => r.data),
  contributions: (p: { bankId: string; status?: string; mine: boolean; page?: number; size?: number }) => api.get<Paged<Contribution>>('/organization-contributions', { params: { size: 20, ...p } }).then(r => r.data),
  contribution: (id: string, includeAnswers: boolean) => api.get<Contribution>(`/organization-contributions/${id}`, { params: { includeAnswers } }).then(r => r.data),
  review: (id: string, decision: 'APPROVED' | 'REJECTED', reviewNote?: string) => api.post<Contribution>(`/organization-contributions/${id}/review`, { decision, ...(reviewNote ? { reviewNote } : {}) }).then(r => r.data),
  lineage: (questionId: string) => api.get<Lineage>(`/questions/${questionId}/lineage`).then(r => r.data),
  // Personal lifecycle (activate replaces the retired review step; needed so a question can reach ACTIVE and be shared)
  activate: (questionId: string, questionVersion: number) => api.post<Question>(`/questions/${questionId}/activate`, { questionVersion }).then(r => r.data),
}
