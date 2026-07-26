import type {
  CreateOrder,
  Execution,
  Listing,
  OrderEvent,
  Quote,
  TradingOrder,
  WatchlistItem,
  WorkspaceLayout,
  WorkspacePreference,
} from './types'

interface ApiProblem {
  detail?: string
  title?: string
}

async function request<T>(accessToken: string, path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, {
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
    throw new Error(problem?.detail ?? problem?.title ?? `Trading API returned ${response.status}`)
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

async function streamQuotes(
  accessToken: string,
  listingIds: number[],
  onQuote: (quote: Quote) => void,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(`/api/market-data/stream?listingIds=${listingIds.join(',')}`, {
    headers: {
      Accept: 'text/event-stream',
      Authorization: `Bearer ${accessToken}`,
    },
    signal,
  })
  if (!response.ok) {
    throw new Error(`Market-data stream returned ${response.status}`)
  }
  if (!response.body) {
    throw new Error('Market-data stream returned no response body')
  }

  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffered = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) return
    buffered += value.replaceAll('\r\n', '\n')
    let boundary = buffered.indexOf('\n\n')
    while (boundary >= 0) {
      const event = buffered.slice(0, boundary)
      buffered = buffered.slice(boundary + 2)
      const eventType = event.split('\n').find((line) => line.startsWith('event:'))?.slice(6).trim()
      const data = event.split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trimStart())
        .join('\n')
      if (eventType === 'quote' && data !== '') {
        onQuote(JSON.parse(data) as Quote)
      }
      boundary = buffered.indexOf('\n\n')
    }
  }
}

async function streamOrders(
  accessToken: string,
  onOrder: (order: TradingOrder) => void,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch('/api/orders/stream', {
    headers: { Accept: 'text/event-stream', Authorization: `Bearer ${accessToken}` },
    signal,
  })
  if (!response.ok) throw new Error(`Order stream returned ${response.status}`)
  if (!response.body) throw new Error('Order stream returned no response body')
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffered = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) return
    buffered += value.replaceAll('\r\n', '\n')
    let boundary = buffered.indexOf('\n\n')
    while (boundary >= 0) {
      const event = buffered.slice(0, boundary)
      buffered = buffered.slice(boundary + 2)
      const eventType = event.split('\n').find((line) => line.startsWith('event:'))?.slice(6).trim()
      const data = event.split('\n').filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trimStart()).join('\n')
      if (eventType === 'order' && data !== '') onOrder(JSON.parse(data) as TradingOrder)
      boundary = buffered.indexOf('\n\n')
    }
  }
}

export const tradingApi = {
  instruments: (token: string, query: string) =>
    request<Listing[]>(token, `/instruments?query=${encodeURIComponent(query)}`),
  watchlist: (token: string) => request<WatchlistItem[]>(token, '/watchlist'),
  addToWatchlist: (token: string, listingId: number) =>
    request<WatchlistItem>(token, `/watchlist/${listingId}`, { method: 'POST' }),
  removeFromWatchlist: (token: string, listingId: number) =>
    request<void>(token, `/watchlist/${listingId}`, { method: 'DELETE' }),
  quotes: (token: string, listingIds: number[]) =>
    request<Quote[]>(token, `/market-data/quotes?listingIds=${listingIds.join(',')}`),
  streamQuotes,
  streamOrders,
  depth: (token: string, listingId: number) =>
    request<Quote>(token, `/market-data/${listingId}/depth`),
  orders: (token: string) => request<TradingOrder[]>(token, '/orders'),
  createOrder: (token: string, order: CreateOrder) =>
    request<TradingOrder>(token, '/orders', { method: 'POST', body: JSON.stringify(order) }),
  modifyOrder: (token: string, orderId: string, expectedVersion: number, quantity: number, limitPrice: number | null) =>
    request<TradingOrder>(token, `/orders/${orderId}`, {
      method: 'PUT',
      body: JSON.stringify({ expectedVersion, quantity, limitPrice }),
    }),
  cancelOrder: (token: string, orderId: string) =>
    request<TradingOrder>(token, `/orders/${orderId}/cancel`, { method: 'POST' }),
  cancelAll: (token: string) =>
    request<{ cancelled: number }>(token, '/orders/cancel-all', { method: 'POST' }),
  orderHistory: (token: string, orderId: string) =>
    request<OrderEvent[]>(token, `/orders/${orderId}/history`),
  executions: (token: string, orderId: string) =>
    request<Execution[]>(token, `/orders/${orderId}/executions`),
  workspacePreference: (token: string) =>
    request<WorkspacePreference>(token, '/workspace-preferences'),
  saveWorkspacePreference: (token: string, layout: WorkspaceLayout) =>
    request<WorkspacePreference>(token, '/workspace-preferences', {
      method: 'PUT',
      body: JSON.stringify({ layoutJson: JSON.stringify(layout) }),
    }),
}
