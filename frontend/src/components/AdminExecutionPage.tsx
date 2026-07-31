import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import {
  adminExecutionApi,
  type AdminExecution,
  type ExecutionStrategy,
} from '../admin/execution-api'
import { useAuth } from '../auth/useAuth'
import '../admin/users.css'

interface ExecutionFilterDraft {
  deskId: string
  venue: string
  destination: string
}

function compactNumber(value: number | null): string {
  if (value === null) return '-'
  return new Intl.NumberFormat(undefined, { maximumFractionDigits: 6 }).format(value)
}

function money(value: number): string {
  return new Intl.NumberFormat(undefined, { maximumFractionDigits: 6 }).format(value)
}

function dateTime(value: string): string {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function shortId(value: string): string {
  return value.slice(0, 8)
}

export function AdminExecutionPage() {
  const { user } = useAuth()
  const token = user?.access_token ?? ''
  const [filters, setFilters] = useState<ExecutionFilterDraft>({ deskId: '', venue: '', destination: '' })
  const [executions, setExecutions] = useState<AdminExecution[]>([])
  const [strategies, setStrategies] = useState<ExecutionStrategy[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const activeStrategies = strategies.filter((strategy) =>
    strategy.status === 'LIVE' || strategy.status === 'PARTIALLY_FILLED').length
  const venues = useMemo(
    () => new Set(executions.map((execution) => execution.venue)).size,
    [executions],
  )
  const tradedQuantity = executions.reduce((total, execution) => total + execution.quantity, 0)

  const loadExecution = useCallback(async (draft: ExecutionFilterDraft) => {
    if (!token) return
    setLoading(true)
    setError(null)
    const requestFilters = {
      deskId: draft.deskId.trim() || undefined,
      venue: draft.venue.trim() || undefined,
      destination: draft.destination || undefined,
      limit: 100,
    }
    try {
      const [loadedExecutions, loadedStrategies] = await Promise.all([
        adminExecutionApi.executions(token, requestFilters),
        adminExecutionApi.strategies(token, { deskId: requestFilters.deskId, limit: 100 }),
      ])
      setExecutions(loadedExecutions)
      setStrategies(loadedStrategies)
    } catch (loadError: unknown) {
      setError(loadError instanceof Error ? loadError.message : 'Execution state could not be loaded')
    } finally {
      setLoading(false)
    }
  }, [token])

  useEffect(() => {
    if (!token) return
    void loadExecution({ deskId: '', venue: '', destination: '' })
  }, [loadExecution, token])

  const submit = (event: FormEvent) => {
    event.preventDefault()
    void loadExecution(filters)
  }

  return (
    <>
      {error && (
        <div className="admin-toast admin-toast--error" role="status">
          <span>{error}</span>
          <button type="button" aria-label="Dismiss message" onClick={() => setError(null)}>×</button>
        </div>
      )}

      <main className="admin-users-main">
        <section className="admin-metrics" aria-label="Execution metrics">
          <div><span>Executions</span><strong>{executions.length}</strong></div>
          <div><span>Traded quantity</span><strong>{compactNumber(tradedQuantity)}</strong></div>
          <div><span>Strategies</span><strong>{strategies.length}</strong></div>
          <div><span>Active</span><strong>{activeStrategies}</strong></div>
        </section>

        <section className="admin-read-panel">
          <div className="admin-panel-heading">
            <div><span>Execution</span><h1>Execution Monitoring</h1></div>
            <form className="admin-filter-bar" onSubmit={submit}>
              <label>Desk<input value={filters.deskId} onChange={(event) => setFilters((current) => ({ ...current, deskId: event.target.value }))} /></label>
              <label>Venue<input value={filters.venue} onChange={(event) => setFilters((current) => ({ ...current, venue: event.target.value }))} /></label>
              <label>Route
                <select value={filters.destination} onChange={(event) => setFilters((current) => ({ ...current, destination: event.target.value }))}>
                  <option value="">All</option>
                  <option value="DMA">DMA</option>
                  <option value="SMART">SMART</option>
                  <option value="VWAP">VWAP</option>
                </select>
              </label>
              <button type="submit" disabled={loading}>{loading ? 'Loading...' : 'Refresh'}</button>
            </form>
          </div>

          <div className="admin-state-strip" aria-label="Execution state">
            <div><span>Venues</span><strong>{venues}</strong></div>
            <div><span>VWAP</span><strong>{strategies.filter((strategy) => strategy.destination === 'VWAP').length}</strong></div>
            <div><span>SMART</span><strong>{strategies.filter((strategy) => strategy.destination === 'SMART').length}</strong></div>
            <div><span>Last fill</span><strong>{executions[0] ? dateTime(executions[0].executedAt) : '-'}</strong></div>
          </div>

          <div className="admin-monitor-grid">
            <section>
              <div className="admin-panel-heading admin-panel-heading--compact">
                <div><span>Fills</span><h2>Recent executions</h2></div>
              </div>
              <div className="admin-table-wrap">
                <table className="admin-data-table">
                  <thead>
                    <tr><th>Order</th><th>Desk</th><th>Route</th><th>Fill</th><th>Venue</th><th>Time</th></tr>
                  </thead>
                  <tbody>
                    {loading && executions.length === 0 ? (
                      <tr><td colSpan={6} className="admin-empty-row">Loading executions...</td></tr>
                    ) : executions.length === 0 ? (
                      <tr><td colSpan={6} className="admin-empty-row">No executions found.</td></tr>
                    ) : executions.map((execution) => (
                      <tr key={execution.id}>
                        <td><strong>{execution.symbol} {execution.side}</strong><small>{shortId(execution.orderId)} / {shortId(execution.rootOrderId)}</small></td>
                        <td>{execution.deskId}</td>
                        <td>{execution.destination}</td>
                        <td>{compactNumber(execution.quantity)} @ {money(execution.price)}</td>
                        <td>{execution.venue}</td>
                        <td>{dateTime(execution.executedAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>

            <section>
              <div className="admin-panel-heading admin-panel-heading--compact">
                <div><span>Strategies</span><h2>Parent orders</h2></div>
              </div>
              <div className="admin-table-wrap">
                <table className="admin-data-table">
                  <thead>
                    <tr><th>Order</th><th>Desk</th><th>Status</th><th>Progress</th><th>Children</th><th>Updated</th></tr>
                  </thead>
                  <tbody>
                    {loading && strategies.length === 0 ? (
                      <tr><td colSpan={6} className="admin-empty-row">Loading strategies...</td></tr>
                    ) : strategies.length === 0 ? (
                      <tr><td colSpan={6} className="admin-empty-row">No strategies found.</td></tr>
                    ) : strategies.map((strategy) => (
                      <tr key={strategy.orderId}>
                        <td><strong>{strategy.symbol} {strategy.destination}</strong><small>{shortId(strategy.orderId)}</small></td>
                        <td>{strategy.deskId}</td>
                        <td><span className={strategy.status === 'FILLED' ? 'admin-status admin-status--enabled' : 'admin-status'}><i />{strategy.status}</span></td>
                        <td>{compactNumber(strategy.tradedQuantity)} / {compactNumber(strategy.quantity)}</td>
                        <td>{strategy.childOrderCount}</td>
                        <td>{dateTime(strategy.updatedAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          </div>
        </section>
      </main>
    </>
  )
}
