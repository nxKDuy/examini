import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { examApi, errorText, type Session } from './examApi'
export default function StudentExamPage() {
  const {id}=useParams();const navigate=useNavigate();const [session,setSession]=useState<Session>()
  const [answers,setAnswers]=useState<Record<string,string>>({});const [error,setError]=useState('');const [saving,setSaving]=useState(0)
  const [confirming,setConfirming]=useState(false);const [remaining,setRemaining]=useState(0);const deadline=useRef(0);const [busy,setBusy]=useState(false)
  const pending=useRef(new Set<string>());const queues=useRef<Record<string,Promise<void>>>({})
  useEffect(()=>{let live=true;examApi.session(id!).then(s=>{if(!live)return;setSession(s);setAnswers(s.answers);setRemaining(Math.max(0,Math.ceil((Date.parse(s.expiresAt)-Date.parse(s.serverNow))/1000)));deadline.current=performance.now()+Math.max(0,Date.parse(s.expiresAt)-Date.parse(s.serverNow));if(s.status!=='IN_PROGRESS')navigate(`/exam/result/${s.sessionId}`)}).catch(e=>setError(errorText(e)));return()=>{live=false}},[id,navigate])
  useEffect(()=>{if(!session)return;const timer=setInterval(()=>{setRemaining(Math.max(0,Math.ceil((deadline.current-performance.now())/1000)));if(deadline.current<=performance.now()){void examApi.session(session.sessionId).then(s=>{if(s.status!=='IN_PROGRESS')navigate(`/exam/result/${s.sessionId}`)}).catch(e=>setError(errorText(e)))}},1000);return()=>clearInterval(timer)},[session,navigate])
  function save(questionId:string,content:string) {
    setAnswers(a=>({...a,[questionId]:content}));pending.current.add(questionId);setSaving(v=>v+1);setError('')
    const previous=queues.current[questionId] || Promise.resolve()
    const next=previous.catch(()=>{}).then(async()=>{await examApi.answer(id!,questionId,content)})
    queues.current[questionId]=next
    void next.then(()=>{if(queues.current[questionId]===next)pending.current.delete(questionId)}).catch(e=>setError(errorText(e))).finally(()=>setSaving(v=>v-1))
  }
  async function submit(){setBusy(true);try{await Promise.all(Object.values(queues.current));if(pending.current.size)throw new Error('Unsaved');await examApi.submit(id!);navigate(`/exam/result/${id}`)}catch(e){setError(errorText(e))}finally{setBusy(false)}}
  if(!session)return <main><p role="alert">{error || 'Đang tải bài thi…'}</p></main>
  return <main style={{maxWidth:900,margin:'24px auto',padding:24}}><h1>{session.title}</h1>
    <p role="timer">Thời gian còn lại: {Math.floor(remaining/60)}:{String(remaining%60).padStart(2,'0')}</p>
    <p role="status">{saving?'Đang lưu…':pending.current.size?'Có câu chưa lưu. Hãy thử lưu lại.':'Các thay đổi đã lưu'}</p>{error && <p role="alert">{error}</p>}
    {session.questions.map((q,i)=><section className="clean-card" key={q.questionId} style={{padding:24,marginBottom:16}}>
      <h2>{i+1}. {q.content} ({q.points} điểm)</h2>
      {['MULTIPLE_CHOICE','TRUE_FALSE'].includes(q.questionType) ? q.options.map(o=><label key={o.id} style={{display:'block'}}><input type="radio" name={q.questionId} value={o.id} checked={answers[q.questionId]===o.id} disabled={busy || remaining<=0} onChange={()=>save(q.questionId,o.id)} />{o.content}</label>) : <textarea aria-label={`Câu trả lời ${i+1}`} value={answers[q.questionId] || ''} disabled={busy || remaining<=0} onChange={e=>save(q.questionId,e.target.value)} />}
      {pending.current.has(q.questionId) && !saving && <button onClick={()=>save(q.questionId,answers[q.questionId] || '')}>Thử lưu lại</button>}
    </section>)}
    <button className="btn-primary" disabled={busy || saving>0 || pending.current.size>0} onClick={()=>setConfirming(true)}>Nộp bài</button>
    {confirming && <section role="dialog" aria-label="Xác nhận nộp bài"><p>Nộp bài và kết thúc lượt thi?</p><button disabled={busy || saving>0 || pending.current.size>0} onClick={()=>void submit()}>Xác nhận nộp bài</button><button disabled={busy} onClick={()=>setConfirming(false)}>Tiếp tục làm bài</button></section>}
  </main>
}
