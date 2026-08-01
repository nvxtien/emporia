import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import {
  adminAuditApi,
  type AdminAuditSource,
  type AdminAuditEvent,
  type AdminAuditEventPage,
} from '../admin/audit-api'
import { useAuth } from '../auth/useAuth'
import '../admin/users.css'

interface AuditFilterDraft {
  actor: string
  action: string
  entityType: string
  entityId: string
  result: string
  source: AdminAuditSource
}

const EMPTY_FILTERS: AuditFilterDraft = {
  actor: '',
  action: '',
  entityType: '',
  entityId: '',
  result: '',
  source: 'all',
}
const DEFAULT_PAGE_SIZE = 50
const PAGE_SIZE_OPTIONS = [25, 50, 100, 200]
const SOURCE_OPTIONS: Array<{ value: AdminAuditSource; label: string }> = [
  { value: 'all', label: 'All services' },
  { value: 'authentication', label: 'Authentication' },
  { value: 'static-data-service', label: 'Static data' },
  { value: 'portfolio-service', label: 'Portfolio' },
]
const ACTION_OPTIONS = [
  'USER_CREATED',
  'USER_UPDATED',
  'USER_PASSWORD_CHANGED',
  'USER_TRADING_IDENTITY_UPDATED',
  'STATIC_DATA_LISTING_UPDATED',
  'STATIC_DATA_LISTINGS_IMPORTED',
  'PORTFOLIO_PROVISIONED',
]

function emptyPageInfo() {
  return {
    page: 0,
    size: DEFAULT_PAGE_SIZE,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true,
  }
}

function filtersFromDraft(draft: AuditFilterDraft, page: number, size: number) {
  return {
    actor: draft.actor.trim() || undefined,
    action: draft.action || undefined,
    entityType: draft.entityType || undefined,
    entityId: draft.entityId.trim() || undefined,
    result: draft.result || undefined,
    source: draft.source,
    page,
    size,
  }
}

function compactNumber(value: number): string {
  return new Intl.NumberFormat().format(value)
}

function dateTime(value: string): string {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value))
}

function actionLabel(value: string): string {
  return value.replaceAll('_', ' ')
}

function shortId(value: string): string {
  return value.length > 12 ? value.slice(0, 12) : value
}

function sourceLabel(value: AdminAuditEvent['source']): string {
  if (value === 'authentication') return 'Authentication'
  if (value === 'static-data-service') return 'Static data'
  if (value === 'portfolio-service') return 'Portfolio'
  return 'Unknown'
}

function pageRange(page: AdminAuditEventPage): string {
  if (page.totalElements === 0) return '0 of 0'
  const start = page.page * page.size + 1
  const end = start + page.items.length - 1
  return `${compactNumber(start)}-${compactNumber(end)} of ${compactNumber(page.totalElements)}`
}

