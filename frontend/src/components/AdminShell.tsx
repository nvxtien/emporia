import type { User } from 'oidc-client-ts'
import { Link, NavLink, Outlet } from 'react-router-dom'
import { adminNavItems } from '../admin/navigation'
import { useAuth } from '../auth/useAuth'
import '../admin/users.css'

function BrandMark() {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true">
      <path d="M7 23V17M13 23V11M19 23V14M25 23V7" />
      <path d="m6 12 7-5 6 3 7-6" />
    </svg>
  )
}

function claim(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

function claimList(value: unknown): string[] {
  if (Array.isArray(value)) return value.filter((entry): entry is string => typeof entry === 'string')
  if (typeof value === 'string') return value.split(/[,\s]+/).filter(Boolean)
  return []
}

function displayName(user: User): string {
  return claim(user.profile.name) ?? claim(user.profile.preferred_username) ?? user.profile.sub
}

function initials(name: string): string {
  return name.split(/\s+/).map((part) => part[0]).join('').slice(0, 2).toUpperCase()
}

function hasAuthority(user: User | null, authority: string): boolean {
  return user ? claimList(user.profile.authorities).includes(authority) : false
}

function AdminAccessState({
  title,
  actionLabel,
  onAction,
}: {
  title: string
  actionLabel?: string
  onAction?: () => void
}) {
  return (
    <main className="admin-access">
      <section className="admin-access__panel">
        <span className="admin-brand-mark"><BrandMark /></span>
        <p className="admin-eyebrow">Emporia Admin</p>
        <h1>{title}</h1>
        {actionLabel && onAction && <button type="button" onClick={onAction}>{actionLabel}</button>}
        <Link to="/">Return home</Link>
      </section>
    </main>
  )
}

export function AdminShell() {
  const { user, isAuthenticated, isLoading, login, logout } = useAuth()
  const isAdmin = hasAuthority(user, 'ROLE_ADMIN')
  const name = user ? displayName(user) : ''

  if (isLoading) return <AdminAccessState title="Preparing admin session" />
  if (!isAuthenticated || !user) {
    return <AdminAccessState title="Sign in to access admin tools" actionLabel="Sign in" onAction={() => void login()} />
  }
  if (!isAdmin) return <AdminAccessState title="Administrator access required" />

  return (
    <div className="admin-users-shell">
      <header className="admin-users-header">
        <Link className="admin-brand" to="/">
          <span className="admin-brand-mark"><BrandMark /></span>
          <strong>Emporia</strong><small>Admin</small>
        </Link>
        <nav className="admin-nav" aria-label="Admin workspace navigation">
          <span className="active">Admin</span>
          <Link to="/workspace">Trading desk</Link>
        </nav>
        <div className="admin-account">
          <span className="admin-avatar">{initials(name)}</span>
          <div><strong>{name}</strong><button type="button" onClick={() => void logout()}>Sign out</button></div>
        </div>
      </header>

      <div className="admin-shell-body">
        <nav className="admin-side-nav" aria-label="Admin sections">
          <div className="admin-side-nav__title">Administration</div>
          <div className="admin-side-nav__links">
            {adminNavItems.map((item) => (
              <NavLink
                key={item.path}
                to={item.path}
                className={({ isActive }) => isActive ? 'active' : undefined}
              >
                <span>{item.label}</span>
                <small>{item.description}</small>
              </NavLink>
            ))}
          </div>
        </nav>
        <div className="admin-shell-content">
          <Outlet />
        </div>
      </div>
    </div>
  )
}
