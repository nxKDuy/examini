import { test } from 'node:test'
import assert from 'node:assert/strict'
import { registerHooks } from 'node:module'
import { readFileSync, existsSync } from 'node:fs'
import ts from 'typescript'

registerHooks({
  resolve(specifier, context, next) {
    if (specifier.startsWith('.') && context.parentURL?.includes('/src/')) {
      const url = new URL(specifier, context.parentURL)
      for (const ext of ['.ts', '.tsx']) if (existsSync(new URL(url.href + ext))) return { url: url.href + ext, shortCircuit: true }
    }
    return next(specifier, context)
  },
  load(url, context, next) {
    if (url.endsWith('.css')) return { format: 'module', shortCircuit: true, source: 'export default {}' }
    if (/\.tsx?$/.test(url)) return { format: 'module', shortCircuit: true, source: ts.transpileModule(readFileSync(new URL(url), 'utf8').replace('import.meta.env.VITE_API_BASE_URL', "'/'"), { compilerOptions: { module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX } }).outputText }
    return next(url, context)
  },
})
const m = await import('../src/features/banks/bankModel.ts')
const org = (capabilities, status = 'ACTIVE') => ({ status, me: { role: 'MEMBER', canReview: false, capabilities } })

test('org bank sub-tabs come from capabilities: readers see approved + mine + own history, only BANK_REVIEW adds queue, no BANK_READ shows nothing', () => {
  assert.deepEqual(m.orgBankTabs(org(['BANK_READ'])), ['approved', 'mine', 'history'])
  assert.deepEqual(m.orgBankTabs(org(['BANK_READ', 'BANK_USE', 'BANK_SUBMIT', 'BANK_REVIEW'])), ['approved', 'mine', 'queue', 'history'])
  assert.deepEqual(m.orgBankTabs(org([])), []); assert.deepEqual(m.orgBankTabs({}), [])
  assert.equal(m.showBankTab(org(['BANK_READ'])), true); assert.equal(m.showBankTab(org([])), false)
})

test('contribution lists ask the server correctly: mine=true for own, mine=false only for reviewers (queue = PENDING_REVIEW)', () => {
  assert.deepEqual(m.contributionQuery('mine'), { mine: true })
  assert.deepEqual(m.contributionQuery('queue'), { mine: false, status: 'PENDING_REVIEW' })
  assert.deepEqual(m.contributionQuery('history'), { mine: false }); assert.deepEqual(m.contributionQuery('history', false), { mine: true }); assert.equal(m.contributionQuery('approved'), null)
})

test('review: only a reviewer, only PENDING, never your own contribution (self-review is blocked up front with a reason)', () => {
  const c = (status, owner) => ({ status, sourceOwnerUserId: owner })
  assert.deepEqual(m.reviewState(c('PENDING_REVIEW', 'other'), true, 'me'), { canAct: true, note: null })
  assert.equal(m.reviewState(c('PENDING_REVIEW', 'me'), true, 'me').canAct, false); assert.match(m.reviewState(c('PENDING_REVIEW', 'me'), true, 'me').note, /tự duyệt/)
  assert.equal(m.reviewState(c('PENDING_REVIEW', 'other'), false, 'me').canAct, false)
  for (const s of ['APPROVED', 'REJECTED']) assert.equal(m.reviewState(c(s, 'other'), true, 'me').canAct, false, s)
  assert.equal(m.reviewState(c('PENDING_REVIEW', 'other'), true, undefined).canAct, false) // unresolved identity fails closed
  assert.equal(m.rejectReady('  '), false); assert.equal(m.rejectReady('Sai đáp án'), true)
})

test('submit targets are ACTIVE organizations where the caller has BANK_SUBMIT; only ACTIVE personal questions can be shared', () => {
  const list = [{ id: 'a', ...org(['BANK_READ', 'BANK_SUBMIT']) }, { id: 'b', ...org(['BANK_READ']) }, { id: 'c', ...org(['BANK_READ', 'BANK_SUBMIT'], 'ARCHIVED') }]
  assert.deepEqual(m.submitTargets(list).map(o => o.id), ['a'])
  assert.equal(m.canShare({ status: 'ACTIVE' }), true); for (const s of ['DRAFT', 'ARCHIVED', 'PENDING_REVIEW']) assert.equal(m.canShare({ status: s }), false, s)
})

