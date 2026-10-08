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

test('discovery params: blanks dropped, text capped at 200, author filter only when chosen', () => {
  assert.deepEqual(m.publicParams({ text: '  ', questionType: '', difficulty: '', author: '', page: 0 }), { page: 0 })
  const long = 'x'.repeat(300)
  assert.equal(m.publicParams({ text: long, questionType: 'ESSAY', difficulty: 'HARD', author: 'u1', page: 2 }).text.length, 200)
  assert.deepEqual(m.publicParams({ text: ' đạo hàm ', questionType: 'ESSAY', difficulty: '', author: 'u1', page: 2 }), { text: 'đạo hàm', questionType: 'ESSAY', sourceOwnerUserId: 'u1', page: 2 })
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

test('Activate sends the displayed version, submit and publish preserve source version and independent workflow', async () => {
  const requests = []
  api.defaults.adapter = async config => { requests.push(config); return { status: 200, statusText: 'OK', config, headers: {}, data: {} } }
  await bankApi.activate('q', 7); await bankApi.submit('q', 'b', 7); await bankApi.publish('q', 7)
  assert.deepEqual(requests.map(r => r.url), ['/questions/q/activate', '/questions/q/organization-contributions', '/questions/q/publish-public'])
  assert.deepEqual(requests.map(r => JSON.parse(r.data)), [{ questionVersion: 7 }, { bankId: 'b', sourceQuestionVersion: 7 }, { sourceQuestionVersion: 7 }])
})

test('Vite proxy sends Bank/Organization API to gateway while document refresh loads SPA', async () => {
  const { default: config } = await import('../vite.config.ts')
  for (const prefix of ['/organizations', '/question-banks', '/organization-contributions', '/organization-questions', '/public-questions', '/public-contributions']) {
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
