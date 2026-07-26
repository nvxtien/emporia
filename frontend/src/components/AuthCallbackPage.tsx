import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'

export function AuthCallbackPage({ mode }: { mode: 'signin' | 'signout' }) {
  const navigate = useNavigate()
  const { completeSignIn, completeSignOut } = useAuth()
  const started = useRef(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (started.current) return
    started.current = true

    const complete = mode === 'signin' ? completeSignIn : completeSignOut
    void complete()
      .then(() => navigate(mode === 'signin' ? '/workspace' : '/', { replace: true }))
      .catch(() => setFailed(true))
  }, [completeSignIn, completeSignOut, mode, navigate])

  return (
    <main className="callback-page">
      <div className="callback-card" role="status" aria-live="polite">
        <div className={failed ? 'callback-mark callback-mark--error' : 'callback-mark'}>
          {failed ? '!' : <span className="spinner" aria-hidden="true" />}
        </div>
        <p className="eyebrow">Secure trading access</p>
        <h1>{failed ? 'We could not complete that request' : 'Opening your workspace'}</h1>
        <p>
          {failed
            ? 'Return to Emporia and try signing in again.'
            : 'Your identity is being verified before your portfolio is displayed.'}
        </p>
        {failed && (
          <button className="button button--primary" type="button" onClick={() => navigate('/')}>
            Return home
          </button>
        )}
      </div>
    </main>
  )
}
