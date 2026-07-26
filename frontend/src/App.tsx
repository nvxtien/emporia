import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './auth/AuthContext'
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
          <Route path="/workspace" element={<TradingWorkspacePage />} />
          <Route path="*" element={<HomePage />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}

export default App
