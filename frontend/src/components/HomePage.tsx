import type { User } from 'oidc-client-ts'
import { useAuth } from '../auth/useAuth'

const marketIndices = [
  { name: 'S&P 500', value: '6,086.37', change: '+0.62%', positive: true },
  { name: 'NASDAQ', value: '19,681.75', change: '+0.84%', positive: true },
  { name: 'DOW', value: '44,173.64', change: '-0.18%', positive: false },
  { name: 'VIX', value: '15.42', change: '-2.03%', positive: true },
]

const watchlist = [
  {
    symbol: 'NVDA',
    name: 'NVIDIA',
    price: '$141.97',
    change: '+2.31%',
    positive: true,
    path: 'M2 35 C18 33, 20 18, 35 23 S56 31, 68 18 S90 6, 104 12 S120 10, 138 3',
  },
  {
    symbol: 'AAPL',
    name: 'Apple',
    price: '$236.45',
    change: '+1.08%',
    positive: true,
    path: 'M2 32 C16 27, 28 34, 39 24 S59 10, 72 18 S91 28, 106 15 S125 12, 138 7',
  },
  {
    symbol: 'MSFT',
    name: 'Microsoft',
    price: '$418.79',
    change: '+0.44%',
    positive: true,
    path: 'M2 31 C17 35, 27 26, 39 28 S59 16, 72 20 S91 13, 105 18 S124 8, 138 10',
  },
  {
    symbol: 'TSLA',
    name: 'Tesla',
    price: '$328.11',
    change: '-1.27%',
    positive: false,
    path: 'M2 8 C16 13, 24 5, 38 16 S56 12, 68 22 S88 18, 102 27 S122 25, 138 35',
  },
]

const positions = [
  { symbol: 'AAPL', shares: '12 shares', value: '$2,837.40', change: '+1.08%' },
  { symbol: 'NVDA', shares: '18 shares', value: '$2,555.46', change: '+2.31%' },
  { symbol: 'MSFT', shares: '6 shares', value: '$2,512.74', change: '+0.44%' },
]

function claim(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

function claimList(value: unknown): string[] {
  if (Array.isArray(value)) return value.filter((entry): entry is string => typeof entry === 'string')
  if (typeof value === 'string') return value.split(/[,\s]+/).filter(Boolean)
  return []
}

function hasAuthority(user: User | null, authority: string): boolean {
  return user ? claimList(user.profile.authorities).includes(authority) : false
}

function displayName(user: User): string {
  return (
    claim(user.profile.name) ??
    claim(user.profile.preferred_username) ??
    claim(user.profile.email) ??
    user.profile.sub
  )
}

function initials(name: string): string {
  return name
    .split(/\s+/)
    .map((part) => part[0])
    .join('')
    .slice(0, 2)
    .toUpperCase()
}

function ArrowIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true">
      <path d="M4 10h11M11 5l5 5-5 5" />
    </svg>
  )
}

function BrandIcon() {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true">
      <path d="M7 23V17M13 23V11M19 23V14M25 23V7" />
      <path d="m6 12 7-5 6 3 7-6" />
    </svg>
  )
}

function CheckIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true">
      <path d="m4 10 4 4 8-9" />
    </svg>
  )
}

function MiniChart({ path, positive }: { path: string; positive: boolean }) {
  return (
    <svg
      className={positive ? 'mini-chart mini-chart--up' : 'mini-chart mini-chart--down'}
      viewBox="0 0 140 40"
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      <path d={path} />
    </svg>
  )
}

