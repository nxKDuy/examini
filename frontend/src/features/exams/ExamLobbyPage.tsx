import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import { examApi, errorText } from './examApi'
export default function ExamLobbyPage() {
  const {id} = useParams(); const navigate = useNavigate(); const [code,setCode] = useState('')
  const [error,setError]=useState(''); const [ready,setReady]=useState(false); const [starting,setStarting]=useState(false)
  const discover = id === 'discover'
  const my = useQuery({queryKey:['my-exams'],queryFn:examApi.my,enabled:discover})
  const publicExams = useQuery({queryKey:['public-exams'],queryFn:examApi.public,enabled:discover})
  const lobby = useQuery({queryKey:['lobby',id],queryFn:()=>examApi.lobby(id!),enabled:!discover && !!id})
  async function join(examId?: string) { try {const exam=examId ? await examApi.join(examId) : await examApi.joinCode(code);navigate(`/exam/lobby/${exam.id}`)}catch(e){setError(errorText(e))} }
  async function start() {setStarting(true);setError('');try {if(lobby.data?.status==='OPEN')await examApi.preflight(id!);const session=await examApi.start(id!);navigate(`/exam/take/${session.sessionId}`)}catch(e){setError(errorText(e))}finally{setStarting(false)} }
  return <main className="clean-card" style={{maxWidth:900,margin:'32px auto',padding:32}}>
    <h1>{discover?'Kỳ thi của tôi':lobby.data?.title || 'Sảnh thi'}</h1>
    {(error || lobby.error || my.error || publicExams.error) && <p role="alert">{error || errorText(lobby.error || my.error || publicExams.error)}</p>}
    {discover ? <>
      <form onSubmit={e=>{e.preventDefault();void join()}}><label>Mã tham gia <input required value={code} onChange={e=>setCode(e.target.value)} /></label><button className="btn-primary">Tham gia</button></form>
      <h2>Được phân công / Đã tham gia</h2>{my.data?.map(e=><p key={e.id}>{e.title} · {e.status} <button onClick={()=>navigate(`/exam/lobby/${e.id}`)}>Mở sảnh</button></p>)}
      <h2>Kỳ thi công khai</h2>{publicExams.data?.map(e=><p key={e.id}>{e.title} <button onClick={()=>void join(e.id)}>Tham gia</button></p>)}
    </> : lobby.data ? <>
      <p>{lobby.data.description}</p><p>Ban tổ chức: {lobby.data.ownerUserId}</p>
      <p>{lobby.data.durationMinutes} phút · {lobby.data.questionIds.length} câu · {lobby.data.totalScore} điểm</p>
      <p>Trạng thái: {lobby.data.status} · Tối đa {lobby.data.attemptLimit} lượt</p>
      <p>Lịch: {lobby.data.startTime || 'Không giới hạn bắt đầu'} — {lobby.data.endTime || 'Không giới hạn kết thúc'}</p>
      <p>Bài làm được lưu từng câu. Thời gian do máy chủ kiểm soát. Tải lại trang sẽ khôi phục lượt đang làm.</p>
      <label><input type="checkbox" checked={ready} onChange={e=>setReady(e.target.checked)} /> Tôi đã sẵn sàng</label>
      <button className="btn-primary" disabled={!ready || starting || !navigator.onLine} onClick={()=>void start()}>{starting?'Đang kiểm tra…':'Bắt đầu / Tiếp tục'}</button>
    </> : <p>Đang tải…</p>}
  </main>
}
