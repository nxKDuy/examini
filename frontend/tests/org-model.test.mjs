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
    if (/\.tsx?$/.test(url)) return { format: 'module', shortCircuit: true, source: ts.transpileModule(readFileSync(new URL(url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText }
    return next(url, context)
  },
})
const m = await import('../src/features/organizations/orgModel.ts')
const member = (o = {}) => ({ membershipId: 'm1', userId: '11111111-2222-3333-4444-555555555555', role: 'MEMBER', status: 'ACTIVE', canReview: false, revision: 3, ...o })

test('tabs come from the real membership: members only see Thành viên, ADMIN adds Lời mời + Nhật ký, only OWNER sees Cài đặt', () => {
  assert.deepEqual(m.tabsFor('MEMBER'), ['members']); assert.deepEqual(m.tabsFor('ADMIN'), ['members', 'invitations', 'audit'])
  assert.deepEqual(m.tabsFor('OWNER'), ['members', 'invitations', 'audit', 'settings']); assert.deepEqual(m.tabsFor(undefined), [])
  assert.equal(m.parseTab('settings', m.tabsFor('ADMIN')), 'members') // a hidden tab in the URL falls back, never opens
  assert.equal(m.parseTab('audit', m.tabsFor('ADMIN')), 'audit')
})

test('a Student with an explicit ADMIN membership manages exactly like any ADMIN (persona is not consulted)', () => {
  const viewer = { role: 'ADMIN', membershipId: 'me' } // role comes from `me`, nothing here knows about personas
  const a = m.memberAbilities(viewer, member({ membershipId: 'x' }), false)
  assert.deepEqual([a.changeRole, a.toggleReview, a.remove], [false, true, true])
  assert.equal(m.memberAbilities(viewer, member({ role: 'ADMIN' }), false).any, false) // ADMIN does not touch another ADMIN
  assert.equal(m.memberAbilities(viewer, member({ role: 'OWNER' }), false).any, false)
})

test('OWNER manages ADMIN/MEMBER, never OWNER or self; canReview exists only for MEMBER', () => {
  const o = { role: 'OWNER', membershipId: 'me' }
  assert.deepEqual(m.memberAbilities(o, member({ role: 'MEMBER' }), false), { changeRole: true, toggleReview: true, remove: true, any: true, note: null })
  const admin = m.memberAbilities(o, member({ role: 'ADMIN' }), false)
  assert.deepEqual([admin.changeRole, admin.toggleReview, admin.remove], [true, false, true])
  assert.equal(m.memberAbilities(o, member({ role: 'OWNER' }), false).any, false)
  assert.equal(m.memberAbilities(o, member({ membershipId: 'me' }), false).any, false) // leave is a different action
})

test('MEMBER, archived organizations and ended memberships offer no member actions', () => {
  assert.equal(m.memberAbilities({ role: 'MEMBER', membershipId: 'me' }, member(), false).any, false)
  assert.equal(m.memberAbilities({ role: 'OWNER', membershipId: 'me' }, member(), true).any, false)
  for (const status of ['REVOKED', 'LEFT']) assert.equal(m.memberAbilities({ role: 'OWNER', membershipId: 'me' }, member({ status }), false).any, false)
})

test('invite roles: OWNER may invite ADMIN, ADMIN only MEMBER, MEMBER nothing; archived blocks inviting and managing', () => {
  assert.deepEqual(m.inviteRolesFor('OWNER'), ['MEMBER', 'ADMIN']); assert.deepEqual(m.inviteRolesFor('ADMIN'), ['MEMBER']); assert.deepEqual(m.inviteRolesFor('MEMBER'), [])
  assert.equal(m.canInvite({ status: 'ACTIVE', me: { role: 'ADMIN' } }), true); assert.equal(m.canInvite({ status: 'ARCHIVED', me: { role: 'OWNER' } }), false)
  assert.equal(m.canInvite({ status: 'ACTIVE', me: { role: 'MEMBER' } }), false); assert.equal(m.canManageOrg({ status: 'ACTIVE', me: { role: 'ADMIN' } }), false)
  assert.equal(m.canManageOrg({ status: 'ACTIVE', me: { role: 'OWNER' } }), true); assert.equal(m.canManageOrg({ status: 'ARCHIVED', me: { role: 'OWNER' } }), false)
})

