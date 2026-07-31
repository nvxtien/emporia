export interface AdminNavItem {
  path: string
  label: string
  description: string
}

export const adminNavItems: AdminNavItem[] = [
  { path: '/admin/users', label: 'Users', description: 'Accounts and roles' },
  { path: '/admin/permissions', label: 'Permissions', description: 'Role policies' },
  { path: '/admin/static-data', label: 'Static data', description: 'Listings and venues' },
  { path: '/admin/execution', label: 'Execution', description: 'Strategy health' },
  { path: '/admin/portfolio', label: 'Portfolio state', description: 'Positions and cash' },
  { path: '/admin/audit', label: 'Audit', description: 'Write history' },
]
