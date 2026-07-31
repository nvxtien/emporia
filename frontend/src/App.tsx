import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './auth/AuthContext'
import { AdminAuditPage } from './components/AdminAuditPage'
import { AdminPlaceholderRoute } from './components/AdminPlaceholderPage'
import { AdminExecutionPage } from './components/AdminExecutionPage'
import { AdminPortfolioPage } from './components/AdminPortfolioPage'
import { AdminShell } from './components/AdminShell'
import { AdminStaticDataPage } from './components/AdminStaticDataPage'
import { AdminUsersPage } from './components/AdminUsersPage'
import { AuthCallbackPage } from './components/AuthCallbackPage'
import { HomePage } from './components/HomePage'
import { LoginPage } from './components/LoginPage'
import { TradingWorkspacePage } from './components/TradingWorkspacePage'
import './App.css'

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/auth/callback" element={<AuthCallbackPage mode="signin" />} />
          <Route path="/auth/logout-callback" element={<AuthCallbackPage mode="signout" />} />
          <Route path="/sign-in" element={<LoginPage />} />
          <Route path="/admin" element={<AdminShell />}>
            <Route index element={<Navigate to="/admin/users" replace />} />
            <Route path="users" element={<AdminUsersPage />} />
            <Route path="permissions" element={<AdminPlaceholderRoute page="permissions" />} />
            <Route path="static-data" element={<AdminStaticDataPage />} />
            <Route path="execution" element={<AdminExecutionPage />} />
            <Route path="portfolio" element={<AdminPortfolioPage />} />
            <Route path="audit" element={<AdminAuditPage />} />
          </Route>
          <Route path="/workspace" element={<TradingWorkspacePage />} />
          <Route path="*" element={<HomePage />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}

export default App
