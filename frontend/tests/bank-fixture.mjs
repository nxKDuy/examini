// In-memory Bank API fixture; resource/version contracts only, never real E2E.
const page = content => ({ content, totalElements: content.length, totalPages: content.length ? 1 : 0, number: 0 })
const base = { categoryId: 'cat', categoryName: 'Toán', questionType: 'MULTIPLE_CHOICE', difficultyLevel: 'EASY', questionVersion: 3, createdBy: 'teacher', scope: 'PERSONAL', status: 'ACTIVE', createdAt: '2026-10-01T00:00:00', updatedAt: '2026-10-01T00:00:00', creationMethod: 'MANUAL', sourceType: 'NONE', options: [{ id: 'option-a', content: 'Lựa chọn A', displayOrder: 0 }, { id: 'option-b', content: 'Lựa chọn B', displayOrder: 1 }] }
let personal = { ...base, id: 'q-personal', bankId: 'b-personal', bankType: 'PERSONAL', content: 'Câu cá nhân fixture', status: 'DRAFT' }
let publication = { id: 'pub1', bankId: 'b-public', sourceQuestionId: personal.id, sourceQuestionVersion: 3, sourceOwnerUserId: 'teacher', sourceOwnerDisplayName: 'Cô Lan', publicationStatus: 'PUBLISHED', publishedAt: '2026-10-01T00:00:00', postType: 'SINGLE_QUESTION', title: 'Câu công khai fixture', postVersion: 3, itemCount: 1, subjects: [{ id: 's-math', name: 'Toán' }], categories: [{ id: 'cat', name: 'Đại số', subjectId: 's-math', subjectName: 'Toán', path: ['Đại số'] }], copiesTotal: 4, viewsTotal: 12, averageRating: 8.5, ratingCount: 2, myRating: null, question: { ...base, id: 'q-personal', content: 'Câu công khai fixture' } }
const orgQuestion = { id: 'orgq1', bankId: 'b-org', sourceQuestionId: personal.id, sourceQuestionVersion: 3, sourceOwnerUserId: 'teacher', publicationStatus: 'APPROVED', approvedAt: '2026-10-01T00:00:00', question: { ...base, content: 'Câu tổ chức fixture' } }
const contributions = ['teacher', 'other'].map((owner, i) => ({ id: `c${i}`, bankId: 'b-org', sourceQuestionId: personal.id, sourceQuestionVersion: 3, sourceOwnerUserId: owner, status: 'PENDING_REVIEW', submittedAt: '2026-10-01T00:00:00', question: { ...base, content: `Đóng góp ${owner}` } }))
const banks = [{ id: 'b-personal', type: 'PERSONAL', name: 'Kho của tôi', ownerUserId: 'teacher', defaultBank: true, status: 'ACTIVE' }, { id: 'b-public', type: 'PUBLIC', name: 'Công khai', status: 'ACTIVE' }, { id: 'b-org', type: 'ORGANIZATION', name: 'Khoa CNTT', organizationId: 'o1', status: 'ACTIVE' }]
let questionSets = []
const calls = []
export function bankFixture(url, method, body, identity) {
  const path = url.pathname
  if (path === '/__bank-fixture/reset') { personal = { ...personal, status: 'DRAFT' }; publication = { ...publication, publicationStatus: 'PUBLISHED' }; questionSets = []; calls.length = 0; return [200, {}] }
  if (path === '/__bank-fixture/calls') return [200, calls]
  if (!/^\/(questions|question-banks|question-sets|public-questions|organization-questions|organization-contributions)(\/|$)/.test(path)) return null
  calls.push({ path, method, body, params: Object.fromEntries(url.searchParams) })
  if (path === '/question-banks') return [200, page(banks.filter(b => (!url.searchParams.get('type') || b.type === url.searchParams.get('type')) && (!url.searchParams.get('organizationId') || b.organizationId === url.searchParams.get('organizationId'))))]
  if (/^\/question-banks\/[^/]+\/questions$/.test(path)) return [200, page(personal.status === 'ACTIVE' ? [{ bankId: 'b-personal', question: personal }] : [])]
  if (/^\/question-banks\/[^/]+\/question-sets$/.test(path)) {
    if (method === 'POST') {
      const q = { ...base, ...personal, status: 'ACTIVE' }
      const set = { id: 'set1', bankId: 'b-personal', ownerUserId: 'teacher', title: body.title, description: body.description, version: 1, status: 'ACTIVE', items: body.items.map((i, index) => ({ id: `item${index+1}`, position: index, sourceQuestionId: i.questionId, sourceQuestionVersion: i.questionVersion, question: q })) }
      questionSets = [set]; return [201, set]
    }
    return [200, page(questionSets)]
  }
  if (path === '/question-sets/set1/publish-public' && method === 'POST') return [201, { ...publication, postType: 'QUESTION_SET', title: questionSets[0]?.title, postVersion: body.version, itemCount: questionSets[0]?.items.length }]
  if (path === '/questions/batch') return [200, body.map(id => ({ ...base, id, eligible: true, content: id === 'pub1' ? publication.question.content : id === 'orgq1' ? orgQuestion.question.content : personal.content }))]
  if (path === '/questions') return [200, page([personal].filter(q => !url.searchParams.get('status') || q.status === url.searchParams.get('status')))]
  if (path.endsWith('/activate')) { personal = { ...personal, status: 'ACTIVE' }; return [200, personal] }
  if (path.endsWith('/publish-public')) return [201, publication]
  if (method === 'POST' && path.endsWith('/organization-contributions')) { contributions[0].submissionNote = body.submissionNote?.trim() || null; return [201, contributions[0]] }
  if (path.endsWith('/lineage')) return [404, { status: 404, detail: 'Không có lineage' }]
  if (path.endsWith('/versions')) return [200, []]
  if (path.endsWith('/reviews')) return [200, []]
  if (path === '/questions/q-personal') {
    if (method === 'DELETE') { personal = { ...personal, status: 'ARCHIVED' }; return [204, null] }
    return [200, { ...personal, options: personal.options.map((o, i) => ({ ...o, correct: i === 0 })), expectedAnswer: 'PRIVATE_KEY_FIXTURE' }]
  }
  if (path === '/public-questions/taxonomy') return [200, page(url.searchParams.get('kind') === 'SUBJECT' ? [{ id: 's-math', name: 'Toán' }] : [{ id: 'cat', name: 'Đại số', subjectId: 's-math', subjectName: 'Toán', path: ['Đại số'] }])]
  if (path === '/public-questions') {
    const mine = url.searchParams.get('mine') === 'true'
    const status = url.searchParams.get('publicationStatus')
    if (status === 'ARCHIVED' && !mine) return [403, { status: 403 }]
    return [200, page((mine ? identity === publication.sourceOwnerUserId : publication.publicationStatus === 'PUBLISHED') && (!status || status === publication.publicationStatus) ? [publication] : [])]
  }
  if (path === '/public-questions/pub1') {
    if (method === 'DELETE') { publication = { ...publication, publicationStatus: 'ARCHIVED' }; return [204, null] }
    return [200, publication]
  }
  if (path === '/public-questions/pub1/view' && method === 'POST') return [200, { publicationId: 'pub1', views24h: 1, views30d: 4, views365d: 10, viewsTotal: 13 }]
  if (path === '/public-questions/pub1/rating' && method === 'PUT') { publication = { ...publication, myRating: body.rating, ratingCount: 3, averageRating: 8.6 }; return [200, { publicationId: 'pub1', averageRating: 8.6, ratingCount: 3, myRating: body.rating }] }
  if (path === '/public-questions/pub1/fork' && publication.publicationStatus === 'ARCHIVED') return [409, { status: 409 }]
  if (path.endsWith('/fork')) return identity === 'student' ? [403, { status: 403 }] : [201, { bankId: 'b-personal', question: { ...personal, id: 'copy1', status: 'DRAFT' } }]
  if (path === '/organization-questions') return [200, page([orgQuestion])]
  if (path === '/organization-contributions') return [200, page(contributions.filter(c => url.searchParams.get('mine') !== 'true' || c.sourceOwnerUserId === identity).filter(c => !url.searchParams.get('status') || c.status === url.searchParams.get('status')))]
  const c = contributions.find(c => path.includes(`/${c.id}`))
  if (c) {
    if (path.endsWith('/review')) return [409, { status: 409, detail: 'Đóng góp vừa được xử lý ở nơi khác.' }]
    return [200, { ...c, question: url.searchParams.get('includeAnswers') === 'true' ? { ...c.question, expectedAnswer: 'REVIEW_KEY_FIXTURE', options: c.question.options.map((o, i) => ({ ...o, correct: i === 0 })) } : c.question }]
  }
  return [404, { status: 404 }]
}
