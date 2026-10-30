import { lazy, Suspense } from 'react'
import { RouterProvider, createBrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import LoginPage from './features/auth/LoginPage'
import RegisterPage from './features/auth/RegisterPage'
import { ForgotPasswordPage, ResetPasswordPage } from './features/auth/PasswordPages'
const ProfilePage = lazy(() => import('./features/account/ProfilePage'))
const PrintExamPage = lazy(() => import('./features/exams/PrintExamPage'))
const AdminUsersPage = lazy(() => import('./features/account/AdminUsersPage'))
import AppLayout from './components/layout/AppLayout'
import ProtectedRoute from './components/ProtectedRoute'
import { LoadingState } from './components/ui'
import DashboardPage from './features/dashboard/DashboardPage'
const CalendarPage = lazy(() => import('./features/calendar/CalendarPage'))
const NotificationsPage = lazy(() => import('./features/notifications/NotificationsPage'))
const PublicBankPage = lazy(() => import('./features/banks/PublicBankPage'))
const PersonalQuestionSetEditorPage = lazy(() => import('./features/banks/PersonalQuestionSets').then(m => ({ default: m.PersonalQuestionSetEditorPage })))
const QuestionListPage = lazy(() => import('./features/question-bank/QuestionListPage'))
import ExamListPage from './features/exams/ExamListPage'
const ExamEditorPage = lazy(() => import('./features/exams/ExamEditorPage'))
const ImportJobsPage = lazy(() => import('./features/question-import/ImportJobsPage'))
const ImportJobPage = lazy(() => import('./features/question-import/ImportJobPage'))
const ExamDetailPage = lazy(() => import('./features/exams/ExamDetailPage'))
const GradingPage = lazy(() => import('./features/grading/GradingPage'))
const OrganizationsPage = lazy(() => import('./features/organizations/OrganizationsPage'))
const OrganizationDetailPage = lazy(() => import('./features/organizations/OrganizationDetailPage'))
import MyExamsPage from './features/exams/MyExamsPage'
import ExamLobbyPage from './features/exams/ExamLobbyPage'
import StudentExamPage from './features/exams/StudentExamPage'
import ExamResultPage from './features/exams/ExamResultPage'
import { t, useT } from './lib/i18n'
import RouteError from './components/RouteError'

function AppRoutes() {
  useT()
  return (
    <>
      <Suspense fallback={<LoadingState rows={4} label={t('Đang tải trang…')} />}>
      <Routes>
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />

        {/* Exam taking: focus mode without navigation chrome */}
        <Route element={<ProtectedRoute persona="STUDENT" />}>
          <Route path="/exam/take/:id" element={<StudentExamPage />} />
        </Route>
        {/* Paper exam: full-page print layout without the app chrome. */}
        <Route element={<ProtectedRoute persona="TEACHER" />}>
          <Route path="/exams/:id/print" element={<PrintExamPage />} />
        </Route>

        <Route element={<ProtectedRoute><AppLayout /></ProtectedRoute>}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/calendar" element={<CalendarPage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/public-bank" element={<PublicBankPage />} />
          <Route path="/public-bank/:postId" element={<PublicBankPage />} />
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
          <Route path="/questions/imports" element={<ImportJobsPage />} />
          <Route path="/questions/imports/:id" element={<ImportJobPage />} />
          <Route path="/questions/sets/new" element={<PersonalQuestionSetEditorPage />} />
          <Route path="/questions/sets/:setId" element={<PersonalQuestionSetEditorPage />} />
          <Route path="/exams" element={<ExamListPage />} />
          <Route path="/exams/create" element={<ExamEditorPage />} />
          <Route path="/exams/:id" element={<ExamDetailPage />} />
          <Route path="/exams/:id/edit" element={<ExamEditorPage />} />
          <Route path="/grading" element={<GradingPage />} />
        </Route>

        <Route element={<ProtectedRoute adminOnly><AppLayout /></ProtectedRoute>}>
          <Route path="/admin/users" element={<AdminUsersPage />} />
        </Route>

        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
      </Suspense>
    </>
  )
}

// A data router (one splat route over the same <Routes> tree) so pages can block navigation with useBlocker while a draft is unsaved.
const router = createBrowserRouter([{ path: '*', element: <AppRoutes />, errorElement: <RouteError /> }])

export default function App() {
  return <RouterProvider router={router} />
}