test('public: unpublish only by the owner of a live publication; fork only for a Teacher on a live one; archived is labelled, author falls back to Unknown user', () => {
  const p = (status, owner = 'me') => ({ publicationStatus: status, sourceOwnerUserId: owner })
  assert.equal(m.canUnpublish(p('PUBLISHED'), 'me'), true); assert.equal(m.canUnpublish(p('PUBLISHED', 'x'), 'me'), false)
  assert.equal(m.canUnpublish(p('ARCHIVED'), 'me'), false); assert.equal(m.canUnpublish(p('PUBLISHED'), undefined), false)
  assert.equal(m.canForkPublic(p('PUBLISHED'), true), true); assert.equal(m.canForkPublic(p('PUBLISHED'), false), false); assert.equal(m.canForkPublic(p('ARCHIVED'), true), false)
  assert.equal(m.publicationBadge(p('ARCHIVED')).text, 'Đã gỡ công khai'); assert.equal(m.publicationBadge(p('PUBLISHED')).tone, 'success')
  assert.equal(m.authorName({ sourceOwnerDisplayName: ' Cô Lan ' }), 'Cô Lan'); assert.equal(m.authorName({}), 'Unknown user')
})

test('copy tag distinguishes a copied question from a partially copied set', () => {
  assert.equal(m.copiedBadge({ isCopied: false }), null)
  assert.equal(m.copiedBadge({ isCopied: true, postType: 'SINGLE_QUESTION' }), 'Đã sao chép')
  assert.equal(m.copiedBadge({ isCopied: true, postType: 'QUESTION_SET', itemCount: 2, items: [{ isCopied: true }, { isCopied: false }] }), 'Đã sao chép một phần')
  assert.equal(m.copiedBadge({ isCopied: true, postType: 'QUESTION_SET', itemCount: 2, items: [{ isCopied: true }, { isCopied: true }] }), 'Đã sao chép')
})

test('discovery params: blanks dropped, text capped at 200, author filter only when chosen', () => {
  assert.deepEqual(m.publicParams({ text: '  ', questionType: '', difficulty: '', author: '', page: 0 }), { page: 0 })
  const long = 'x'.repeat(300)
  assert.equal(m.publicParams({ text: long, questionType: 'ESSAY', difficulty: 'HARD', author: 'u1', page: 2 }).text.length, 200)
  assert.deepEqual(m.publicParams({ text: ' đạo hàm ', questionType: 'ESSAY', difficulty: '', author: 'u1', page: 2 }), { text: 'đạo hàm', questionType: 'ESSAY', sourceOwnerUserId: 'u1', page: 2 })
})

test('Public discovery keeps taxonomy, post kind and rolling copy/rating ranking as explicit server filters', () => {
  const base = { text: '', questionType: '', difficulty: '', author: '', page: 0 }
  assert.deepEqual(m.publicParams({ ...base, subjectId: 's1', categoryId: 'c1', postType: 'QUESTION_SET', ranking: 'COPY_MONTH' }), { subjectId: 's1', categoryId: 'c1', postType: 'QUESTION_SET', ranking: 'COPY_MONTH', page: 0 })
  assert.equal(m.publicParams({ ...base, ranking: 'RATING_YEAR' }).ranking, 'RATING_YEAR')
  assert.equal(m.publicParams(base).ranking, undefined)
})

test('provenance chain shows only what lineage says: source -> publication it came through -> this copy; no lineage means no chain', () => {
  assert.deepEqual(m.provenanceChain(null), [])
  const pub = m.provenanceChain({ questionId: 'q', sourceQuestionId: 'abcdef12-0000', sourceQuestionVersion: 3, sourceBankType: 'PERSONAL', sourcePublicQuestionId: '99999999-0000', copiedAt: '2026-10-08T00:00:00Z' })
  assert.deepEqual(pub.map(l => l.key), ['source', 'public', 'here']); assert.match(pub[0].detail, /Phiên bản 3/)
  const org = m.provenanceChain({ questionId: 'q', sourceQuestionId: 'abcdef12-0000', sourceQuestionVersion: 1, sourceBankType: 'ORGANIZATION', sourceOrganizationQuestionId: '88888888-0000' })
  assert.deepEqual(org.map(l => l.key), ['source', 'org', 'here'])
  assert.equal(JSON.stringify(pub).includes('@'), false) // never an e-mail
})

