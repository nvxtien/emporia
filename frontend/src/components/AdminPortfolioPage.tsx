import { useCallback, useMemo, useState, type FormEvent } from 'react'
import {
  adminPortfolioApi,
  type PortfolioBalance,
  type PortfolioState,
} from '../admin/portfolio-api'
import { useAuth } from '../auth/useAuth'
import '../admin/users.css'

const EMPTY_BALANCES = `[
  { "assetId": 840, "amount": 100000 },
  { "assetId": 1, "amount": 25 }
]`

function compactNumber(value: number): string {
  return new Intl.NumberFormat(undefined, { maximumFractionDigits: 6 }).format(value)
}

function dateTime(value: string): string {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function parseBalances(value: string): PortfolioBalance[] {
  const parsed = JSON.parse(value) as unknown
  if (!Array.isArray(parsed)) throw new Error('Balances JSON must be an array')
  return parsed as PortfolioBalance[]
}

export function AdminPortfolioPage() {
  const { user } = useAuth()
  const token = user?.access_token ?? ''
  const [clientIdInput, setClientIdInput] = useState('')
  const [provisionClientIdInput, setProvisionClientIdInput] = useState('')
  const [firstTransactionInput, setFirstTransactionInput] = useState('')
  const [balancesText, setBalancesText] = useState(EMPTY_BALANCES)
  const [portfolio, setPortfolio] = useState<PortfolioState | null>(null)
  const [loading, setLoading] = useState(false)
  const [provisioning, setProvisioning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const totalBalance = useMemo(
    () => portfolio?.balances.reduce((total, balance) => total + balance.amount, 0) ?? 0,
    [portfolio],
  )
  const largestBalance = useMemo(
    () => portfolio?.balances.reduce((largest, balance) => Math.max(largest, balance.amount), 0) ?? 0,
    [portfolio],
  )

  const loadPortfolio = useCallback(async (clientId: number) => {
    if (!token) return
    setLoading(true)
    setError(null)
    setNotice(null)
    try {
      setPortfolio(await adminPortfolioApi.state(token, clientId))
    } catch (loadError: unknown) {
      setPortfolio(null)
      setError(loadError instanceof Error ? loadError.message : 'Portfolio state could not be loaded')
    } finally {
      setLoading(false)
    }
  }, [token])

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const clientId = Number(clientIdInput)
    if (!Number.isSafeInteger(clientId) || clientId <= 0) {
      setError('Client id must be a positive number')
      setPortfolio(null)
      return
    }
    void loadPortfolio(clientId)
  }

  const provision = async (event: FormEvent) => {
    event.preventDefault()
    if (!token) return
    const clientId = Number(provisionClientIdInput)
    const firstTransactionId = Number(firstTransactionInput)
    if (!Number.isSafeInteger(clientId) || clientId <= 0) {
      setError('Client id must be a positive number')
      return
    }
    if (!Number.isSafeInteger(firstTransactionId) || firstTransactionId <= 0) {
      setError('First transaction id must be a positive number')
      return
    }
    setProvisioning(true)
    setError(null)
    setNotice(null)
    try {
      const created = await adminPortfolioApi.provision(token, clientId, {
        firstTransactionId,
        balances: parseBalances(balancesText),
      })
      setPortfolio(created)
      setClientIdInput(String(created.clientId))
      setNotice(`Portfolio ${created.clientId} provisioned`)
    } catch (provisionError: unknown) {
      setPortfolio(null)
      setError(provisionError instanceof Error ? provisionError.message : 'Portfolio could not be provisioned')
    } finally {
      setProvisioning(false)
    }
  }

  return (
    <>
      {error && (
        <div className="admin-toast admin-toast--error" role="status">
          <span>{error}</span>
          <button type="button" aria-label="Dismiss message" onClick={() => setError(null)}>×</button>
        </div>
      )}
      {notice && (
        <div className="admin-toast" role="status">
          <span>{notice}</span>
          <button type="button" aria-label="Dismiss message" onClick={() => setNotice(null)}>×</button>
        </div>
      )}

      <main className="admin-users-main">
        <section className="admin-metrics" aria-label="Portfolio metrics">
          <div><span>Client</span><strong>{portfolio?.clientId ?? '-'}</strong></div>
          <div><span>Balances</span><strong>{portfolio?.balances.length ?? 0}</strong></div>
          <div><span>Total units</span><strong>{compactNumber(totalBalance)}</strong></div>
          <div><span>Largest asset</span><strong>{compactNumber(largestBalance)}</strong></div>
        </section>

        <section className="admin-read-panel">
          <div className="admin-panel-heading">
            <div><span>Portfolio</span><h1>Portfolio State</h1></div>
            <form className="admin-filter-bar" onSubmit={submit}>
              <label>Client<input inputMode="numeric" placeholder="Client id" value={clientIdInput} onChange={(event) => setClientIdInput(event.target.value)} /></label>
              <button type="submit" disabled={loading}>{loading ? 'Loading...' : 'Load'}</button>
            </form>
          </div>

          <div className="admin-state-strip" aria-label="Portfolio receipt state">
            <div><span>First transaction</span><strong>{portfolio ? compactNumber(portfolio.firstTransactionId) : '-'}</strong></div>
            <div><span>Updated</span><strong>{portfolio ? dateTime(portfolio.updatedAt) : '-'}</strong></div>
            <div><span>Latest receipt</span><strong>{portfolio?.latestReceipt?.eventId ?? '-'}</strong></div>
            <div><span>Exchange delivery</span><strong>{portfolio?.latestReceipt ? `${portfolio.latestReceipt.exchangeId} / ${portfolio.latestReceipt.deliveryId}` : '-'}</strong></div>
          </div>

          <form className="admin-write-panel admin-provision-panel" onSubmit={provision}>
            <div className="admin-panel-heading admin-panel-heading--compact">
              <div><span>Controlled write</span><h2>Provision Portfolio</h2></div>
              <button type="submit" className="admin-primary-action" disabled={provisioning || loading}>
                {provisioning ? 'Provisioning...' : 'Provision'}
              </button>
            </div>
            <div className="admin-provision-grid">
              <label>New client<input inputMode="numeric" placeholder="Client id" value={provisionClientIdInput} onChange={(event) => setProvisionClientIdInput(event.target.value)} /></label>
              <label>First transaction<input inputMode="numeric" placeholder="Transaction id" value={firstTransactionInput} onChange={(event) => setFirstTransactionInput(event.target.value)} /></label>
              <label>Balances JSON<textarea aria-label="Portfolio balances JSON" spellCheck={false} value={balancesText} onChange={(event) => setBalancesText(event.target.value)} /></label>
            </div>
          </form>

          <div className="admin-table-wrap">
            <table className="admin-data-table">
              <thead>
                <tr><th>Asset</th><th>Available balance</th><th>Share</th></tr>
              </thead>
              <tbody>
                {loading && !portfolio ? (
                  <tr><td colSpan={3} className="admin-empty-row">Loading portfolio state...</td></tr>
                ) : !portfolio ? (
                  <tr><td colSpan={3} className="admin-empty-row">No portfolio selected.</td></tr>
                ) : portfolio.balances.length === 0 ? (
                  <tr><td colSpan={3} className="admin-empty-row">No balances recorded.</td></tr>
                ) : portfolio.balances.map((balance) => (
                  <tr key={balance.assetId}>
                    <td><strong>Asset {balance.assetId}</strong><small>ID {balance.assetId}</small></td>
                    <td>{compactNumber(balance.amount)}</td>
                    <td>{totalBalance > 0 ? `${((balance.amount / totalBalance) * 100).toFixed(2)}%` : '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      </main>
    </>
  )
}
