import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import {
  adminStaticDataApi,
  type AdminInstrumentListing,
  type AdminInstrumentListingImportRow,
  type AdminInstrumentListingPage,
  type AdminInstrumentListingPayload,
  type AdminStaticDataFacets,
} from '../admin/static-data-api'
import { useAuth } from '../auth/useAuth'
import '../admin/users.css'

interface StaticDataFilterDraft {
  query: string
  exchangeMic: string
  currency: string
  countryCode: string
  enabled: '' | 'true' | 'false'
}

interface StaticDataEditDraft {
  symbol: string
  name: string
  marketSymbol: string
  exchangeMic: string
  exchangeName: string
  countryCode: string
  currency: string
  enabled: 'true' | 'false'
  tickSize: string
  sizeIncrement: string
  referencePrice: string
  previousClose: string
}

const EMPTY_FILTERS: StaticDataFilterDraft = {
  query: '',
  exchangeMic: '',
  currency: '',
  countryCode: '',
  enabled: '',
}
const DEFAULT_PAGE_SIZE = 100
const PAGE_SIZE_OPTIONS = [50, 100, 200]
const EMPTY_IMPORT = `[
  {
    "id": 9001,
    "symbol": "DEMO",
    "name": "Demo Instrument",
    "marketSymbol": "DEMO",
    "exchangeMic": "XNAS",
    "exchangeName": "Nasdaq",
    "countryCode": "US",
    "currency": "USD",
    "enabled": true,
    "tickSize": 0.01,
    "sizeIncrement": 1,
    "referencePrice": 100,
    "previousClose": 99
  }
]`

function compactNumber(value: number): string {
  return new Intl.NumberFormat(undefined, { maximumFractionDigits: 6 }).format(value)
}

function statusLabel(enabled: boolean): string {
  return enabled ? 'Enabled' : 'Disabled'
}

function filtersFromDraft(draft: StaticDataFilterDraft, page: number, size: number) {
  return {
    query: draft.query.trim() || undefined,
    exchangeMic: draft.exchangeMic || undefined,
    currency: draft.currency || undefined,
    countryCode: draft.countryCode || undefined,
    enabled: draft.enabled === '' ? undefined : draft.enabled === 'true',
    page,
    size,
  }
}

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

function pageRange(page: AdminInstrumentListingPage): string {
  if (page.totalElements === 0) return '0 of 0'
  const start = page.page * page.size + 1
  const end = start + page.items.length - 1
  return `${compactNumber(start)}-${compactNumber(end)} of ${compactNumber(page.totalElements)}`
}

function listingToDraft(listing: AdminInstrumentListing): StaticDataEditDraft {
  return {
    symbol: listing.symbol,
    name: listing.name,
    marketSymbol: listing.marketSymbol,
    exchangeMic: listing.exchangeMic,
    exchangeName: listing.exchangeName,
    countryCode: listing.countryCode,
    currency: listing.currency,
    enabled: listing.enabled ? 'true' : 'false',
    tickSize: String(listing.tickSize),
    sizeIncrement: String(listing.sizeIncrement),
    referencePrice: String(listing.referencePrice),
    previousClose: String(listing.previousClose),
  }
}

function parseNumber(value: string, label: string): number {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) throw new Error(`${label} must be a number`)
  return parsed
}

function payloadFromDraft(draft: StaticDataEditDraft): AdminInstrumentListingPayload {
  return {
    symbol: draft.symbol.trim(),
    name: draft.name.trim(),
    marketSymbol: draft.marketSymbol.trim(),
    exchangeMic: draft.exchangeMic.trim(),
    exchangeName: draft.exchangeName.trim(),
    countryCode: draft.countryCode.trim(),
    currency: draft.currency.trim(),
    enabled: draft.enabled === 'true',
    tickSize: parseNumber(draft.tickSize, 'Tick size'),
    sizeIncrement: parseNumber(draft.sizeIncrement, 'Size increment'),
    referencePrice: parseNumber(draft.referencePrice, 'Reference price'),
    previousClose: parseNumber(draft.previousClose, 'Previous close'),
  }
}

