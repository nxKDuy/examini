import { useNavigate } from 'react-router-dom'
import CreateExamWizard from './CreateExamWizard'

export default function CreateExamPage() {
  const navigate = useNavigate()
  return <CreateExamWizard onClose={() => navigate('/exams')} />
}