test('helpers: 503 on publish means the author lookup failed (nothing published); excerpt trims whitespace and length', () => {
  assert.equal(m.isAuthorLookupFailure({ response: { status: 503 } }), true); assert.equal(m.isAuthorLookupFailure({ response: { status: 409 } }), false); assert.equal(m.isAuthorLookupFailure(new Error('x')), false)
  assert.equal(m.excerpt('  a   b \n c ', 50), 'a b c'); assert.equal(m.excerpt('x'.repeat(200), 20).length, 20)
})

test('Question Set confirmation dispatch uses the explicit action and preserves user titles such as archive:algebra', () => {
  const set = { id: 'set-1', title: 'archive:algebra', version: 4, items: [] }
  const calls = []
  m.dispatchQuestionSetConfirmation({ action: 'publish', set }, { archive: value => calls.push(['archive', value]), publish: value => calls.push(['publish', value]) })
  assert.deepEqual(calls, [['publish', set]])
  assert.equal(set.title, 'archive:algebra')
  m.dispatchQuestionSetConfirmation({ action: 'archive', set }, { archive: value => calls.push(['archive', value]), publish: value => calls.push(['publish', value]) })
  assert.deepEqual(calls.map(([action]) => action), ['publish', 'archive'])
})

test('public detail is scoped by actor, view counts come from the response, and forks invalidate the matching personal caches', () => {
  const post = { id: 'p1', viewsTotal: 10, views24h: 2, views30d: 8, views365d: 9, myRating: 8, publicationStatus: 'ARCHIVED' }
  const refreshed = m.applyPublicViewStats(post, { publicationId: 'p1', viewsTotal: 14, views24h: 3, views30d: 11, views365d: 13 })
  assert.deepEqual([refreshed.viewsTotal, refreshed.views24h, refreshed.views30d, refreshed.views365d], [14, 3, 11, 13])
  assert.equal(refreshed.myRating, 8)
  assert.notDeepEqual(m.publicDetailQueryKey('p1', 'owner'), m.publicDetailQueryKey('p1', 'student'))
  assert.deepEqual(m.personalForkQueryKeys('teacher-1', false), [['questions', 'teacher-1'], ['banks', 'personal-set-questions']])
  assert.deepEqual(m.personalForkQueryKeys('teacher-1', true), [['questions', 'teacher-1'], ['banks', 'personal-set-questions'], ['banks', 'question-sets']])
})

const storage = new Map()
globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
globalThis.window = { localStorage: globalThis.localStorage }
const { bankApi } = await import('../src/features/banks/bankApi.ts')
const { default: api } = await import('../src/lib/api.ts')
const { navigationFor, audienceOf } = await import('../src/components/layout/navigation.ts')
const { tabsForOrg } = await import('../src/features/organizations/orgModel.ts')
const { QUESTION_STATUS } = await import('../src/components/statusDefs.tsx')
const React = await import('react')
const { renderToStaticMarkup } = await import('react-dom/server')
const { QuestionView } = await import('../src/features/banks/BankPanel.tsx')
const { QueryClient } = await import('@tanstack/react-query')
const { optimisticallySetBookmark, rollbackBookmark } = await import('../src/features/banks/publicBookmarkCache.ts')

test('Personal lifecycle labels are independent from Organization review; bank-aware eligibility fails closed', () => {
  assert.deepEqual(Object.keys(QUESTION_STATUS), ['DRAFT', 'ACTIVE', 'ARCHIVED'])
  assert.doesNotMatch(QUESTION_STATUS.DRAFT.help, /Gửi duyệt/)
  assert.equal(m.canPickQuestion({ status: 'ACTIVE' }), false)
  assert.equal(m.canPickQuestion({ status: 'ACTIVE', eligible: false }), false)
  assert.equal(m.canPickQuestion({ bankType: 'ORGANIZATION', publicationStatus: 'APPROVED', eligible: true }), true)
})

