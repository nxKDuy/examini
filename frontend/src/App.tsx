import { lazy, Suspense } from 'react'
import { RouterProvider, createBrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import LoginPage from './features/auth/LoginPage'
import RegisterPage from './features/auth/RegisterPage'
import AppLayout from './components/layout/AppLayout'
import ProtectedRoute from './components/ProtectedRoute'
import { LoadingState } from './components/ui'
import DashboardPage from './features/dashboard/DashboardPage'
const CalendarPage = lazy(() => import('./features/calendar/CalendarPage'))
const NotificationsPage = lazy(() => import('./features/notifications/NotificationsPage'))
const PublicBankPage = lazy(() => import('./features/banks/PublicBankPage'))
const QuestionListPage = lazy(() => import('./features/question-bank/QuestionListPage'))
import ExamListPage from './features/exams/ExamListPage'
const ExamEditorPage = lazy(() => import('./features/exams/ExamEditorPage'))
const ExamDetailPage = lazy(() => import('./features/exams/ExamDetailPage'))
const GradingPage = lazy(() => import('./features/grading/GradingPage'))
const OrganizationsPage = lazy(() => import('./features/organizations/OrganizationsPage'))
const OrganizationDetailPage = lazy(() => import('./features/organizations/OrganizationDetailPage'))
import MyExamsPage from './features/exams/MyExamsPage'
import ExamLobbyPage from './features/exams/ExamLobbyPage'
import StudentExamPage from './features/exams/StudentExamPage'
import ExamResultPage from './features/exams/ExamResultPage'

function AppRoutes() {
  return (
    <>
      <Suspense fallback={<LoadingState rows={4} label="Đang tải trang…" />}>
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
          <Route path="/public-bank" element={<PublicBankPage />} />
          <Route path="/organizations" element={<OrganizationsPage />} />
          <Route path="/organizations/invitations" element={<Navigate to="/organizations?tab=invitations" replace />} />
          <Route path="/organizations/:id" element={<OrganizationDetailPage />} />
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
      </Suspense>
    </>
  )
}

// A data router (one splat route over the same <Routes> tree) so pages can block navigation with useBlocker while a draft is unsaved.
const router = createBrowserRouter([{ path: '*', element: <AppRoutes /> }])

export default function App() {
  return <RouterProvider router={router} />
}
