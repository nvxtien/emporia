export interface AdminInstrumentListing {
  id: number
  version: number
  symbol: string
  name: string
  marketSymbol: string
  exchangeMic: string
  exchangeName: string
  countryCode: string
  currency: string
  enabled: boolean
  tickSize: number
  sizeIncrement: number
  referencePrice: number
  previousClose: number
}

export interface AdminExchangeFacet {
  mic: string
  name: string
  listingCount: number
}

export interface AdminStaticDataFacets {
  totalListings: number
  enabledListings: number
  disabledListings: number
  exchanges: AdminExchangeFacet[]
  currencies: string[]
  countries: string[]
}

export interface AdminInstrumentListingPage {
  items: AdminInstrumentListing[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

export interface StaticDataFilters {
  query?: string
  exchangeMic?: string
  currency?: string
  countryCode?: string
  enabled?: boolean
  page?: number
  size?: number
}

export type AdminInstrumentListingPayload = Omit<AdminInstrumentListing, 'id' | 'version'>

export interface AdminInstrumentListingImportRow extends AdminInstrumentListingPayload {
  id: number
  version?: number
}

export interface AdminStaticDataImportRequest {
  listings: AdminInstrumentListingImportRow[]
}

export interface AdminStaticDataImportResult {
  requested: number
  created: number
  updated: number
  listingIds: number[]
}

interface ApiProblem {
  detail?: string
  title?: string
  message?: string
  error?: string
}

function fallbackMessage(status: number): string {
  if (status === 400) return 'Check the static-data filters and try again'
  if (status === 401) return 'Sign in again to view static data'
  if (status === 403) return 'Administrator access is required'
  if (status === 404) return 'Static-data listing was not found'
  return `Static-data API returned ${status}`
}

function query(filters: StaticDataFilters): string {
  const params = new URLSearchParams()
  if (filters.query) params.set('query', filters.query)
  if (filters.exchangeMic) params.set('exchangeMic', filters.exchangeMic)
  if (filters.currency) params.set('currency', filters.currency)
  if (filters.countryCode) params.set('countryCode', filters.countryCode)
  if (filters.enabled !== undefined) params.set('enabled', String(filters.enabled))
  if (filters.page !== undefined) params.set('page', String(filters.page))
  if (filters.size) params.set('size', String(filters.size))
  const value = params.toString()
  return value ? `?${value}` : ''
}

async function request<T>(accessToken: string, path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api/admin/static-data${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      Authorization: `Bearer ${accessToken}`,
      ...init.headers,
    },
  })

  if (!response.ok) {
    let problem: ApiProblem | undefined
    try {
      problem = await response.json() as ApiProblem
    } catch {
      problem = undefined
    }
    throw new Error(problem?.detail ?? problem?.message ?? problem?.title ?? fallbackMessage(response.status))
  }

  return response.json() as Promise<T>
}

export const adminStaticDataApi = {
  listings: (token: string, filters: StaticDataFilters) =>
    request<AdminInstrumentListingPage>(token, `/listings${query(filters)}`),
  listing: (token: string, listingId: number) =>
    request<AdminInstrumentListing>(token, `/listings/${listingId}`),
  facets: (token: string) => request<AdminStaticDataFacets>(token, '/facets'),
  update: (token: string, listingId: number, payload: AdminInstrumentListingPayload) =>
    request<AdminInstrumentListing>(token, `/listings/${listingId}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  importListings: (token: string, payload: AdminStaticDataImportRequest) =>
    request<AdminStaticDataImportResult>(token, '/listings/import', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
}
