export type AdminAuditSource =
  | 'all'
  | 'authorisation-service'
  | 'static-data-service'
  | 'portfolio-service'

export interface AdminAuditEvent {
  id: string
  source?: Exclude<AdminAuditSource, 'all'>
  occurredAt: string
  actorSubject: string
  actorUsername: string
  actorDesk: string
  action: string
  entityType: string
  entityId: string
  result: string
  requestId: string | null
  beforeJson: string | null
  afterJson: string | null
  metadataJson: string | null
}

export interface AdminAuditEventPage {
  items: AdminAuditEvent[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

export interface AdminAuditFilters {
  actor?: string
  action?: string
  entityType?: string
  entityId?: string
  result?: string
  source?: AdminAuditSource
  page?: number
  size?: number
}

interface ApiProblem {
  detail?: string
  title?: string
  message?: string
  error?: string
}

function fallbackMessage(status: number): string {
  if (status === 400) return 'Check the audit filters and try again'
  if (status === 401) return 'Sign in again to view audit events'
  if (status === 403) return 'Administrator access is required'
  return `Audit API returned ${status}`
}

function query(filters: AdminAuditFilters): string {
  const params = new URLSearchParams()
  if (filters.actor) params.set('actor', filters.actor)
  if (filters.action) params.set('action', filters.action)
  if (filters.entityType) params.set('entityType', filters.entityType)
  if (filters.entityId) params.set('entityId', filters.entityId)
  if (filters.result) params.set('result', filters.result)
  if (filters.page !== undefined) params.set('page', String(filters.page))
  if (filters.size) params.set('size', String(filters.size))
  const value = params.toString()
  return value ? `?${value}` : ''
}

const AUDIT_ENDPOINTS: Array<{ source: Exclude<AdminAuditSource, 'all'>; basePath: string }> = [
  { source: 'authorisation-service', basePath: '/api/admin/audit' },
  { source: 'static-data-service', basePath: '/api/admin/static-data/audit' },
  { source: 'portfolio-service', basePath: '/api/portfolio/audit' },
]

async function request<T>(accessToken: string, basePath: string, path: string): Promise<T> {
  const response = await fetch(`${basePath}${path}`, {
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
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

function withSource(
  page: AdminAuditEventPage,
  source: Exclude<AdminAuditSource, 'all'>,
): AdminAuditEventPage {
  return {
    ...page,
    items: page.items.map((event) => ({ ...event, source })),
  }
}

function combinedPage(
  pages: AdminAuditEventPage[],
  page: number,
  size: number,
): AdminAuditEventPage {
  const items = pages
    .flatMap((loadedPage) => loadedPage.items)
    .sort((left, right) => new Date(right.occurredAt).getTime() - new Date(left.occurredAt).getTime())
  const totalElements = pages.reduce((total, loadedPage) => total + loadedPage.totalElements, 0)
  const start = page * size
  const totalPages = totalElements === 0 ? 0 : Math.ceil(totalElements / size)
  return {
    items: items.slice(start, start + size),
    page,
    size,
    totalElements,
    totalPages,
    first: page === 0,
    last: page + 1 >= totalPages,
  }
}

export const adminAuditApi = {
  events: async (token: string, filters: AdminAuditFilters) => {
    const source = filters.source ?? 'all'
    const page = Math.max(0, filters.page ?? 0)
    const size = filters.size ?? 50
    if (source !== 'all') {
      const endpoint = AUDIT_ENDPOINTS.find((candidate) => candidate.source === source)
      if (!endpoint) throw new Error('Unknown audit source')
      return withSource(
        await request<AdminAuditEventPage>(
          token,
          endpoint.basePath,
          `/events${query({ ...filters, page, size, source: undefined })}`,
        ),
        endpoint.source,
      )
    }

    const broadSize = Math.max(size, 200)
    const results = await Promise.allSettled(
      AUDIT_ENDPOINTS.map(async (endpoint) => withSource(
        await request<AdminAuditEventPage>(
          token,
          endpoint.basePath,
          `/events${query({ ...filters, page: 0, size: broadSize, source: undefined })}`,
        ),
        endpoint.source,
      )),
    )
    const pages = results
      .filter((result): result is PromiseFulfilledResult<AdminAuditEventPage> => result.status === 'fulfilled')
      .map((result) => result.value)
    if (pages.length === 0) {
      const firstError = results.find((result): result is PromiseRejectedResult => result.status === 'rejected')
      throw firstError?.reason instanceof Error ? firstError.reason : new Error('Audit events could not be loaded')
    }
    return combinedPage(pages, page, size)
  },
}
