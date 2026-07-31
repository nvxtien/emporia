export interface PortfolioBalance {
  assetId: number
  amount: number
}

export interface LatestPortfolioReceipt {
  eventId: string
  exchangeId: string
  deliveryId: number
  receivedAt: string
}

export interface PortfolioState {
  schemaVersion: number
  clientId: number
  firstTransactionId: number
  updatedAt: string
  balances: PortfolioBalance[]
  latestReceipt: LatestPortfolioReceipt | null
}

export interface ProvisionPortfolioRequest {
  firstTransactionId: number
  balances: PortfolioBalance[]
}

interface ApiProblem {
  detail?: string
  title?: string
  message?: string
  error?: string
}

function fallbackMessage(status: number): string {
  if (status === 400) return 'Check the portfolio client id and try again'
  if (status === 401) return 'Sign in again to view portfolio state'
  if (status === 403) return 'Administrator access is required'
  if (status === 404) return 'Portfolio client was not found'
  if (status === 409) return 'Portfolio already exists for this client'
  return `Portfolio API returned ${status}`
}

async function request<T>(accessToken: string, path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api/portfolio${path}`, {
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

export const adminPortfolioApi = {
  state: (token: string, clientId: number) => request<PortfolioState>(token, `/state/${clientId}`),
  provision: (token: string, clientId: number, payload: ProvisionPortfolioRequest) =>
    request<PortfolioState>(token, `/state/${clientId}`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
}
