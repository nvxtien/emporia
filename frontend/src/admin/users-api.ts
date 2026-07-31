export type AdminAuthority = 'ROLE_USER' | 'ROLE_ADMIN'

export interface AdminUser {
  id: string
  username: string
  email: string
  enabled: boolean
  desk: string
  canTrade: boolean
  authorities: AdminAuthority[]
  createdAt: string
}

export interface CreateAdminUser {
  username: string
  email: string
  password: string
  desk: string
  canTrade: boolean
  authorities: AdminAuthority[]
}

export interface UpdateAdminUser {
  username: string
  email: string
  enabled: boolean
  desk: string
  canTrade: boolean
  authorities: AdminAuthority[]
}

interface ApiProblem {
  detail?: string
  title?: string
  message?: string
  error?: string
}

function fallbackMessage(status: number): string {
  if (status === 400) return 'Check the user details and try again'
  if (status === 401) return 'Sign in again to manage users'
  if (status === 403) return 'Administrator access is required'
  if (status === 404) return 'User was not found'
  if (status === 409) return 'That change conflicts with current user-management rules'
  return `Admin API returned ${status}`
}

async function request<T>(accessToken: string, path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/admin/users${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  })

  if (!response.ok) {
    let problem: ApiProblem | undefined
    try {
      problem = await response.json() as ApiProblem
    } catch {
      problem = undefined
    }
    throw new Error(problem?.detail ?? problem?.message ?? problem?.title ?? problem?.error ?? fallbackMessage(response.status))
  }

  return response.json() as Promise<T>
}

export const adminUsersApi = {
  list: (token: string) => request<AdminUser[]>(token, ''),
  create: (token: string, user: CreateAdminUser) =>
    request<AdminUser>(token, '', { method: 'POST', body: JSON.stringify(user) }),
  update: (token: string, userId: string, user: UpdateAdminUser) =>
    request<AdminUser>(token, `/${userId}`, { method: 'PUT', body: JSON.stringify(user) }),
  updatePassword: (token: string, userId: string, password: string) =>
    request<AdminUser>(token, `/${userId}/password`, {
      method: 'PUT',
      body: JSON.stringify({ password }),
    }),
  updateTradingIdentity: (token: string, userId: string, desk: string, canTrade: boolean) =>
    request<AdminUser>(token, `/${userId}/trading-identity`, {
      method: 'PUT',
      body: JSON.stringify({ desk, canTrade }),
    }),
}