test('Public navigation for Student and Teacher; Org tab uses BANK_READ rather than role or persona', () => {
  for (const a of ['STUDENT', 'TEACHER']) assert(navigationFor(a).flatMap(g => g.items).some(i => i.to === '/public-bank'))
  assert.equal(audienceOf({ persona: 'TEACHER', systemPrivilege: 'NONE' }), 'TEACHER')
  assert(tabsForOrg(org(['BANK_READ'])).includes('bank'))
  assert(!tabsForOrg({ me: { role: 'OWNER', capabilities: [] } }).includes('bank'))
})

test('Candidate-safe renderer omits even unexpected correctness/key fields; reviewer rendering is explicit', () => {
  const question = { content: 'Safe content', questionType: 'MULTIPLE_CHOICE', difficultyLevel: 'EASY', questionVersion: 1, expectedAnswer: 'SECRET_KEY', options: [{ id: 'o', content: 'Option', displayOrder: 0, correct: true }] }
  const safe = renderToStaticMarkup(React.createElement(QuestionView, { question }))
  assert.doesNotMatch(safe, /SECRET_KEY|Đáp án đúng|success-soft/)
  const reviewer = renderToStaticMarkup(React.createElement(QuestionView, { question, showAnswers: true }))
  assert.match(reviewer, /SECRET_KEY/); assert.match(reviewer, /Đáp án đúng/)
})

test('Picker uses the exact bank list and publication resource ID, then provider eligible batch without answer key', async () => {
  for (const type of ['PERSONAL', 'SYSTEM', 'PUBLIC', 'ORGANIZATION']) {
    const requests = []
    api.defaults.adapter = async config => {
      requests.push(config)
      const data = config.url === '/questions/batch'
        ? [{ id: 'publication', bankType: type, eligible: true }, { id: 'unsafe', eligible: false }]
        : { content: type === 'PUBLIC' || type === 'ORGANIZATION' ? [{ id: 'publication', sourceQuestionId: 'original' }] : [{ bankId: 'b', question: { id: 'publication' } }], totalElements: 1, totalPages: 1, number: 0 }
      return { status: 200, statusText: 'OK', config, headers: {}, data }
    }
    const result = await bankApi.pickerList({ id: 'b', type }, { page: 0, size: 10 })
    assert.deepEqual(result.content.map(q => q.id), ['publication'])
    assert.equal(requests[0].url, type === 'PUBLIC' ? '/public-questions' : type === 'ORGANIZATION' ? '/organization-questions' : '/question-banks/b/questions')
    assert.deepEqual(JSON.parse(requests[1].data), ['publication'])
    assert.deepEqual(requests[1].params, { eligible: true, includeAnswers: false })
    assert(!requests.some(r => r.params?.status === 'APPROVED'))
  }
})

test('Public Question Set picker submits item resource IDs, never the parent post ID', async () => {
  const requests = []
  api.defaults.adapter = async config => {
    requests.push(config)
    const data = config.url === '/questions/batch' ? [{ id: 'item-1', eligible: true }, { id: 'item-2', eligible: true }] : { content: [{ id: 'post-1', postType: 'QUESTION_SET', items: [{ id: 'item-1' }, { id: 'item-2' }] }], totalElements: 1, totalPages: 1, number: 0 }
    return { status: 200, statusText: 'OK', config, headers: {}, data }
  }
  const result = await bankApi.pickerList({ id: 'public-bank', type: 'PUBLIC' }, { page: 0, size: 10 })
  assert.deepEqual(result.content.map(q => q.id), ['item-1', 'item-2'])
  assert.deepEqual(JSON.parse(requests[1].data), ['item-1', 'item-2'])
  assert.deepEqual(requests[1].params, { eligible: true, includeAnswers: false })
})

test('Question Set adapters preserve selected versions and explicit publish snapshot version', async () => {
  const calls = []
  api.defaults.adapter = async config => { calls.push(config); return { status: 200, statusText: 'OK', config, headers: {}, data: {} } }
  await bankApi.createQuestionSet('personal', { title: 'Bộ', items: [{ questionId: 'q1', questionVersion: 4 }] })
  await bankApi.updateQuestionSet('set1', { title: 'Bộ sửa', items: [{ questionId: 'q1', questionVersion: 4 }], expectedVersion: 2 })
  await bankApi.publishQuestionSet('set1', 3)
  assert.deepEqual(calls.map(c => c.url), ['/question-banks/personal/question-sets', '/question-sets/set1', '/question-sets/set1/publish-public'])
  assert.deepEqual(JSON.parse(calls[0].data), { title: 'Bộ', items: [{ questionId: 'q1', questionVersion: 4 }] })
  assert.equal(JSON.parse(calls[1].data).expectedVersion, 2)
  assert.deepEqual(JSON.parse(calls[2].data), { version: 3 })
})

