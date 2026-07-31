export type AdminOrderSide = 'BUY' | 'SELL'
export type AdminOrderStatus = 'LIVE' | 'PARTIALLY_FILLED' | 'FILLED' | 'CANCELLED' | 'REJECTED'
export type AdminExecutionDestination = 'DMA' | 'SMART' | 'VWAP'

export interface AdminExecution {
  id: string
  executionReference: string
  orderId: string
  rootOrderId: string
  parentOrderId: string | null
  deskId: string
  ownerSubject: string
  symbol: string
  side: AdminOrderSide
  destination: AdminExecutionDestination
  orderStatus: AdminOrderStatus
  quantity: number
  price: number
  venue: string
  executedAt: string
}

export interface ExecutionStrategy {
  orderId: string
  rootOrderId: string
  deskId: string
  ownerSubject: string
  symbol: string
  side: AdminOrderSide
  destination: Extract<AdminExecutionDestination, 'SMART' | 'VWAP'>
  status: AdminOrderStatus
  targetStatus: AdminOrderStatus
  quantity: number
  tradedQuantity: number
  remainingQuantity: number
  averageTradePrice: number | null
  childOrderCount: number
  createdAt: string
  updatedAt: string
}

export interface ExecutionFilters {
  deskId?: string
  venue?: string
  destination?: string
  limit?: number
}

interface ApiProblem {
  detail?: string
  title?: string
  message?: string
  error?: string
}

function fallbackMessage(status: number): string {
  if (status === 400) return 'Check the execution filters and try again'
  if (status === 401) return 'Sign in again to view execution state'
  if (status === 403) return 'Administrator access is required'
  return `Execution API returned ${status}`
}

function query(filters: ExecutionFilters): string {
  const params = new URLSearchParams()
  if (filters.deskId) params.set('deskId', filters.deskId)
  if (filters.venue) params.set('venue', filters.venue)
  if (filters.destination) params.set('destination', filters.destination)
  if (filters.limit) params.set('limit', String(filters.limit))
  const value = params.toString()
  return value ? `?${value}` : ''
}

async function request<T>(accessToken: string, path: string): Promise<T> {
  const response = await fetch(`/api/orders${path}`, {
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

export const adminExecutionApi = {
  executions: (token: string, filters: ExecutionFilters) =>
    request<AdminExecution[]>(token, `/executions${query(filters)}`),
  strategies: (token: string, filters: Pick<ExecutionFilters, 'deskId' | 'limit'>) =>
    request<ExecutionStrategy[]>(token, `/strategies${query(filters)}`),
}
