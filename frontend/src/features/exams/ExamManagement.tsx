import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuthStore } from '../../store/authStore'
import { examApi, errorText, type Exam, type CreateExamPayload } from './examApi'
export default function ExamManagement({exam,onClose}:{exam:Exam;onClose:()=>void}){
  const [editing,setEditing]=useState(false)
  const [draft,setDraft]=useState<CreateExamPayload>({title:exam.title,description:exam.description,type:exam.type,durationMinutes:exam.durationMinutes,passingScore:exam.passingScore || 0,startTime:exam.startTime || undefined,endTime:exam.endTime || undefined,shuffleQuestions:exam.shuffleQuestions,shuffleOptions:exam.shuffleOptions,requireCam:false,requireMic:false,blockTabSwitch:false,requireFullscreen:false,questions:exam.questions})
  const qc=useQueryClient();const user=useAuthStore(s=>s.user);const [error,setError]=useState('');const [userId,setUserId]=useState('');const [role,setRole]=useState('MANAGER');const [policy,setPolicy]=useState({accessMode:exam.accessMode,maxParticipants:exam.maxParticipants,maxConcurrentSessions:exam.maxConcurrentSessions,attemptLimit:exam.attemptLimit,joinCode:exam.code})
  const members=useQuery({queryKey:['members',exam.id],queryFn:()=>examApi.members(exam.id)})
  const participants=useQuery({queryKey:['participants',exam.id],queryFn:()=>examApi.participants(exam.id)})
  const monitor=useQuery({queryKey:['monitor',exam.id],queryFn:()=>examApi.monitor(exam.id),refetchInterval:5000})
  const owner=user?.id===exam.ownerUserId;const manage=owner || members.data?.[user?.id || '']==='MANAGER'
  async function action(fn:()=>Promise<unknown>){setError('');try{await fn();await qc.invalidateQueries();onClose()}catch(e){setError(errorText(e))}}
  return <section className="clean-card" style={{padding:24,marginBottom:24}}><h2>{exam.title} · {exam.status}</h2><button onClick={onClose}>Đóng bảng</button>
    {(error || members.error || participants.error || monitor.error) && <p role="alert">{error || errorText(members.error || participants.error || monitor.error)}</p>}
    <p>Người tham gia: {Object.keys(participants.data || {}).length}/{exam.maxParticipants} · Đang làm: {monitor.data?.filter(s=>s.status==='IN_PROGRESS').length ?? '…'}/{exam.maxConcurrentSessions}</p>
    {manage && <>{exam.status==='DRAFT' && <button onClick={()=>void action(()=>examApi.publishExam(exam.id))}>Xuất bản</button>}{exam.status==='PUBLISHED' && <button onClick={()=>void action(()=>examApi.open(exam.id))}>Mở kỳ thi</button>}{exam.status==='OPEN' && <button onClick={()=>void action(()=>examApi.close(exam.id))}>Đóng kỳ thi</button>}</>}
    {manage && exam.status==='DRAFT' && <>
      <button onClick={()=>setEditing(!editing)}>Chỉnh sửa bản nháp</button>
      {editing && <form onSubmit={e=>{e.preventDefault();void action(()=>examApi.updateExam(exam.id,draft))}}>
        <label>Tên đề <input required value={draft.title} onChange={e=>setDraft(p=>({...p,title:e.target.value}))}/></label>
        <label>Hướng dẫn <textarea value={draft.description || ''} onChange={e=>setDraft(p=>({...p,description:e.target.value}))}/></label>
        <label>Thời lượng phút <input type="number" min={1} required value={draft.durationMinutes} onChange={e=>setDraft(p=>({...p,durationMinutes:Number(e.target.value)}))}/></label>
        <label>Điểm đạt <input type="number" min={0} step="0.1" value={draft.passingScore} onChange={e=>setDraft(p=>({...p,passingScore:Number(e.target.value)}))}/></label>
        <label>Bắt đầu (UTC) <input type="datetime-local" value={draft.startTime || ''} onChange={e=>setDraft(p=>({...p,startTime:e.target.value || undefined}))}/></label>
        <label>Kết thúc (UTC) <input type="datetime-local" value={draft.endTime || ''} onChange={e=>setDraft(p=>({...p,endTime:e.target.value || undefined}))}/></label>
        <label>Câu hỏi UUID và điểm (mỗi dòng UUID,điểm)<textarea defaultValue={draft.questions.map(q=>`${q.questionId},${q.weight}`).join('\n')} onChange={e=>setDraft(p=>({...p,questions:e.target.value.split('\n').filter(Boolean).map(line=>{const [questionId,weight]=line.split(',');return {questionId:questionId.trim(),weight:Number(weight)}})}))}/></label>
        <button>Lưu bản nháp</button>
      </form>}
    </>}
    {manage && exam.status==='DRAFT' && <form onSubmit={e=>{e.preventDefault();void action(()=>examApi.policy(exam.id,policy))}}>
      <label>Cách tham gia <select value={policy.accessMode} onChange={e=>setPolicy(p=>({...p,accessMode:e.target.value as Exam['accessMode']}))}>{['ASSIGNED','JOIN_CODE','PUBLIC'].map(v=><option key={v}>{v}</option>)}</select></label>
      {policy.accessMode==='JOIN_CODE' && <label>Mã tham gia <input value={policy.joinCode} onChange={e=>setPolicy(p=>({...p,joinCode:e.target.value}))}/></label>}
      {(['maxParticipants','maxConcurrentSessions','attemptLimit'] as const).map(k=><label key={k}>{k}<input type="number" min={1} value={policy[k]} onChange={e=>setPolicy(p=>({...p,[k]:Number(e.target.value)}))}/></label>)}<button>Lưu chính sách</button>
    </form>}
    <label>UUID người dùng <input value={userId} onChange={e=>setUserId(e.target.value)} /></label>
    {owner && <><select value={role} onChange={e=>setRole(e.target.value)}><option>MANAGER</option><option>PROCTOR</option></select><button onClick={()=>void action(()=>examApi.invite(exam.id,userId,role))}>Gửi lời mời cộng tác</button></>}
    {manage && exam.status==='DRAFT' && <button onClick={()=>void action(()=>examApi.assign(exam.id,userId))}>Phân công sinh viên</button>}
    <h3>Cộng tác viên</h3>{Object.entries(members.data || {}).map(([id,r])=><p key={id}>{id} · {r} {owner && <button onClick={()=>void action(()=>examApi.removeMember(exam.id,id))}>Xóa</button>}</p>)}
    <h3>Người tham gia</h3>{Object.entries(participants.data || {}).map(([id,source])=><p key={id}>{id} · {source} {manage && exam.status==='DRAFT' && <button onClick={()=>void action(()=>examApi.unassign(exam.id,id))}>Bỏ phân công</button>}</p>)}
    <h3>Lượt thi</h3>{monitor.data?.map(s=><p key={s.sessionId}>{s.userId} · Lượt {s.attemptNumber} · {s.status} · Hết hạn {new Date(s.expiresAt).toLocaleString('vi-VN')}</p>)}
  </section>
}
