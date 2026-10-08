import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Dialog } from '../../components/Dialog'
import { ErrorState, Field, LoadingState, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { orgApi } from '../organizations/orgApi'
import { bankApi } from './bankApi'
import { isAuthorLookupFailure, submitTargets } from './bankModel'

interface Shared { questionId: string; version: number; onClose: () => void; onDone: (message: string) => void }

/** Send one version of an ACTIVE personal question to an organization's review queue. The original stays usable whatever the verdict. */
export function SubmitToOrgDialog({ questionId, version, onClose, onDone }: Shared) {
  const orgs = useQuery({ queryKey: ['organizations', 'list', 'submit-targets'], queryFn: async () => {
    const items = []
    for (let page = 0; ; page++) {
      const data = await orgApi.list({ status: 'ACTIVE', size: 50, page })
      items.push(...data.items)
      if (page + 1 >= data.totalPages) return items
    }
  } })
  const targets = submitTargets(orgs.data ?? [])
  const [orgId, setOrgId] = useState('')
  const [submissionNote, setSubmissionNote] = useState('')
  const send = useMutation({
    mutationFn: async () => {
      const banks = await bankApi.banks({ type: 'ORGANIZATION', organizationId: orgId })
      const bank = banks.content.find(b => b.organizationId === orgId)
      if (!bank) throw new Error('NO_BANK')
      return bankApi.submit(questionId, bank.id, version, submissionNote)
    },
    onSuccess: () => onDone('Đã gửi đóng góp vào tổ chức. Trạng thái: Chờ duyệt. Câu hỏi gốc của bạn vẫn dùng bình thường.'),
  })
  const noBank = send.error instanceof Error && send.error.message === 'NO_BANK'
  return (
    <Dialog title="Gửi vào tổ chức" onClose={onClose} busy={send.isPending}
      description="Gửi một bản chụp của câu hỏi này cho người duyệt của tổ chức. Câu gốc không đổi và vẫn dùng được dù bị từ chối."
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={send.isPending}>Hủy</button>
        <button type="button" className="btn btn-primary" disabled={!orgId || send.isPending} onClick={() => send.mutate()}>{send.isPending ? 'Đang gửi…' : 'Gửi đóng góp'}</button>
      </>}>
      <div className="stack">
        {send.isError && <Notice tone="danger" live>{noBank ? 'Chưa tìm thấy ngân hàng của tổ chức này. Thử lại sau.' : errorText(send.error)}</Notice>}
        <Field label="Ghi chú đóng góp (không bắt buộc)" htmlFor="submission-note" hint="Người duyệt thấy ghi chú này; tối đa 5000 ký tự."><textarea id="submission-note" className="input" rows={3} maxLength={5000} value={submissionNote} onChange={e => setSubmissionNote(e.target.value)} /></Field>
        <Notice tone="info">Phiên bản sẽ gửi: <strong>{version}</strong>. Sửa câu hỏi sau khi gửi không đổi bản đã gửi.</Notice>
        {orgs.isLoading ? <LoadingState rows={1} /> : orgs.isError ? <ErrorState error={orgs.error} onRetry={() => void orgs.refetch()} />
          : targets.length === 0 ? <p className="small muted">Bạn chưa ở tổ chức nào đang hoạt động mà bạn được gửi câu hỏi vào.</p>
          : <Field label="Tổ chức" htmlFor="submit-org" required><select id="submit-org" className="input" value={orgId} onChange={e => setOrgId(e.target.value)} data-autofocus>
              <option value="">Chọn tổ chức</option>{targets.map(o => <option key={o.id} value={o.id}>{o.name}</option>)}</select></Field>}
      </div>
    </Dialog>
  )
}

/** Publish ONE version publicly, directly (no review). The confirmation spells out what that means. */
export function PublishPublicDialog({ questionId, version, onClose, onDone }: Shared) {
  const publish = useMutation({
    mutationFn: () => bankApi.publish(questionId, version),
    onSuccess: () => onDone('Đã xuất bản công khai phiên bản này. Tìm thấy ở mục Ngân hàng công khai.'),
  })
  return (
    <Dialog title="Xuất bản công khai?" onClose={onClose} busy={publish.isPending}
      description={`Xuất bản đúng phiên bản ${version} của câu hỏi này.`}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={publish.isPending}>Hủy</button>
        <button type="button" className="btn btn-primary" disabled={publish.isPending} onClick={() => publish.mutate()}>{publish.isPending ? 'Đang xuất bản…' : 'Xuất bản công khai'}</button>
      </>}>
      <div className="stack">
        {publish.isError && <Notice tone="danger" live>{isAuthorLookupFailure(publish.error) ? 'Chưa xác thực được tên tác giả, nên chưa xuất bản. Hãy thử lại sau ít phút.' : errorText(publish.error)}</Notice>}
        <ul className="small stack-sm" style={{ margin: 0, paddingLeft: 18 }}>
          <li>Mọi giáo viên và học sinh đã đăng nhập đều xem được; không cần duyệt.</li>
          <li>Tên hiển thị của bạn được ghi là tác giả. Email và đáp án không bao giờ hiện công khai.</li>
          <li>Đây là bản chụp của phiên bản {version}: sửa câu hỏi sau này không đổi bản đã xuất bản. Muốn đăng bản mới, xuất bản lại.</li>
          <li>Bạn có thể gỡ công khai bất cứ lúc nào ở mục Ngân hàng công khai.</li>
        </ul>
      </div>
    </Dialog>
  )
}