export function HomePage() {
  const { user, isAuthenticated, isLoading, error, login, logout, clearError } = useAuth()
  const name = user ? displayName(user) : ''
  const isAdmin = hasAuthority(user, 'ROLE_ADMIN')

  return (
    <div className="site-shell">
      <header className="site-header">
        <a className="brand" href="#top" aria-label="Emporia trading home">
          <span className="brand-mark"><BrandIcon /></span>
          <span>Emporia</span>
          <small>Trade</small>
        </a>
        <nav className="main-nav" aria-label="Main navigation">
          <a href="#markets">Markets</a>
          <a href="#portfolio">Portfolio</a>
          <a href="#trade">Trade</a>
          <a href="#account">Account</a>
          {isAdmin && <a href="/admin/users">Admin</a>}
        </nav>
        {isAuthenticated && user ? (
          <div className="header-account">
            <span className="avatar" aria-hidden="true">{initials(name)}</span>
            <div className="header-user">
              <span>{name}</span>
              <button type="button" onClick={() => void logout()}>Sign out</button>
            </div>
          </div>
        ) : (
          <button
            className="button button--small"
            type="button"
            disabled={isLoading}
            onClick={() => void login()}
          >
            Sign in
          </button>
        )}
      </header>

      <div className="market-strip" aria-label="Illustrative market index data">
        <span className="market-strip__label"><i /> Market preview</span>
        <div className="market-strip__items">
          {marketIndices.map((index) => (
            <span className="index-quote" key={index.name}>
              <strong>{index.name}</strong>
              <span>{index.value}</span>
              <em className={index.positive ? 'gain' : 'loss'}>{index.change}</em>
            </span>
          ))}
        </div>
        <span className="data-label">Sample data</span>
      </div>

      {error && (
        <div className="notice" role="alert">
          <span>{error}</span>
          <button type="button" onClick={clearError} aria-label="Dismiss message">×</button>
        </div>
      )}

      <main id="top">
        <section className="hero-section">
          <div className="hero-copy">
            <p className="eyebrow"><span /> A calmer way to navigate the market</p>
            <h1>Your strategy.<br /><em>In clear view.</em></h1>
            <p className="hero-intro">
              Research stocks, understand your exposure, and prepare every order from one focused
              trading workspace.
            </p>
            <div className="hero-actions">
              {isAuthenticated ? (
                <a className="button button--primary" href="/workspace">
                  Open trading desk <ArrowIcon />
                </a>
              ) : (
                <button
                  className="button button--primary"
                  type="button"
                  disabled={isLoading}
                  onClick={() => void login()}
                >
                  {isLoading ? 'Checking session…' : 'Start trading securely'} <ArrowIcon />
                </button>
              )}
              <a className="quiet-link" href="#markets">Explore the market</a>
            </div>
            <ul className="trust-list" aria-label="Platform benefits">
              <li><CheckIcon /> OAuth2-secured access</li>
              <li><CheckIcon /> Clear order review</li>
              <li><CheckIcon /> Portfolio-level context</li>
            </ul>
          </div>

          <div className="market-terminal" aria-label="Illustrative portfolio preview">
            <div className="terminal-topbar">
              <div>
                <span className="terminal-kicker">Portfolio preview</span>
                <strong>Core account</strong>
              </div>
              <span className="status-badge"><i /> Sample</span>
            </div>
            <div className="balance-row">
              <div>
                <span>Total value</span>
                <strong>$48,260.40</strong>
              </div>
              <div className="day-change">
                <span>Today</span>
                <strong>+$642.18</strong>
                <small>+1.35%</small>
              </div>
            </div>
            <div className="portfolio-chart">
              <div className="chart-guides" aria-hidden="true"><span /><span /><span /></div>
              <svg viewBox="0 0 560 180" preserveAspectRatio="none" role="img" aria-label="Portfolio value trending upward">
                <defs>
                  <linearGradient id="portfolio-fill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#73d9aa" stopOpacity="0.32" />
                    <stop offset="100%" stopColor="#73d9aa" stopOpacity="0" />
                  </linearGradient>
                </defs>
                <path className="chart-fill" d="M0 150 C45 143 52 115 96 123 S158 146 198 102 S260 72 300 91 S353 109 391 67 S455 74 491 40 S535 30 560 13 L560 180 L0 180 Z" />
                <path className="chart-line" d="M0 150 C45 143 52 115 96 123 S158 146 198 102 S260 72 300 91 S353 109 391 67 S455 74 491 40 S535 30 560 13" />
                <circle cx="560" cy="13" r="5" />
              </svg>
            </div>
            <div className="range-tabs" aria-label="Chart period">
              <span>1D</span><span>1W</span><span className="active">1M</span><span>3M</span><span>1Y</span>
            </div>
            <div className="terminal-metrics">
              <div><span>Buying power</span><strong>$12,420.00</strong></div>
              <div><span>Invested</span><strong>$35,840.40</strong></div>
              <div><span>Positions</span><strong>8</strong></div>
            </div>
          </div>
        </section>

        <section className="markets-section" id="markets">
          <div className="section-heading">
            <div>
              <p className="eyebrow">Your market at a glance</p>
              <h2>Move from signal to decision.</h2>
            </div>
            <p>Build a focused watchlist and keep the companies that matter close at hand.</p>
          </div>
          <div className="watchlist-table">
            <div className="watchlist-header">
              <span>Symbol</span><span>Trend</span><span>Price</span><span>Today</span><span />
            </div>
            {watchlist.map((stock) => (
              <article className="watchlist-row" key={stock.symbol}>
                <div className="stock-identity">
                  <span className="stock-logo">{stock.symbol.slice(0, 1)}</span>
                  <div><strong>{stock.symbol}</strong><span>{stock.name}</span></div>
                </div>
                <MiniChart path={stock.path} positive={stock.positive} />
                <strong className="stock-price">{stock.price}</strong>
                <span className={stock.positive ? 'change-pill gain' : 'change-pill loss'}>{stock.change}</span>
                <a href="#trade" aria-label={`Review ${stock.symbol}`}>Review <ArrowIcon /></a>
              </article>
            ))}
          </div>
          <p className="sample-note">Prices and performance shown on this page are illustrative and are not live market data.</p>
        </section>

        <section className="portfolio-section" id="portfolio">
          <div className="portfolio-copy">
            <p className="eyebrow">Portfolio intelligence</p>
            <h2>Know what you own—and why you own it.</h2>
            <p>
              See allocation, concentration, and daily movement together. Emporia keeps portfolio
              context beside every market decision, so an idea never lives in isolation.
            </p>
            <div className="feature-list">
              <div><span>01</span><p><strong>Exposure made visible</strong>Understand sector and position weight before adding risk.</p></div>
              <div><span>02</span><p><strong>Performance in context</strong>Separate a market move from a change in your original thesis.</p></div>
              <div><span>03</span><p><strong>A complete decision trail</strong>Keep research, orders, and portfolio impact connected.</p></div>
            </div>
          </div>
          <div className="holdings-card">
            <div className="card-heading">
              <div><span>Model portfolio</span><strong>Top positions</strong></div>
              <span className="allocation-total">73% allocated</span>
            </div>
            <div className="allocation-bar" aria-label="Illustrative portfolio allocation">
              <span className="allocation-one" /><span className="allocation-two" /><span className="allocation-three" /><span className="allocation-cash" />
            </div>
            <div className="holdings-list">
              {positions.map((position, index) => (
                <div className="holding-row" key={position.symbol}>
                  <span className={`holding-dot holding-dot--${index + 1}`} />
                  <div><strong>{position.symbol}</strong><span>{position.shares}</span></div>
                  <strong>{position.value}</strong>
                  <span className="gain">{position.change}</span>
                </div>
              ))}
              <div className="holding-row holding-row--cash">
                <span className="holding-dot holding-dot--cash" />
                <div><strong>Cash</strong><span>Available</span></div>
                <strong>$12,420.00</strong>
                <span>—</span>
              </div>
            </div>
          </div>
        </section>

        <section className="trade-section" id="trade">
          <div className="order-card">
            <div className="order-card__header">
              <div><span>Order preview</span><strong>AAPL · Apple Inc.</strong></div>
              <span className="quote-price">$236.45 <em className="gain">+1.08%</em></span>
            </div>
            <div className="order-tabs"><span className="active">Buy</span><span>Sell</span></div>
            <div className="order-fields">
              <div><span>Order type</span><strong>Limit order</strong></div>
              <div><span>Shares</span><strong>10</strong></div>
              <div><span>Limit price</span><strong>$235.00</strong></div>
            </div>
            <div className="order-estimate"><span>Estimated total</span><strong>$2,350.00</strong></div>
            <div className="order-review"><CheckIcon /><span><strong>Review before sending</strong>See estimated value and portfolio impact before confirming an order.</span></div>
          </div>
          <div className="trade-copy">
            <p className="eyebrow">Deliberate execution</p>
            <h2>Every order deserves a final look.</h2>
            <p>
              A structured ticket makes quantity, order type, estimated value, and buying-power
              impact clear before anything reaches the market.
            </p>
            <ul>
              <li><CheckIcon /> Market and limit order support</li>
              <li><CheckIcon /> Estimated cost before confirmation</li>
              <li><CheckIcon /> Position impact in the review step</li>
            </ul>
          </div>
        </section>

        <section className="account-section" id="account">
          <div className="account-copy">
            <p className="eyebrow">Protected account access</p>
            {isAuthenticated && user ? (
              <>
                <h2>Welcome back, {name}.</h2>
                <p>Your identity is verified. Your trading workspace is ready on this device.</p>
              </>
            ) : (
              <>
                <h2>Your portfolio starts with secure access.</h2>
                <p>
                  Emporia uses OAuth2 and OpenID Connect with PKCE. Your password is handled by the
                  authorization service, never stored inside the React application.
                </p>
              </>
            )}
          </div>
          <div className="account-card">
            {isAuthenticated && user ? (
              <>
                <div className="profile-line">
                  <span className="avatar avatar--large" aria-hidden="true">{initials(name)}</span>
                  <div><strong>{name}</strong><span>Verified trading account</span></div>
                  <span className="verified-badge"><CheckIcon /> Verified</span>
                </div>
                <dl className="session-details">
                  <div><dt>Identity provider</dt><dd>Emporia OpenID</dd></div>
                  <div><dt>Session</dt><dd><span className="status-dot" /> Active</dd></div>
                  <div><dt>Account access</dt><dd>Browser session</dd></div>
                </dl>
                <button className="button button--outline button--full" type="button" onClick={() => void logout()}>
                  Sign out securely
                </button>
                <a className="button button--light button--full" href="/workspace">
                  Open trading desk <ArrowIcon />
                </a>
              </>
            ) : (
              <>
                <span className="account-card__label">Emporia access</span>
                <h3>One secure sign-in for your complete trading workspace.</h3>
                <ul>
                  <li>Protected portfolio access</li>
                  <li>Short-lived access tokens</li>
                  <li>No password stored in the browser app</li>
                </ul>
                <button
                  className="button button--light button--full"
                  type="button"
                  disabled={isLoading}
                  onClick={() => void login()}
                >
                  {isLoading ? 'Checking session…' : 'Sign in to Emporia'} <ArrowIcon />
                </button>
              </>
            )}
          </div>
        </section>
      </main>

      <footer className="site-footer">
        <div className="brand brand--footer"><span className="brand-mark"><BrandIcon /></span><span>Emporia</span><small>Trade</small></div>
        <p>Research clearly. Size deliberately. Trade with context.</p>
        <span>© {new Date().getFullYear()} Emporia</span>
        <small className="risk-note">Illustrative market data only. Investing involves risk, including possible loss of principal.</small>
      </footer>
    </div>
  )
}