test('Personal Question Set search adapters use provider-owned literal search and server pagination/status', async () => {
  const calls = []
  api.defaults.adapter = async config => { calls.push(config); return { status: 200, statusText: 'OK', config, headers: {}, data: { content: [], totalElements: 0, totalPages: 0, number: 0 } } }
  await bankApi.personalQuestions('personal-bank', 3, 20, '  đạo hàm  ')
  await bankApi.questionSets('personal-bank', 2, 20, { text: ' hàm số ', status: 'ACTIVE' })
  await bankApi.questionSet('set-1')
  assert.deepEqual(calls.map(c => c.url), ['/question-banks/personal-bank/questions', '/question-banks/personal-bank/question-sets', '/question-sets/set-1'])
  assert.deepEqual(calls[0].params, { status: 'ACTIVE', text: 'đạo hàm', size: 20, page: 3 })
  assert.deepEqual(calls[1].params, { text: 'hàm số', status: 'ACTIVE', page: 2, size: 20 })
})

test('Bookmarks use authenticated API routes and saved query composes with discovery filters', async () => {
  const calls = []
  api.defaults.adapter = async config => { calls.push(config); return { status: 204, statusText: 'No Content', config, headers: {}, data: undefined } }
  await bankApi.bookmark('p1'); await bankApi.unbookmark('p1');
  await bankApi.publicList({ saved: true, text: 'algebra', subjectId: 's1', ranking: 'COPY_MONTH', page: 2 })
  assert.deepEqual(calls.slice(0,2).map(c => [c.method,c.url]), [['put','/public-questions/p1/bookmark'],['delete','/public-questions/p1/bookmark']])
  assert.deepEqual(calls[2].params, { size: 20, saved: true, text: 'algebra', subjectId: 's1', ranking: 'COPY_MONTH', page: 2 })
})

test('Optimistic bookmarks are actor-scoped and failure restores exact list and detail state', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const post = { id: 'p1', isSaved: false }
  const teacherKey = ['banks','public','teacher',{ page: 0 }], studentKey = ['banks','public','student',{ page: 0 }]
  client.setQueryData(teacherKey, { content: [post], totalElements: 1, totalPages: 1, number: 0 })
  client.setQueryData(studentKey, { content: [{ ...post, isSaved: true }], totalElements: 1, totalPages: 1, number: 0 })
  const detailKey = m.publicDetailQueryKey('p1','teacher')
  client.setQueryData(detailKey, post)
  const snapshot = await optimisticallySetBookmark(client, 'p1', 'teacher', true)
  assert.equal(client.getQueryData(teacherKey).content[0].isSaved, true)
  assert.equal(client.getQueryData(studentKey).content[0].isSaved, true)
  assert.equal(client.getQueryData(detailKey).isSaved, true)
  rollbackBookmark(client, snapshot)
  assert.equal(client.getQueryData(teacherKey).content[0].isSaved, false)
  assert.equal(client.getQueryData(studentKey).content[0].isSaved, true)
  assert.equal(client.getQueryData(detailKey).isSaved, false)
  client.clear()
})

test('Public view adapter returns authoritative aggregate counts from the explicit detail-view endpoint', async () => {
  let request
  api.defaults.adapter = async config => { request = config; return { status: 200, statusText: 'OK', config, headers: {}, data: { publicationId: 'post-1', views24h: 2, views30d: 5, views365d: 8, viewsTotal: 9 } } }
  const response = await bankApi.publicView('post-1')
  assert.equal(request.url, '/public-questions/post-1/view')
  assert.deepEqual(response, { publicationId: 'post-1', views24h: 2, views30d: 5, views365d: 8, viewsTotal: 9 })
})

