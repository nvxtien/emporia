import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { useAuth } from '../auth/useAuth'
import {
  adminUsersApi,
  type AdminAuthority,
  type AdminUser,
} from '../admin/users-api'
import '../admin/users.css'

interface UserDraft {
  id: string | null
  username: string
  email: string
  desk: string
  password: string
  enabled: boolean
  canTrade: boolean
  isAdmin: boolean
}

function emptyDraft(): UserDraft {
  return {
    id: null,
    username: '',
    email: '',
    desk: 'default',
    password: '',
    enabled: true,
    canTrade: false,
    isAdmin: false,
  }
}

function draftFromUser(user: AdminUser): UserDraft {
  return {
    id: user.id,
    username: user.username,
    email: user.email,
    desk: user.desk,
    password: '',
    enabled: user.enabled,
    canTrade: user.canTrade,
    isAdmin: user.authorities.includes('ROLE_ADMIN'),
  }
}

function authorities(draft: UserDraft): AdminAuthority[] {
  return draft.isAdmin ? ['ROLE_USER', 'ROLE_ADMIN'] : ['ROLE_USER']
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function upsertUser(users: AdminUser[], user: AdminUser): AdminUser[] {
  const next = users.some((candidate) => candidate.id === user.id)
    ? users.map((candidate) => candidate.id === user.id ? user : candidate)
    : [...users, user]
  return next.toSorted((left, right) => left.username.localeCompare(right.username))
}

export function AdminUsersPage() {
  const { user } = useAuth()
  const token = user?.access_token ?? ''
  const [users, setUsers] = useState<AdminUser[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [editingNew, setEditingNew] = useState(false)
  const [draft, setDraft] = useState<UserDraft>(() => emptyDraft())
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const selectedUser = useMemo(
    () => users.find((candidate) => candidate.id === selectedId) ?? null,
    [selectedId, users],
  )
  const enabledUsers = users.filter((account) => account.enabled).length
  const adminUsers = users.filter((account) => account.enabled && account.authorities.includes('ROLE_ADMIN')).length
  const traders = users.filter((account) => account.canTrade).length

  const loadUsers = useCallback(async () => {
    if (!token) return
    setLoading(true)
    setError(null)
    try {
      const loaded = await adminUsersApi.list(token)
      setUsers(loaded)
      setSelectedId((current) => {
        if (editingNew) return current
        if (current && loaded.some((candidate) => candidate.id === current)) return current
        return loaded[0]?.id ?? null
      })
    } catch (loadError: unknown) {
      setError(loadError instanceof Error ? loadError.message : 'Users could not be loaded')
    } finally {
      setLoading(false)
    }
  }, [editingNew, token])

  useEffect(() => {
    if (!token) return
    void loadUsers()
  }, [loadUsers, token])

  useEffect(() => {
    if (editingNew) {
      setDraft(emptyDraft())
      return
    }
    if (selectedUser) setDraft(draftFromUser(selectedUser))
  }, [editingNew, selectedUser])

  const updateDraft = <Key extends keyof UserDraft>(key: Key, value: UserDraft[Key]) => {
    setDraft((current) => ({ ...current, [key]: value }))
  }

  const createNew = () => {
    setEditingNew(true)
    setSelectedId(null)
    setError(null)
    setNotice(null)
  }

  const selectUser = (account: AdminUser) => {
    setEditingNew(false)
    setSelectedId(account.id)
    setError(null)
    setNotice(null)
  }

  const saveUser = async (event: FormEvent) => {
    event.preventDefault()
    if (!token) return
    setSaving(true)
    setError(null)
    setNotice(null)
    try {
      const password = draft.password.trim()
      if (draft.id === null && password.length === 0) throw new Error('Password is required')
      const account = {
        username: draft.username.trim(),
        email: draft.email.trim(),
        desk: draft.desk.trim(),
        canTrade: draft.canTrade,
        authorities: authorities(draft),
      }
      let saved = draft.id === null
        ? await adminUsersApi.create(token, { ...account, password })
        : await adminUsersApi.update(token, draft.id, { ...account, enabled: draft.enabled })
      if (draft.id !== null && password.length > 0) {
        saved = await adminUsersApi.updatePassword(token, draft.id, password)
      }
      setUsers((current) => upsertUser(current, saved))
      setSelectedId(saved.id)
      setEditingNew(false)
      setDraft({ ...draftFromUser(saved), password: '' })
      setNotice(`${saved.username} saved`)
    } catch (saveError: unknown) {
      setError(saveError instanceof Error ? saveError.message : 'User could not be saved')
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      {(error || notice) && (
        <div className={error ? 'admin-toast admin-toast--error' : 'admin-toast'} role="status">
          <span>{error ?? notice}</span>
          <button type="button" aria-label="Dismiss message" onClick={() => { setError(null); setNotice(null) }}>×</button>
        </div>
      )}

      <main className="admin-users-main">
        <section className="admin-metrics" aria-label="User metrics">
          <div><span>Users</span><strong>{users.length}</strong></div>
          <div><span>Enabled</span><strong>{enabledUsers}</strong></div>
          <div><span>Admins</span><strong>{adminUsers}</strong></div>
          <div><span>Traders</span><strong>{traders}</strong></div>
        </section>

        <section className="admin-users-grid">
          <aside className="admin-directory">
            <div className="admin-panel-heading">
              <div><span>Directory</span><h1>User accounts</h1></div>
              <div>
                <button type="button" onClick={() => void loadUsers()} disabled={loading}>Refresh</button>
                <button type="button" onClick={createNew}>New user</button>
              </div>
            </div>
            <div className="admin-table-wrap">
              <table>
                <thead>
                  <tr><th>User</th><th>Desk</th><th>Role</th><th>Status</th></tr>
                </thead>
                <tbody>
                  {loading && users.length === 0 ? (
                    <tr><td colSpan={4} className="admin-empty-row">Loading users...</td></tr>
                  ) : users.length === 0 ? (
                    <tr><td colSpan={4} className="admin-empty-row">No users found.</td></tr>
                  ) : users.map((account) => (
                    <tr
                      key={account.id}
                      className={account.id === selectedId ? 'selected' : ''}
                      onClick={() => selectUser(account)}
                    >
                      <td><strong>{account.username}</strong><small>{account.email}</small></td>
                      <td>{account.desk}</td>
                      <td>{account.authorities.includes('ROLE_ADMIN') ? 'Admin' : 'User'}</td>
                      <td><span className={account.enabled ? 'admin-status admin-status--enabled' : 'admin-status'}><i />{account.enabled ? 'Enabled' : 'Disabled'}</span></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </aside>

          <section className="admin-editor">
            <div className="admin-panel-heading">
              <div>
                <span>{draft.id === null ? 'Create' : 'Edit'}</span>
                <h2>{draft.id === null ? 'New user' : draft.username}</h2>
              </div>
              {selectedUser && <small>Created {formatDate(selectedUser.createdAt)}</small>}
            </div>

            <form className="admin-user-form" onSubmit={(event) => void saveUser(event)}>
              <div className="admin-form-grid">
                <label>Username<input value={draft.username} onChange={(event) => updateDraft('username', event.target.value)} required maxLength={100} /></label>
                <label>Email<input type="email" value={draft.email} onChange={(event) => updateDraft('email', event.target.value)} required maxLength={320} /></label>
                <label>Desk<input value={draft.desk} onChange={(event) => updateDraft('desk', event.target.value)} required maxLength={100} /></label>
                <label>{draft.id === null ? 'Password' : 'New password'}<input type="password" value={draft.password} onChange={(event) => updateDraft('password', event.target.value)} required={draft.id === null} minLength={8} autoComplete="new-password" /></label>
              </div>

              <div className="admin-toggle-grid">
                <label><input type="checkbox" checked={draft.enabled} onChange={(event) => updateDraft('enabled', event.target.checked)} />Enabled</label>
                <label><input type="checkbox" checked={draft.canTrade} onChange={(event) => updateDraft('canTrade', event.target.checked)} />Can trade</label>
                <label><input type="checkbox" checked={draft.isAdmin} onChange={(event) => updateDraft('isAdmin', event.target.checked)} />Administrator</label>
              </div>

              <div className="admin-form-actions">
                <button className="admin-primary-action" type="submit" disabled={saving}>{saving ? 'Saving...' : 'Save user'}</button>
                <button type="button" onClick={createNew}>Clear</button>
              </div>
            </form>
          </section>
        </section>
      </main>
    </>
  )
}