function parseImportListings(value: string): AdminInstrumentListingImportRow[] {
  const parsed = JSON.parse(value) as unknown
  const rows = Array.isArray(parsed)
    ? parsed
    : typeof parsed === 'object' && parsed !== null && Array.isArray((parsed as { listings?: unknown }).listings)
      ? (parsed as { listings: unknown[] }).listings
      : null
  if (!rows) throw new Error('Import JSON must be an array or an object with a listings array')
  return rows as AdminInstrumentListingImportRow[]
}

export function AdminStaticDataPage() {
  const { user } = useAuth()
  const token = user?.access_token ?? ''
  const [filters, setFilters] = useState<StaticDataFilterDraft>(EMPTY_FILTERS)
  const [appliedFilters, setAppliedFilters] = useState<StaticDataFilterDraft>(EMPTY_FILTERS)
  const [listings, setListings] = useState<AdminInstrumentListing[]>([])
  const [pageInfo, setPageInfo] = useState(emptyPageInfo)
  const [facets, setFacets] = useState<AdminStaticDataFacets | null>(null)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [editDraft, setEditDraft] = useState<StaticDataEditDraft | null>(null)
  const [importText, setImportText] = useState(EMPTY_IMPORT)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [importing, setImporting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const selectedListing = useMemo(
    () => listings.find((listing) => listing.id === selectedId) ?? null,
    [listings, selectedId],
  )
  const loadedEnabled = listings.filter((listing) => listing.enabled).length
  const visibleExchanges = useMemo(
    () => new Set(listings.map((listing) => listing.exchangeMic)).size,
    [listings],
  )
  const sameInstrumentListings = useMemo(
    () => selectedListing
      ? listings.filter((listing) => listing.symbol === selectedListing.symbol).length
      : 0,
    [listings, selectedListing],
  )

  useEffect(() => {
    setEditDraft(selectedListing ? listingToDraft(selectedListing) : null)
  }, [selectedListing])

  const loadStaticData = useCallback(async (draft: StaticDataFilterDraft, page: number, size: number) => {
    if (!token) return
    setLoading(true)
    setError(null)
    setNotice(null)
    try {
      const [loadedPage, loadedFacets] = await Promise.all([
        adminStaticDataApi.listings(token, filtersFromDraft(draft, page, size)),
        adminStaticDataApi.facets(token),
      ])
      setListings(loadedPage.items)
      setPageInfo({
        page: loadedPage.page,
        size: loadedPage.size,
        totalElements: loadedPage.totalElements,
        totalPages: loadedPage.totalPages,
        first: loadedPage.first,
        last: loadedPage.last,
      })
      setFacets(loadedFacets)
      setSelectedId((current) => {
        if (current && loadedPage.items.some((listing) => listing.id === current)) return current
        return loadedPage.items[0]?.id ?? null
      })
    } catch (loadError: unknown) {
      setError(loadError instanceof Error ? loadError.message : 'Static data could not be loaded')
    } finally {
      setLoading(false)
    }
  }, [token])

  useEffect(() => {
    if (!token) return
    void loadStaticData(EMPTY_FILTERS, 0, DEFAULT_PAGE_SIZE)
  }, [loadStaticData, token])

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setAppliedFilters(filters)
    void loadStaticData(filters, 0, pageInfo.size)
  }

  const clearFilters = () => {
    setFilters(EMPTY_FILTERS)
    setAppliedFilters(EMPTY_FILTERS)
    void loadStaticData(EMPTY_FILTERS, 0, pageInfo.size)
  }

  const movePage = (nextPage: number) => {
    void loadStaticData(appliedFilters, nextPage, pageInfo.size)
  }

  const changePageSize = (nextSize: number) => {
    void loadStaticData(appliedFilters, 0, nextSize)
  }

  const updateDraft = <Field extends keyof StaticDataEditDraft>(
    field: Field,
    value: StaticDataEditDraft[Field],
  ) => {
    setEditDraft((current) => (current ? { ...current, [field]: value } : current))
  }

  const saveListing = async (event: FormEvent) => {
    event.preventDefault()
    if (!token || !selectedListing || !editDraft) return
    setSaving(true)
    setError(null)
    setNotice(null)
    try {
      const updated = await adminStaticDataApi.update(
        token,
        selectedListing.id,
        payloadFromDraft(editDraft),
      )
      setListings((current) => current.map((listing) => (listing.id === updated.id ? updated : listing)))
      setSelectedId(updated.id)
      setNotice(`${updated.symbol} saved`)
    } catch (saveError: unknown) {
      setError(saveError instanceof Error ? saveError.message : 'Static-data listing could not be saved')
    } finally {
      setSaving(false)
    }
  }

  const importListings = async (event: FormEvent) => {
    event.preventDefault()
    if (!token) return
    setImporting(true)
    setError(null)
    setNotice(null)
    try {
      const result = await adminStaticDataApi.importListings(token, {
        listings: parseImportListings(importText),
      })
      await loadStaticData(appliedFilters, pageInfo.page, pageInfo.size)
      setSelectedId(result.listingIds[0] ?? selectedId)
      setNotice(`Imported ${result.requested} listings (${result.created} new, ${result.updated} updated)`)
    } catch (importError: unknown) {
      setError(importError instanceof Error ? importError.message : 'Static-data import could not be applied')
    } finally {
      setImporting(false)
    }
  }

  const currentPageRange = pageRange({ items: listings, ...pageInfo })
  const displayTotalPages = Math.max(1, pageInfo.totalPages)

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
        <section className="admin-metrics" aria-label="Static-data metrics">
          <div><span>Listings</span><strong>{facets?.totalListings ?? listings.length}</strong></div>
          <div><span>Enabled</span><strong>{facets?.enabledListings ?? loadedEnabled}</strong></div>
          <div><span>Disabled</span><strong>{facets?.disabledListings ?? listings.length - loadedEnabled}</strong></div>
          <div><span>Exchanges</span><strong>{facets?.exchanges.length ?? visibleExchanges}</strong></div>
        </section>

        <section className="admin-read-panel">
          <div className="admin-panel-heading">
            <div><span>Reference data</span><h1>Static Data</h1></div>
            <form className="admin-filter-bar admin-filter-bar--wide" onSubmit={submit}>
              <label>Search<input placeholder="Symbol or name" value={filters.query} onChange={(event) => setFilters((current) => ({ ...current, query: event.target.value }))} /></label>
              <label>Exchange
                <select value={filters.exchangeMic} onChange={(event) => setFilters((current) => ({ ...current, exchangeMic: event.target.value }))}>
                  <option value="">All</option>
                  {facets?.exchanges.map((exchange) => <option value={exchange.mic} key={exchange.mic}>{exchange.mic}</option>)}
                </select>
              </label>
              <label>Currency
                <select value={filters.currency} onChange={(event) => setFilters((current) => ({ ...current, currency: event.target.value }))}>
                  <option value="">All</option>
                  {facets?.currencies.map((currency) => <option value={currency} key={currency}>{currency}</option>)}
                </select>
              </label>
              <label>Country
                <select value={filters.countryCode} onChange={(event) => setFilters((current) => ({ ...current, countryCode: event.target.value }))}>
                  <option value="">All</option>
                  {facets?.countries.map((country) => <option value={country} key={country}>{country}</option>)}
                </select>
              </label>
              <label>Status
                <select value={filters.enabled} onChange={(event) => setFilters((current) => ({ ...current, enabled: event.target.value as StaticDataFilterDraft['enabled'] }))}>
                  <option value="">All</option>
                  <option value="true">Enabled</option>
                  <option value="false">Disabled</option>
                </select>
              </label>
              <button type="submit" disabled={loading}>{loading ? 'Loading...' : 'Refresh'}</button>
              <button type="button" onClick={clearFilters} disabled={loading}>Clear</button>
            </form>
          </div>

          <div className="admin-state-strip" aria-label="Static-data visible state">
            <div><span>Matching</span><strong>{compactNumber(pageInfo.totalElements)}</strong></div>
            <div><span>Loaded</span><strong>{listings.length}</strong></div>
            <div><span>Loaded enabled</span><strong>{loadedEnabled}</strong></div>
            <div><span>Selected</span><strong>{selectedListing ? `${selectedListing.symbol} / ${selectedListing.exchangeMic}` : '-'}</strong></div>
          </div>

          <form className="admin-write-panel admin-import-panel" onSubmit={importListings}>
            <div className="admin-panel-heading admin-panel-heading--compact">
              <div><span>Controlled write</span><h2>Import Listings</h2></div>
              <button type="submit" className="admin-primary-action" disabled={importing || loading}>
                {importing ? 'Importing...' : 'Import'}
              </button>
            </div>
            <textarea
              aria-label="Static-data import JSON"
              spellCheck={false}
              value={importText}
              onChange={(event) => setImportText(event.target.value)}
            />
          </form>

          <div className="admin-reference-grid">
            <section>
              <div className="admin-panel-heading admin-panel-heading--compact">
                <div><span>Listings</span><h2>Instrument Listings</h2></div>
                <small>{loading ? 'Refreshing' : currentPageRange}</small>
              </div>
              <div className="admin-table-wrap">
                <table className="admin-data-table admin-static-data-table">
                  <thead>
                    <tr><th>Instrument</th><th>Exchange</th><th>Currency</th><th>Tick</th><th>Reference</th><th>Status</th></tr>
                  </thead>
                  <tbody>
                    {loading && listings.length === 0 ? (
                      <tr><td colSpan={6} className="admin-empty-row">Loading static data...</td></tr>
                    ) : listings.length === 0 ? (
                      <tr><td colSpan={6} className="admin-empty-row">No listings found.</td></tr>
                    ) : listings.map((listing) => (
                      <tr
                        key={listing.id}
                        className={listing.id === selectedId ? 'selected' : undefined}
                        onClick={() => setSelectedId(listing.id)}
                      >
                        <td><strong>{listing.symbol}</strong><small>{listing.name}</small></td>
                        <td><strong>{listing.exchangeMic}</strong><small>{listing.exchangeName}</small></td>
                        <td>{listing.currency}<small>{listing.countryCode}</small></td>
                        <td>{compactNumber(listing.tickSize)}<small>Size {compactNumber(listing.sizeIncrement)}</small></td>
                        <td>{compactNumber(listing.referencePrice)}<small>Prev {compactNumber(listing.previousClose)}</small></td>
                        <td><span className={listing.enabled ? 'admin-status admin-status--enabled' : 'admin-status'}><i />{statusLabel(listing.enabled)}</span></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="admin-pagination" aria-label="Static-data pagination">
                <span>Page {pageInfo.totalPages === 0 ? 0 : pageInfo.page + 1} of {displayTotalPages}</span>
                <label>Rows
                  <select value={pageInfo.size} onChange={(event) => changePageSize(Number(event.target.value))} disabled={loading}>
                    {PAGE_SIZE_OPTIONS.map((size) => <option value={size} key={size}>{size}</option>)}
                  </select>
                </label>
                <button
                  type="button"
                  aria-label="Previous listings page"
                  disabled={loading || pageInfo.first || pageInfo.totalElements === 0}
                  onClick={() => movePage(pageInfo.page - 1)}
                >
                  Previous
                </button>
                <button
                  type="button"
                  aria-label="Next listings page"
                  disabled={loading || pageInfo.last || pageInfo.totalElements === 0}
                  onClick={() => movePage(pageInfo.page + 1)}
                >
                  Next
                </button>
              </div>
            </section>

            <aside className="admin-reference-detail">
              <div className="admin-panel-heading admin-panel-heading--compact">
                <div><span>Listing detail</span><h2>{selectedListing?.symbol ?? 'No listing'}</h2></div>
              </div>
              {selectedListing ? (
                <>
                  <dl className="admin-detail-list">
                    <div><dt>Listing ID</dt><dd>{selectedListing.id}</dd></div>
                    <div><dt>Version</dt><dd>{selectedListing.version}</dd></div>
                    <div><dt>Market symbol</dt><dd>{selectedListing.marketSymbol}</dd></div>
                    <div><dt>Exchange</dt><dd>{selectedListing.exchangeMic} · {selectedListing.exchangeName}</dd></div>
                    <div><dt>Country</dt><dd>{selectedListing.countryCode}</dd></div>
                    <div><dt>Currency</dt><dd>{selectedListing.currency}</dd></div>
                    <div><dt>Tick size</dt><dd>{compactNumber(selectedListing.tickSize)}</dd></div>
                    <div><dt>Size increment</dt><dd>{compactNumber(selectedListing.sizeIncrement)}</dd></div>
                    <div><dt>Reference price</dt><dd>{compactNumber(selectedListing.referencePrice)}</dd></div>
                    <div><dt>Previous close</dt><dd>{compactNumber(selectedListing.previousClose)}</dd></div>
                    <div><dt>Same-symbol rows</dt><dd>{sameInstrumentListings}</dd></div>
                    <div><dt>Status</dt><dd>{statusLabel(selectedListing.enabled)}</dd></div>
                  </dl>
                  {editDraft && (
                    <form className="admin-inline-form" onSubmit={saveListing}>
                      <label>Symbol<input value={editDraft.symbol} onChange={(event) => updateDraft('symbol', event.target.value)} /></label>
                      <label>Name<input value={editDraft.name} onChange={(event) => updateDraft('name', event.target.value)} /></label>
                      <label>Market symbol<input value={editDraft.marketSymbol} onChange={(event) => updateDraft('marketSymbol', event.target.value)} /></label>
                      <label>Exchange MIC<input value={editDraft.exchangeMic} onChange={(event) => updateDraft('exchangeMic', event.target.value)} /></label>
                      <label>Exchange name<input value={editDraft.exchangeName} onChange={(event) => updateDraft('exchangeName', event.target.value)} /></label>
                      <label>Country<input value={editDraft.countryCode} onChange={(event) => updateDraft('countryCode', event.target.value)} /></label>
                      <label>Currency<input value={editDraft.currency} onChange={(event) => updateDraft('currency', event.target.value)} /></label>
                      <label>Status
                        <select value={editDraft.enabled} onChange={(event) => updateDraft('enabled', event.target.value as StaticDataEditDraft['enabled'])}>
                          <option value="true">Enabled</option>
                          <option value="false">Disabled</option>
                        </select>
                      </label>
                      <label>Tick size<input inputMode="decimal" value={editDraft.tickSize} onChange={(event) => updateDraft('tickSize', event.target.value)} /></label>
                      <label>Size increment<input inputMode="decimal" value={editDraft.sizeIncrement} onChange={(event) => updateDraft('sizeIncrement', event.target.value)} /></label>
                      <label>Reference price<input inputMode="decimal" value={editDraft.referencePrice} onChange={(event) => updateDraft('referencePrice', event.target.value)} /></label>
                      <label>Previous close<input inputMode="decimal" value={editDraft.previousClose} onChange={(event) => updateDraft('previousClose', event.target.value)} /></label>
                      <div className="admin-form-actions">
                        <button type="submit" className="admin-primary-action" disabled={saving}>
                          {saving ? 'Saving...' : 'Save listing'}
                        </button>
                      </div>
                    </form>
                  )}
                </>
              ) : (
                <p className="admin-detail-empty">Select a listing to inspect its reference-data fields.</p>
              )}
            </aside>
          </div>
        </section>
      </main>
    </>
  )
}
