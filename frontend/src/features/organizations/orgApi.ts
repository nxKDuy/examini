import api from '../../lib/api'

/** Contract: organization-api.md (MAIN lock 4014c66). Every authority below comes from the caller's real membership, never from persona or role names. */
export type OrgRole = 'OWNER' | 'ADMIN' | 'MEMBER'
export type InviteRole = 'ADMIN' | 'MEMBER'
export type OrgStatus = 'ACTIVE' | 'ARCHIVED'
export type MemberStatus = 'ACTIVE' | 'REVOKED' | 'LEFT'
export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'REVOKED' | 'EXPIRED'

export interface Access { membershipId?: string; role: OrgRole; canReview: boolean; capabilities?: string[] }
export interface Organization {
  id: string; name: string; description?: string; status: OrgStatus; revision: number
  createdAt?: string; updatedAt?: string; archivedAt?: string; me?: Access; activeMemberCount?: number
}
export interface Member {
  membershipId: string; userId: string; displayName?: string; role: OrgRole; status: MemberStatus; canReview: boolean
  capabilities?: string[]; joinedAt?: string; endedAt?: string; revision: number
}
export interface Invitation {
  id: string; organizationId: string; organizationName?: string; recipientUserId: string; recipientDisplayName?: string
  role: InviteRole; status: InvitationStatus; invitedBy?: string; createdAt?: string; expiresAt?: string; respondedAt?: string; revision: number
}
export interface AuditEntry { id: string; actorUserId?: string; action: string; targetUserId?: string; membershipId?: string; invitationId?: string; detail?: string; occurredAt: string }
export interface Paged<T> { items: T[]; page: number; size: number; totalElements: number; totalPages: number }

export type RecipientInput = { userId: string } | { email: string }

export const orgApi = {
  list: (p: { status?: string; q?: string; page?: number; size?: number }) => api.get<Paged<Organization>>('/organizations', { params: { size: 12, ...p } }).then(r => r.data),
  get: (id: string) => api.get<Organization>(`/organizations/${id}`).then(r => r.data),
  create: (body: { name: string; description?: string }) => api.post<Organization>('/organizations', body).then(r => r.data),
  update: (id: string, body: { name?: string; description?: string; revision: number }) => api.patch<Organization>(`/organizations/${id}`, body).then(r => r.data),
  archive: (id: string, revision: number) => api.post<Organization>(`/organizations/${id}/archive`, { revision }).then(r => r.data),
  transfer: (id: string, targetMembershipId: string, revision: number) => api.post<Organization>(`/organizations/${id}/transfer-ownership`, { targetMembershipId, revision }).then(r => r.data),
  leave: (id: string) => api.post<void>(`/organizations/${id}/leave`).then(() => undefined),
  members: (id: string, p: { status?: string; role?: string; page?: number; size?: number }) => api.get<Paged<Member>>(`/organizations/${id}/members`, { params: { size: 20, ...p } }).then(r => r.data),
  updateMember: (id: string, membershipId: string, body: { role?: InviteRole; canReview?: boolean; revision: number }) =>
    api.patch<Member>(`/organizations/${id}/members/${membershipId}`, body).then(r => r.data),
  removeMember: (id: string, membershipId: string, revision: number) => api.delete<void>(`/organizations/${id}/members/${membershipId}`, { params: { revision } }).then(() => undefined),
  invite: (id: string, body: RecipientInput & { role: InviteRole }) => api.post<Invitation>(`/organizations/${id}/invitations`, body).then(r => ({ invitation: r.data, created: r.status === 201 })),
  invitations: (id: string, p: { status?: string; page?: number; size?: number }) => api.get<Paged<Invitation>>(`/organizations/${id}/invitations`, { params: { size: 20, ...p } }).then(r => r.data),
  revokeInvitation: (id: string, invitationId: string) => api.post<Invitation>(`/organizations/${id}/invitations/${invitationId}/revoke`).then(r => r.data),
  myInvitations: (status = 'PENDING') => api.get<Paged<Invitation>>('/organizations/invitations/mine', { params: { status, size: 50 } }).then(r => r.data),
  accept: (invitationId: string) => api.post<Member>(`/organizations/invitations/${invitationId}/accept`).then(r => r.data),
  decline: (invitationId: string) => api.post<Invitation>(`/organizations/invitations/${invitationId}/decline`).then(r => r.data),
  audit: (id: string, p: { page?: number; size?: number }) => api.get<Paged<AuditEntry>>(`/organizations/${id}/audit`, { params: { size: 20, ...p } }).then(r => r.data),
}
