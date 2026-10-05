import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import LoginPage from './features/auth/LoginPage'
import RegisterPage from './features/auth/RegisterPage'
import AppLayout from './components/layout/AppLayout'
import ProtectedRoute from './components/ProtectedRoute'
import DashboardPage from './features/dashboard/DashboardPage'
import QuestionListPage from './features/question-bank/QuestionListPage'
import ExamListPage from './features/exams/ExamListPage'
import CreateExamPage from './features/exams/CreateExamPage'
import AnalyticsPage from './features/analytics/AnalyticsPage'
import GradingPage from './features/grading/GradingPage'
import StudentExamPage from './features/exams/StudentExamPage'
import ExamLobbyPage from './features/exams/ExamLobbyPage'
import ExamResultPage from './features/exams/ExamResultPage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />

        {/* Protected Routes Without Layout (Focus Mode) */}
        <Route element={<ProtectedRoute persona="STUDENT" />}>
          <Route path="/exam/lobby/:id" element={<ExamLobbyPage />} />
          <Route path="/exam/take/:id" element={<StudentExamPage />} />
          <Route path="/exam/result/:id" element={<ExamResultPage />} />
        </Route>

        {/* Teacher Focus Mode — No Sidebar/Navbar */}
        <Route element={<ProtectedRoute persona="TEACHER" />}>
          <Route path="/exams/create" element={<CreateExamPage />} />
        </Route>

        {/* Protected Routes With Layout */}
        <Route element={<ProtectedRoute><AppLayout /></ProtectedRoute>}>
          <Route path="/dashboard" element={<DashboardPage />} />
        </Route>

        <Route element={<ProtectedRoute persona="TEACHER"><AppLayout /></ProtectedRoute>}>
          <Route path="/questions" element={<QuestionListPage />} />
          <Route path="/exams" element={<ExamListPage />} />
          <Route path="/grading" element={<GradingPage />} />
          <Route path="/analytics" element={<AnalyticsPage />} />
        </Route>

        <Route element={<ProtectedRoute adminOnly><AppLayout /></ProtectedRoute>}>
          <Route path="/users" element={<div className="page-enter p-8 text-surface-200">Quản lý người dùng (Coming soon)</div>} />
        </Route>

        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
