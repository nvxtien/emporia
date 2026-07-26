import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

type CsrfResponse = {
  headerName: string
  parameterName: string
  token: string
}

function BrandIcon() {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true">
      <path d="M7 23V17M13 23V11M19 23V14M25 23V7" />
      <path d="m6 12 7-5 6 3 7-6" />
    </svg>
  )
}

function ArrowIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true">
      <path d="M4 10h11M11 5l5 5-5 5" />
    </svg>
  )
}

function ShieldIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 3 5 6v5c0 4.6 2.8 8 7 10 4.2-2 7-5.4 7-10V6l-7-3Z" />
      <path d="m9 12 2 2 4-5" />
    </svg>
  )
}

function EyeIcon({ hidden }: { hidden: boolean }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M2.5 12s3.5-6 9.5-6 9.5 6 9.5 6-3.5 6-9.5 6-9.5-6-9.5-6Z" />
      <circle cx="12" cy="12" r="2.5" />
      {hidden && <path d="m4 4 16 16" />}
    </svg>
  )
}

export function LoginPage() {
  const [searchParams] = useSearchParams()
  const [csrf, setCsrf] = useState<CsrfResponse | null>(null)
  const [csrfError, setCsrfError] = useState(false)
  const [showPassword, setShowPassword] = useState(false)
  const invalidCredentials = searchParams.has('error')

  useEffect(() => {
    const controller = new AbortController()

    void fetch('/auth/csrf', {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    })
      .then((response) => {
        if (!response.ok) throw new Error('Unable to prepare secure sign in')
        return response.json() as Promise<CsrfResponse>
      })
      .then(setCsrf)
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        setCsrfError(true)
      })

    return () => controller.abort()
  }, [])

  return (
    <main className="login-shell">
      <section className="login-form-panel">
        <div className="login-form-wrap">
          <Link className="brand login-brand" to="/" aria-label="Return to Emporia Trade">
            <span className="brand-mark"><BrandIcon /></span>
            <span>Emporia</span>
            <small>Trade</small>
          </Link>

          <div className="login-heading">
            <p className="eyebrow">Secure account access</p>
            <h1>Welcome back.</h1>
            <p>Sign in to continue to your portfolio and trading workspace.</p>
          </div>

          {(invalidCredentials || csrfError) && (
            <div className="login-alert" role="alert">
              <span>!</span>
              <div>
                <strong>{invalidCredentials ? 'We could not sign you in' : 'Secure sign-in is unavailable'}</strong>
                <p>
                  {invalidCredentials
                    ? 'Check your username and password, then try again.'
                    : 'The authorization service could not be reached. Please try again shortly.'}
                </p>
              </div>
            </div>
          )}

          <form className="login-form" method="post" action="/login">
            {csrf && <input type="hidden" name={csrf.parameterName} value={csrf.token} />}
            <label htmlFor="username">
              <span>Username</span>
              <input
                id="username"
                name="username"
                type="text"
                autoComplete="username"
                autoCapitalize="none"
                spellCheck="false"
                placeholder="Enter your username"
                required
                autoFocus
              />
            </label>
            <label htmlFor="password">
              <span>Password</span>
              <span className="password-field">
                <input
                  id="password"
                  name="password"
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="current-password"
                  placeholder="Enter your password"
                  required
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((visible) => !visible)}
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                >
                  <EyeIcon hidden={showPassword} />
                </button>
              </span>
            </label>
            <button className="button button--primary button--full login-submit" type="submit" disabled={!csrf || csrfError}>
              {csrf ? 'Continue securely' : 'Preparing secure sign in…'}
              {csrf && <ArrowIcon />}
            </button>
          </form>

          <div className="login-security-note">
            <ShieldIcon />
            <p><strong>Protected sign-in</strong>Your credentials are sent directly to Emporia’s authorization service over this secure session.</p>
          </div>

          <Link className="login-back-link" to="/">← Return to market overview</Link>
        </div>
      </section>

      <aside className="login-visual" aria-label="Emporia portfolio preview">
        <div className="login-visual__top">
          <span>Emporia private workspace</span>
          <span><i /> Protected</span>
        </div>
        <div className="login-visual__copy">
          <p className="eyebrow">Clarity before execution</p>
          <h2>One secure view of every market decision.</h2>
          <p>Research, portfolio context, and order review stay connected from sign-in to execution.</p>
        </div>
        <div className="login-portfolio-card">
          <div className="login-card-heading">
            <div><span>Portfolio preview</span><strong>$48,260.40</strong></div>
            <span className="gain">+1.35%</span>
          </div>
          <svg viewBox="0 0 500 128" preserveAspectRatio="none" aria-hidden="true">
            <defs>
              <linearGradient id="login-chart-fill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="#c6ef8b" stopOpacity="0.28" />
                <stop offset="100%" stopColor="#c6ef8b" stopOpacity="0" />
              </linearGradient>
            </defs>
            <path className="login-chart-fill" d="M0 112 C38 109 55 81 90 91 S146 112 184 69 S243 49 278 66 S341 82 372 45 S429 52 458 24 S488 19 500 8 L500 128 L0 128Z" />
            <path className="login-chart-line" d="M0 112 C38 109 55 81 90 91 S146 112 184 69 S243 49 278 66 S341 82 372 45 S429 52 458 24 S488 19 500 8" />
          </svg>
          <div className="login-card-metrics">
            <div><span>Buying power</span><strong>$12,420.00</strong></div>
            <div><span>Positions</span><strong>8</strong></div>
            <div><span>Today</span><strong className="gain">+$642.18</strong></div>
          </div>
        </div>
        <p className="login-disclaimer">Illustrative portfolio data · Investing involves risk</p>
      </aside>
    </main>
  )
}
