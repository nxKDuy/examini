import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { examApi, errorText } from './examApi'
export default function ExamResultPage(){
  const {id}=useParams();const {data,isLoading,error}=useQuery({queryKey:['result',id],queryFn:()=>examApi.result(id!),refetchInterval:query=>query.state.data?.status==='GRADED'?false:3000})
  return <main className="clean-card" style={{maxWidth:720,margin:'40px auto',padding:32}}>
    <h1>{data?.title || 'Kết quả bài thi'}</h1>{error && <p role="alert">{errorText(error)}</p>}
    {isLoading?<p>Đang tải…</p>:<><p>Trạng thái: {data?.status}</p>{data?.status==='GRADED'?<p>Điểm: {data.score}/{data.totalScore}</p>:<p>Bài làm chưa có kết quả cuối cùng. Vui lòng chờ chấm / xét duyệt.</p>}<p>{data?.feedback}</p></>}
    <Link to="/exam/lobby/discover">Kỳ thi của tôi</Link>
  </main>
}