test('recipient box: UUID becomes userId, an e-mail is sent exactly as typed, anything else is rejected', () => {
  const id = '3f2b8c1e-1111-4222-8333-444455556666'
  assert.deepEqual(m.parseRecipient(`  ${id}  `), { userId: id })
  assert.deepEqual(m.parseRecipient('Hoc.Sinh@Example.com'), { email: 'Hoc.Sinh@Example.com' }) // no lowercase/normalising
  for (const bad of ['', 'abc', 'a@b', '@x.com', 'x y@z.com', '1234']) assert.equal(m.parseRecipient(bad), null, bad)
  assert.equal(m.parseRecipient('a@b.co'.padEnd(300, 'x') + '.com'), null) // over 254 characters
})

test('member patch sends only what changed with the member revision; promoting to ADMIN never sends a stale canReview', () => {
  const base = member({ revision: 9 })
  assert.equal(m.memberPatch(base, { role: 'MEMBER', canReview: false }), null)
  assert.deepEqual(m.memberPatch(base, { role: 'MEMBER', canReview: true }), { revision: 9, canReview: true })
  assert.deepEqual(m.memberPatch(base, { role: 'ADMIN', canReview: true }), { revision: 9, role: 'ADMIN' })
  const reviewer = member({ canReview: true, revision: 2 })
  assert.deepEqual(m.memberPatch(reviewer, { role: 'ADMIN', canReview: true }), { revision: 2, role: 'ADMIN' })
  assert.deepEqual(m.memberPatch(member({ role: 'ADMIN', revision: 5 }), { role: 'MEMBER', canReview: false }), { revision: 5, role: 'MEMBER' })
  assert.equal(m.memberPatch(base, { role: 'OWNER', canReview: false }), null) // OWNER is never a patch target role
})

test('stale revision is only the 409 "Dữ liệu đã thay đổi"; last-owner and lifecycle 409s are ordinary errors', () => {
  const err = (status, detail) => ({ response: { status, data: { detail } } })
  assert.equal(m.isStaleRevision(err(409, 'Dữ liệu đã thay đổi (revision hiện tại 4)')), true)
  assert.equal(m.isStaleRevision(err(409, 'OWNER cuối cùng phải chuyển quyền sở hữu trước khi rời')), false)
  assert.equal(m.isStaleRevision(err(500, 'Dữ liệu đã thay đổi (revision hiện tại 4)')), false); assert.equal(m.isStaleRevision(new Error('x')), false)
})

test('missing display names fall back to a short id instead of guessing an identity; counts are never invented', () => {
  assert.deepEqual(m.memberName(member({ displayName: ' Lan ' })), { text: 'Lan', fallback: false })
  assert.deepEqual(m.memberName(member()), { text: 'Thành viên 11111111', fallback: true })
  assert.deepEqual(m.recipientName({ recipientUserId: 'abcdef12-0000' }), { text: 'Người dùng abcdef12', fallback: true })
})

test('ownership transfer candidates are active ADMIN/MEMBER other than the caller; typed confirmation needs the exact name', () => {
  const list = [member({ membershipId: 'o', role: 'OWNER' }), member({ membershipId: 'a', role: 'ADMIN' }), member({ membershipId: 'me' }), member({ membershipId: 'g', status: 'LEFT' }), member({ membershipId: 'b' })]
  assert.deepEqual(m.transferTargets(list, 'me').map(x => x.membershipId), ['a', 'b'])
  assert.equal(m.nameMatches('  Khoa CNTT ', 'Khoa CNTT'), true); assert.equal(m.nameMatches('khoa cntt', 'Khoa CNTT'), false); assert.equal(m.nameMatches('', ''), false)
})

test('invitation expiry text counts whole days and never goes negative', () => {
  const now = Date.parse('2026-10-08T00:00:00Z')
  assert.equal(m.expiresText('2026-10-15T00:00:00Z', now), 'còn 7 ngày'); assert.equal(m.expiresText('2026-10-08T05:00:00Z', now), 'hết hạn trong hôm nay')
  assert.equal(m.expiresText('2026-10-07T00:00:00Z', now), 'đã hết hạn'); assert.equal(m.expiresText(undefined, now), '')
})