test('Activate sends the displayed version, submit and publish preserve source version and independent workflow', async () => {
  const requests = []
  api.defaults.adapter = async config => { requests.push(config); return { status: 200, statusText: 'OK', config, headers: {}, data: {} } }
  await bankApi.activate('q', 7); await bankApi.submit('q', 'b', 7); await bankApi.publish('q', 7)
  assert.deepEqual(requests.map(r => r.url), ['/questions/q/activate', '/questions/q/organization-contributions', '/questions/q/publish-public'])
  assert.deepEqual(requests.map(r => JSON.parse(r.data)), [{ questionVersion: 7 }, { bankId: 'b', sourceQuestionVersion: 7 }, { sourceQuestionVersion: 7 }])
})

test('Vite proxy sends Bank/Organization API to gateway while document refresh loads SPA', async () => {
  const { default: config } = await import('../vite.config.ts')
  for (const prefix of ['/organizations', '/question-banks', '/question-sets', '/organization-contributions', '/organization-questions', '/public-questions', '/public-contributions']) {
    const proxy = config.server.proxy[prefix]
    assert.equal(proxy.bypass({ headers: { accept: 'text/html' } }), '/index.html')
    assert.equal(proxy.bypass({ headers: { accept: 'application/json' } }), undefined)
  }
})

test('Personal edits and archival carry exact revision; provider conflict is not retried by the API adapter', async () => {
  const { questionBankApi } = await import('../src/features/question-bank/questionApi.ts')
  const calls = []
  api.defaults.adapter = async config => {
    calls.push(config)
    if (config.method === 'delete') throw Object.assign(new Error('stale version'), { response: { status: 409, data: { detail: 'Phiên bản đã thay đổi' } }, config })
    return { status: 200, statusText: 'OK', config, headers: {}, data: { id: 'q', status: 'DRAFT', questionVersion: 8 } }
  }
  const created = await questionBankApi.createQuestion({ content: 'New personal question' })
  assert.equal(created.status, 'DRAFT')
  const edited = await questionBankApi.updateQuestion('q', { content: 'New version', questionVersion: 7 })
  assert.equal(edited.questionVersion, 8)
  await assert.rejects(questionBankApi.archive({ id: 'q', questionVersion: 8 }), /stale version/)
  assert.equal(calls.length, 3)
  assert.deepEqual(calls[2].params, { questionVersion: 8 })
  assert.equal(JSON.parse(calls[1].data).questionVersion, 7)
})

test('Author question create/update forwards optional frozen explanation, including empty-string clear', async () => {
  const { questionBankApi } = await import('../src/features/question-bank/questionApi.ts')
  const calls = []
  api.defaults.adapter = async config => { calls.push(config); return { status: 200, statusText: 'OK', config, headers: {}, data: {} } }
  await questionBankApi.createQuestion({ explanation: 'Giải thích nguồn' })
  await questionBankApi.updateQuestion('q1', { questionVersion: 4, explanation: '' })
  assert.equal(JSON.parse(calls[0].data).explanation, 'Giải thích nguồn')
  assert.equal(JSON.parse(calls[1].data).explanation, '')
})

test('Archived organizations never expose queue even with stale reviewer capability', () => {
  assert.deepEqual(m.orgBankTabs(org(['BANK_READ', 'BANK_REVIEW'], 'ARCHIVED')), ['approved', 'mine', 'history'])
})

test('Locked adapter: mine-only archived discovery never leaks archive filters into public search', () => {
  const common = { text: '', questionType: '', difficulty: '', author: 'me', page: 0 }
  assert.deepEqual(m.publicParams({ ...common, mine: true, publicationStatus: 'ARCHIVED' }), { sourceOwnerUserId: 'me', mine: true, publicationStatus: 'ARCHIVED', page: 0 })
  assert.deepEqual(m.publicParams({ ...common, mine: false, publicationStatus: 'ARCHIVED' }), { sourceOwnerUserId: 'me', page: 0 })
})

test('Locked adapter: contribution note is trimmed and separate from review note', async () => {
  const calls = []
  api.defaults.adapter = async config => { calls.push(config); return { status: 200, statusText: 'OK', config, headers: {}, data: {} } }
  await bankApi.submit('q', 'b', 3, '  Ghi chú gửi  ')
  await bankApi.review('c', 'REJECTED', 'Lý do từ chối')
  assert.deepEqual(JSON.parse(calls[0].data), { bankId: 'b', sourceQuestionVersion: 3, submissionNote: 'Ghi chú gửi' })
  assert.deepEqual(JSON.parse(calls[1].data), { decision: 'REJECTED', reviewNote: 'Lý do từ chối' })
})