function formatJson(value: string | null): string {
  if (!value) return 'No payload'
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

export function AdminAuditPage() {
  const { user } = useAuth()
  const token = user?.access_token ?? ''
  const [filters, setFilters] = useState<AuditFilterDraft>(EMPTY_FILTERS)
  const [appliedFilters, setAppliedFilters] = useState<AuditFilterDraft>(EMPTY_FILTERS)
  const [events, setEvents] = useState<AdminAuditEvent[]>([])
  const [pageInfo, setPageInfo] = useState(emptyPageInfo)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const selectedEvent = useMemo(
    () => events.find((event) => event.id === selectedId) ?? null,
    [events, selectedId],
  )
  const successCount = events.filter((event) => event.result === 'SUCCESS').length
  const sourceCount = useMemo(
    () => new Set(events.map((event) => event.source ?? 'unknown')).size,
    [events],
  )

  const loadAudit = useCallback(async (draft: AuditFilterDraft, page: number, size: number) => {
    if (!token) return
    setLoading(true)
    setError(null)
    try {
      const loadedPage = await adminAuditApi.events(token, filtersFromDraft(draft, page, size))
      setEvents(loadedPage.items)
      setPageInfo({
        page: loadedPage.page,
        size: loadedPage.size,
        totalElements: loadedPage.totalElements,
        totalPages: loadedPage.totalPages,
        first: loadedPage.first,
        last: loadedPage.last,
      })
      setSelectedId((current) => {
        if (current && loadedPage.items.some((event) => event.id === current)) return current
        return loadedPage.items[0]?.id ?? null
      })
    } catch (loadError: unknown) {
      setError(loadError instanceof Error ? loadError.message : 'Audit events could not be loaded')
    } finally {
      setLoading(false)
    }
  }, [token])

  useEffect(() => {
    if (!token) return
    void loadAudit(EMPTY_FILTERS, 0, DEFAULT_PAGE_SIZE)
  }, [loadAudit, token])

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setAppliedFilters(filters)
    void loadAudit(filters, 0, pageInfo.size)
  }

  const clearFilters = () => {
    setFilters(EMPTY_FILTERS)
    setAppliedFilters(EMPTY_FILTERS)
    void loadAudit(EMPTY_FILTERS, 0, pageInfo.size)
  }

  const movePage = (nextPage: number) => {
    void loadAudit(appliedFilters, nextPage, pageInfo.size)
  }

  const changePageSize = (nextSize: number) => {
    void loadAudit(appliedFilters, 0, nextSize)
  }

  const currentPageRange = pageRange({ items: events, ...pageInfo })
  const displayTotalPages = Math.max(1, pageInfo.totalPages)

  return (
    <>
      {error && (
        <div className="admin-toast admin-toast--error" role="status">
          <span>{error}</span>
          <button type="button" aria-label="Dismiss message" onClick={() => setError(null)}>×</button>
        </div>
      )}

      <main className="admin-users-main">
        <section className="admin-metrics" aria-label="Audit metrics">
          <div><span>Matching</span><strong>{compactNumber(pageInfo.totalElements)}</strong></div>
          <div><span>Loaded</span><strong>{events.length}</strong></div>
          <div><span>Success</span><strong>{successCount}</strong></div>
          <div><span>Sources</span><strong>{sourceCount}</strong></div>
        </section>

        <section className="admin-read-panel">
          <div className="admin-panel-heading">
            <div><span>Operational controls</span><h1>Audit Log</h1></div>
            <form className="admin-filter-bar admin-filter-bar--wide" onSubmit={submit}>
              <label>Source
                <select value={filters.source} onChange={(event) => setFilters((current) => ({ ...current, source: event.target.value as AdminAuditSource }))}>
                  {SOURCE_OPTIONS.map((source) => <option value={source.value} key={source.value}>{source.label}</option>)}
                </select>
              </label>
              <label>Actor<input placeholder="Username or subject" value={filters.actor} onChange={(event) => setFilters((current) => ({ ...current, actor: event.target.value }))} /></label>
              <label>Action
                <select value={filters.action} onChange={(event) => setFilters((current) => ({ ...current, action: event.target.value }))}>
                  <option value="">All</option>
                  {ACTION_OPTIONS.map((action) => <option value={action} key={action}>{actionLabel(action)}</option>)}
                </select>
              </label>
              <label>Entity
                <select value={filters.entityType} onChange={(event) => setFilters((current) => ({ ...current, entityType: event.target.value }))}>
                  <option value="">All</option>
                  <option value="USER">User</option>
                  <option value="INSTRUMENT_LISTING">Instrument listing</option>
                  <option value="INSTRUMENT_LISTING_IMPORT">Listing import</option>
                  <option value="PORTFOLIO">Portfolio</option>
                </select>
              </label>
              <label>Entity ID<input placeholder="UUID or id" value={filters.entityId} onChange={(event) => setFilters((current) => ({ ...current, entityId: event.target.value }))} /></label>
              <label>Result
                <select value={filters.result} onChange={(event) => setFilters((current) => ({ ...current, result: event.target.value }))}>
                  <option value="">All</option>
                  <option value="SUCCESS">Success</option>
                </select>
              </label>
              <button type="submit" disabled={loading}>{loading ? 'Loading...' : 'Refresh'}</button>
              <button type="button" onClick={clearFilters} disabled={loading}>Clear</button>
            </form>
          </div>

          <div className="admin-state-strip" aria-label="Audit visible state">
            <div><span>Range</span><strong>{currentPageRange}</strong></div>
            <div><span>Page</span><strong>{pageInfo.totalPages === 0 ? 0 : pageInfo.page + 1} of {displayTotalPages}</strong></div>
            <div><span>Source</span><strong>{selectedEvent ? sourceLabel(selectedEvent.source) : sourceLabel(filters.source === 'all' ? undefined : filters.source)}</strong></div>
            <div><span>Request</span><strong>{selectedEvent?.requestId ?? '-'}</strong></div>
          </div>

          <div className="admin-reference-grid admin-audit-grid">
            <section>
              <div className="admin-panel-heading admin-panel-heading--compact">
                <div><span>Events</span><h2>Administrative Writes</h2></div>
                <small>{loading ? 'Refreshing' : currentPageRange}</small>
              </div>
              <div className="admin-table-wrap">
                <table className="admin-data-table admin-audit-table">
                  <thead>
                    <tr><th>Time</th><th>Source</th><th>Action</th><th>Actor</th><th>Entity</th><th>Result</th></tr>
                  </thead>
                  <tbody>
                    {loading && events.length === 0 ? (
                      <tr><td colSpan={6} className="admin-empty-row">Loading audit events...</td></tr>
                    ) : events.length === 0 ? (
                      <tr><td colSpan={6} className="admin-empty-row">No audit events found.</td></tr>
                    ) : events.map((event) => (
                      <tr
                        key={event.id}
                        className={event.id === selectedId ? 'selected' : undefined}
                        onClick={() => setSelectedId(event.id)}
                      >
                        <td>{dateTime(event.occurredAt)}<small>{shortId(event.id)}</small></td>
                        <td><strong>{sourceLabel(event.source)}</strong><small>{event.source ?? 'unknown'}</small></td>
                        <td><strong>{actionLabel(event.action)}</strong><small>{event.requestId ?? 'No request id'}</small></td>
                        <td><strong>{event.actorUsername}</strong><small>{event.actorDesk}</small></td>
                        <td>{event.entityType}<small>{shortId(event.entityId)}</small></td>
                        <td><span className="admin-status admin-status--enabled"><i />{event.result}</span></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="admin-pagination" aria-label="Audit pagination">
                <span>Page {pageInfo.totalPages === 0 ? 0 : pageInfo.page + 1} of {displayTotalPages}</span>
                <label>Rows
                  <select value={pageInfo.size} onChange={(event) => changePageSize(Number(event.target.value))} disabled={loading}>
                    {PAGE_SIZE_OPTIONS.map((size) => <option value={size} key={size}>{size}</option>)}
                  </select>
                </label>
                <button
                  type="button"
                  aria-label="Previous audit page"
                  disabled={loading || pageInfo.first || pageInfo.totalElements === 0}
                  onClick={() => movePage(pageInfo.page - 1)}
                >
                  Previous
                </button>
                <button
                  type="button"
                  aria-label="Next audit page"
                  disabled={loading || pageInfo.last || pageInfo.totalElements === 0}
                  onClick={() => movePage(pageInfo.page + 1)}
                >
                  Next
                </button>
              </div>
            </section>

            <aside className="admin-reference-detail admin-audit-detail">
              <div className="admin-panel-heading admin-panel-heading--compact">
                <div><span>Event detail</span><h2>{selectedEvent ? actionLabel(selectedEvent.action) : 'No event'}</h2></div>
              </div>
              {selectedEvent ? (
                <>
                  <dl className="admin-detail-list">
                    <div><dt>Event ID</dt><dd>{selectedEvent.id}</dd></div>
                    <div><dt>Source</dt><dd>{sourceLabel(selectedEvent.source)}</dd></div>
                    <div><dt>Occurred</dt><dd>{dateTime(selectedEvent.occurredAt)}</dd></div>
                    <div><dt>Actor</dt><dd>{selectedEvent.actorUsername}</dd></div>
                    <div><dt>Subject</dt><dd>{selectedEvent.actorSubject}</dd></div>
                    <div><dt>Desk</dt><dd>{selectedEvent.actorDesk}</dd></div>
                    <div><dt>Entity</dt><dd>{selectedEvent.entityType} / {selectedEvent.entityId}</dd></div>
                    <div><dt>Result</dt><dd>{selectedEvent.result}</dd></div>
                  </dl>
                  <div className="admin-audit-payloads">
                    <section><h3>Before</h3><pre>{formatJson(selectedEvent.beforeJson)}</pre></section>
                    <section><h3>After</h3><pre>{formatJson(selectedEvent.afterJson)}</pre></section>
                    <section><h3>Metadata</h3><pre>{formatJson(selectedEvent.metadataJson)}</pre></section>
                  </div>
                </>
              ) : (
                <p className="admin-detail-empty">Select an audit event to inspect its structured snapshots.</p>
              )}
            </aside>
          </div>
        </section>
      </main>
    </>
  )
}
