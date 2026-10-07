import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import LoginPage from './features/auth/LoginPage'
import RegisterPage from './features/auth/RegisterPage'
import AppLayout from './components/layout/AppLayout'
import ProtectedRoute from './components/ProtectedRoute'
import DashboardPage from './features/dashboard/DashboardPage'
import CalendarPage from './features/calendar/CalendarPage'
import NotificationsPage from './features/notifications/NotificationsPage'
import QuestionListPage from './features/question-bank/QuestionListPage'
import ExamListPage from './features/exams/ExamListPage'
import ExamEditorPage from './features/exams/ExamEditorPage'
import ExamDetailPage from './features/exams/ExamDetailPage'
import GradingPage from './features/grading/GradingPage'
import MyExamsPage from './features/exams/MyExamsPage'
import ExamLobbyPage from './features/exams/ExamLobbyPage'
import StudentExamPage from './features/exams/StudentExamPage'
import ExamResultPage from './features/exams/ExamResultPage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />

        {/* Exam taking: focus mode without navigation chrome */}
        <Route element={<ProtectedRoute persona="STUDENT" />}>
          <Route path="/exam/take/:id" element={<StudentExamPage />} />
        </Route>

        <Route element={<ProtectedRoute><AppLayout /></ProtectedRoute>}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/calendar" element={<CalendarPage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
        </Route>

        <Route element={<ProtectedRoute persona="STUDENT"><AppLayout /></ProtectedRoute>}>
          <Route path="/my-exams" element={<MyExamsPage />} />
          <Route path="/exam/lobby/discover" element={<Navigate to="/my-exams" replace />} />
          <Route path="/exam/lobby/:id" element={<ExamLobbyPage />} />
          <Route path="/exam/result/:id" element={<ExamResultPage />} />
        </Route>

        {/* Teacher areas; system Admin may also enter (question review, exam management). */}
        <Route element={<ProtectedRoute persona="TEACHER"><AppLayout /></ProtectedRoute>}>
          <Route path="/questions" element={<QuestionListPage />} />
          <Route path="/exams" element={<ExamListPage />} />
          <Route path="/exams/create" element={<ExamEditorPage />} />
          <Route path="/exams/:id" element={<ExamDetailPage />} />
          <Route path="/exams/:id/edit" element={<ExamEditorPage />} />
          <Route path="/grading" element={<GradingPage />} />
        </Route>

        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