test('Locked adapter: lineage respects unavailable metadata and frozen SOURCE; bank names are never author names', () => {
  const chain = m.provenanceChain({ nodes: [
    { relation: 'SOURCE', id: 'q-source', version: 3, bankType: 'PERSONAL', status: 'ACTIVE', name: null, available: true, frozen: true },
    { relation: 'PUBLICATION', id: 'p-source', version: 3, bankType: 'PUBLIC', status: 'ARCHIVED', name: 'MUST_NOT_SHOW', available: false, frozen: true },
    { relation: 'COPY', id: 'q-copy', version: 1, bankType: 'PERSONAL', status: 'DRAFT', name: 'Kho của tôi', available: true, frozen: false },
  ] })
  assert.match(chain[0].detail, /Đang dùng.*Bản chụp cố định/)
  assert.match(chain[1].detail, /không khả dụng/); assert.doesNotMatch(chain[1].detail, /MUST_NOT_SHOW|Đã gỡ/)
  assert.match(chain[2].detail, /Bản nháp.*Kho của tôi/)
  assert.doesNotMatch(chain.map(c => c.detail).join(' '), /Tác giả|Unknown user/)
})

test('Save/Saved exist only for Teacher and Student audiences: ADMIN (global admin) and unknown audiences get none, and TEACHER is never inferred from ADMIN', () => {
  assert.equal(m.canBookmark('TEACHER'), true); assert.equal(m.canBookmark('STUDENT'), true)
  assert.equal(m.canBookmark('ADMIN'), false); assert.equal(m.canBookmark(''), false); assert.equal(m.canBookmark('teacher'), false)
})

test('card helpers: update date prefers the content edit time, topic is the broadest taxonomy, stats are real numbers with honest gaps', () => {
  assert.match(m.updatedDate({ contentUpdatedAt: '2026-10-05T08:00:00', publishedAt: '2026-09-20T00:00:00' }), /05\/10\/2026/)
  assert.match(m.updatedDate({ publishedAt: '2026-09-20T00:00:00' }), /20\/09\/2026/); assert.equal(m.updatedDate({}), null)
  const q = { categoryName: 'Toán' }
  assert.deepEqual(m.broadTopic({ subjects: [{ id: 'a', name: 'Toán' }, { id: 'b', name: 'Lý' }, { id: 'c', name: 'Toán' }], categories: [], question: q }), { text: 'Toán', more: 1 })
  assert.deepEqual(m.broadTopic({ subjects: [], categories: [{ id: 'x', name: 'Thơ hiện đại', path: ['Văn học', 'Thơ hiện đại'] }, { id: 'y', name: 'Truyện', path: ['Văn học', 'Truyện'] }], question: q }), { text: 'Văn học', more: 0 })
  assert.deepEqual(m.broadTopic({ subjects: [], categories: [], question: q }), { text: 'Toán', more: 0 }); assert.equal(m.broadTopic({ subjects: [], categories: [], question: {} }), null)
  const label = s => Object.fromEntries(s.map(x => [x.key, x.label]))
  assert.deepEqual(label(m.postStats({ viewsTotal: 12, copiesTotal: 4, averageRating: 8.46, ratingCount: 2 })), { views: '12 lượt xem', copies: '4 lượt sao chép', rating: '8.5/10 từ 2 đánh giá' })
  assert.equal(m.postStats({ viewsTotal: 12, copiesTotal: 4, averageRating: 8.5, ratingCount: 2 }).find(x => x.key === 'rating').value, '8.5/10 (2)')
  assert.equal(m.postStats({ viewsTotal: 0, copiesTotal: 0, averageRating: null, ratingCount: 0 }).find(x => x.key === 'rating').label, 'Chưa có đánh giá')
  const missing = m.postStats({}); assert.deepEqual(missing.map(x => x.value), ['—', '—', '—']); assert.match(missing[0].label, /Chưa có số liệu xem/) // unknown is never shown as 0
  assert.equal(m.postStats({ viewsTotal: 0, copiesTotal: 0, ratingCount: 0 })[0].value, '0') // a real zero stays 0
})
