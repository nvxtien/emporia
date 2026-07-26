import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import type { User } from 'oidc-client-ts'
import { useAuth } from '../auth/useAuth'
import { tradingApi } from '../trading/api'
import type {
  Listing,
  Execution,
  OrderEvent,
  OrderSide,
  OrderType,
  Quote,
  TradingOrder,
  WatchlistItem,
  WorkspaceLayout,
} from '../trading/types'
import '../trading/workspace.css'

function claim(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

function displayName(user: User): string {
  return claim(user.profile.name) ?? claim(user.profile.preferred_username) ?? user.profile.sub
}

function initials(name: string): string {
  return name.split(/\s+/).map((part) => part[0]).join('').slice(0, 2).toUpperCase()
}

function money(value: number | null | undefined, currency = 'USD'): string {
  if (value === null || value === undefined) return 'Market'
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

function compactNumber(value: number): string {
  return new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(value)
}

function time(value: string): string {
  return new Intl.DateTimeFormat('en-US', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value))
}

function dateTime(value: string): string {
  return new Intl.DateTimeFormat('en-US', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value))
}

function shortId(value: string): string {
  return value.slice(0, 8).toUpperCase()
}

const PANEL_LABELS: Record<string, string> = {
  watchlist: 'Watchlist',
  'market-depth': 'Market depth',
  'order-ticket': 'Order ticket',
  'parent-orders': 'Parent orders',
  'child-orders': 'Child orders',
}

const DEFAULT_LAYOUT: WorkspaceLayout = {
  version: 1,
  panels: ['watchlist', 'market-depth', 'order-ticket', 'parent-orders', 'child-orders'],
  columns: { owner: true, filled: true, price: true, destination: true },
}

function parseLayout(value: string): WorkspaceLayout {
  try {
    const parsed = JSON.parse(value) as Partial<WorkspaceLayout>
    const panels = Array.isArray(parsed.panels)
      ? parsed.panels.filter((panel): panel is string => typeof panel === 'string' && panel in PANEL_LABELS)
      : DEFAULT_LAYOUT.panels
    return {
      version: 1,
      panels: [...new Set(panels)],
      columns: { ...DEFAULT_LAYOUT.columns, ...(parsed.columns ?? {}) },
    }
  } catch {
    return DEFAULT_LAYOUT
  }
}

function BrandMark() {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true">
      <path d="M7 23V17M13 23V11M19 23V14M25 23V7" />
      <path d="m6 12 7-5 6 3 7-6" />
    </svg>
  )
}

function SearchIcon() {
  return <svg viewBox="0 0 20 20" aria-hidden="true"><circle cx="8.5" cy="8.5" r="5.5" /><path d="m13 13 4 4" /></svg>
}

function EmptyWorkspace({ login, busy }: { login: () => Promise<void>; busy: boolean }) {
  return (
    <main className="workspace-access">
      <div className="workspace-access__card">
        <span className="workspace-brand-mark"><BrandMark /></span>
        <p className="eyebrow">Emporia trading desk</p>
        <h1>Sign in to open your workspace.</h1>
        <p>Your watchlist and orders are protected by your short-lived access token.</p>
        <button className="button button--primary" type="button" disabled={busy} onClick={() => void login()}>
          {busy ? 'Checking session…' : 'Sign in securely'}
        </button>
        <Link to="/">Return to the Emporia overview</Link>
      </div>
    </main>
  )
}

export function TradingWorkspacePage() {
  const { user, isAuthenticated, isLoading, login, logout } = useAuth()
  const token = user?.access_token ?? ''
  const [watchlist, setWatchlist] = useState<WatchlistItem[]>([])
  const [quotes, setQuotes] = useState<Record<number, Quote>>({})
  const [orders, setOrders] = useState<TradingOrder[]>([])
  const [selectedListing, setSelectedListing] = useState<Listing | null>(null)
  const [query, setQuery] = useState('')
  const [searchResults, setSearchResults] = useState<Listing[]>([])
  const [side, setSide] = useState<OrderSide>('BUY')
  const [orderType, setOrderType] = useState<OrderType>('LIMIT')
  const [quantity, setQuantity] = useState('10')
  const [price, setPrice] = useState('')
  const [destination, setDestination] = useState<'DMA' | 'SMART' | 'VWAP'>('DMA')
  const [durationMinutes, setDurationMinutes] = useState('30')
  const [participationRate, setParticipationRate] = useState('10')
  const [reviewing, setReviewing] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [selectedOrder, setSelectedOrder] = useState<TradingOrder | null>(null)
  const [history, setHistory] = useState<OrderEvent[]>([])
  const [executions, setExecutions] = useState<Execution[]>([])
  const [orderToModify, setOrderToModify] = useState<TradingOrder | null>(null)
  const [layout, setLayout] = useState<WorkspaceLayout>(DEFAULT_LAYOUT)
  const [layoutSaving, setLayoutSaving] = useState(false)
  const [loadingWorkspace, setLoadingWorkspace] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const selectedQuote = selectedListing ? quotes[selectedListing.id] : undefined
  const name = user ? displayName(user) : ''
  const desk = user ? claim(user.profile.desk) ?? user.profile.sub : ''
  const canTrade = user?.profile.can_trade === true
  const marketSource = Object.values(quotes).find((quote) => quote.source === 'ALPACA_IEX')
    ? 'Live IEX'
    : Object.values(quotes).find((quote) => quote.source === 'AGGREGATED')
      ? 'Aggregated market'
      : 'Simulated market'

  const refreshOrders = useCallback(async () => {
    if (!token) return
    setOrders(await tradingApi.orders(token))
  }, [token])

  useEffect(() => {
    if (!isAuthenticated || !token) {
      setLoadingWorkspace(false)
      return
    }
    let active = true
    setLoadingWorkspace(true)
    setError(null)
    void Promise.all([tradingApi.watchlist(token), tradingApi.orders(token), tradingApi.workspacePreference(token)])
      .then(([items, currentOrders, workspacePreference]) => {
        if (!active) return
        setWatchlist(items)
        setOrders(currentOrders)
        setLayout(parseLayout(workspacePreference.layoutJson))
        setSelectedListing((current) => current ?? items[0]?.listing ?? null)
      })
      .catch((loadError: unknown) => {
        if (active) setError(loadError instanceof Error ? loadError.message : 'Could not load the trading workspace')
      })
      .finally(() => {
        if (active) setLoadingWorkspace(false)
      })
    return () => { active = false }
  }, [isAuthenticated, token])

  useEffect(() => {
    if (!token) return
    let active = true
    const streamController = new AbortController()
    const connect = async () => {
      while (active) {
        try {
          await tradingApi.streamOrders(token, (order) => {
            if (!active) return
            setOrders((current) => {
              const next = current.some((candidate) => candidate.id === order.id)
                ? current.map((candidate) => candidate.id === order.id ? order : candidate)
                : [order, ...current]
              return next.toSorted((left, right) => right.updatedAt.localeCompare(left.updatedAt))
            })
            setSelectedOrder((current) => current?.id === order.id ? order : current)
          }, streamController.signal)
        } catch (streamError: unknown) {
          if (!active || streamController.signal.aborted) return
          setError(streamError instanceof Error ? streamError.message : 'Order updates are unavailable')
        }
        if (active) await new Promise((resolve) => window.setTimeout(resolve, 1000))
      }
    }
    void connect()
    return () => {
      active = false
      streamController.abort()
    }
  }, [token])

  const quoteListingIds = useMemo(() => {
    const ids = watchlist.map((item) => item.listing.id)
    if (selectedListing && !ids.includes(selectedListing.id)) ids.push(selectedListing.id)
    return ids
  }, [selectedListing, watchlist])
  useEffect(() => {
    if (!token || quoteListingIds.length === 0) return
    let active = true
    const streamController = new AbortController()
    const connect = async () => {
      while (active) {
        try {
          await tradingApi.streamQuotes(token, quoteListingIds, (quote) => {
            if (!active) return
            setQuotes((current) => ({ ...current, [quote.listingId]: quote }))
          }, streamController.signal)
        } catch (quoteError: unknown) {
          if (!active || streamController.signal.aborted) return
          setError(quoteError instanceof Error ? quoteError.message : 'Market data is unavailable')
        }
        if (active) {
          await new Promise((resolve) => window.setTimeout(resolve, 1000))
        }
      }
    }
    void connect()
    return () => {
      active = false
      streamController.abort()
    }
  }, [quoteListingIds, token])

  useEffect(() => {
    if (!selectedQuote || price !== '' || orderType !== 'LIMIT') return
    const referencePrice = (side === 'BUY' ? selectedQuote.offers[0]?.price : selectedQuote.bids[0]?.price)
      ?? selectedQuote.lastPrice
    if (referencePrice) setPrice(referencePrice.toFixed(2))
  }, [orderType, price, selectedQuote, side])

  useEffect(() => {
    if (!token) return
    const trimmedQuery = query.trim()
    if (!trimmedQuery) {
      setSearchResults([])
      return
    }
    let active = true
    const timeout = window.setTimeout(() => {
      void tradingApi.instruments(token, trimmedQuery)
        .then((results) => { if (active) setSearchResults(results) })
        .catch((searchError: unknown) => {
          if (active) setError(searchError instanceof Error ? searchError.message : 'Instrument search failed')
        })
    }, 220)
    return () => {
      active = false
      window.clearTimeout(timeout)
    }
  }, [query, token])

  const chooseListing = useCallback((listing: Listing) => {
    setSelectedListing(listing)
    setPrice('')
    setReviewing(false)
  }, [])

  const addListing = async (listing: Listing) => {
    try {
      const item = await tradingApi.addToWatchlist(token, listing.id)
      setWatchlist((current) => current.some((entry) => entry.listing.id === listing.id) ? current : [...current, item])
      chooseListing(listing)
      setQuery('')
      setSearchResults([])
    } catch (addError: unknown) {
      setError(addError instanceof Error ? addError.message : 'Could not add the instrument')
    }
  }

  const removeListing = async (listingId: number) => {
    try {
      await tradingApi.removeFromWatchlist(token, listingId)
      setWatchlist((current) => {
        const next = current.filter((item) => item.listing.id !== listingId)
        if (selectedListing?.id === listingId) chooseListing(next[0]?.listing ?? selectedListing)
        return next
      })
    } catch (removeError: unknown) {
      setError(removeError instanceof Error ? removeError.message : 'Could not remove the instrument')
    }
  }

  const showReview = (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    const numericQuantity = Number(quantity)
    const numericPrice = Number(price)
    if (!selectedListing) return setError('Select an instrument before creating an order')
    if (!Number.isFinite(numericQuantity) || numericQuantity <= 0) return setError('Enter a valid order quantity')
    if (orderType === 'LIMIT' && (!Number.isFinite(numericPrice) || numericPrice <= 0)) return setError('Enter a valid limit price')
    setReviewing(true)
  }

  const submitOrder = async () => {
    if (!selectedListing) return
    setSubmitting(true)
    setError(null)
    try {
      if (orderToModify) {
        const modified = await tradingApi.modifyOrder(
          token,
          orderToModify.id,
          orderToModify.version,
          Number(quantity),
          orderType === 'LIMIT' ? Number(price) : null,
        )
        setOrders((current) => current.map((entry) => entry.id === modified.id ? modified : entry))
        setOrderToModify(null)
        setReviewing(false)
        setNotice(`Order ${shortId(modified.id)} modified`)
        return
      }
      const vwapStartTime = Math.floor(Date.now() / 1000)
      const vwapDuration = Math.max(1, Number(durationMinutes))
      const vwapParticipation = Math.min(100, Math.max(1, Number(participationRate)))
      const quantityUnits = Math.max(
        1,
        Math.floor(Number(quantity) / Math.max(selectedListing.sizeIncrement, Number.EPSILON)),
      )
      const created = await tradingApi.createOrder(token, {
        listingId: selectedListing.id,
        side,
        type: orderType,
        quantity: Number(quantity),
        limitPrice: orderType === 'LIMIT' ? Number(price) : null,
        destination,
        executionParameters: destination === 'VWAP'
          ? {
              utcStartTimeSecs: vwapStartTime,
              utcEndTimeSecs: vwapStartTime + vwapDuration * 60,
              buckets: Math.min(quantityUnits, Math.max(1, Math.ceil(100 / vwapParticipation))),
            }
          : {},
      })
      setOrders((current) => [created, ...current])
      setReviewing(false)
      setNotice(`${side === 'BUY' ? 'Buy' : 'Sell'} order ${shortId(created.id)} accepted`)
      window.setTimeout(() => setNotice(null), 5000)
    } catch (submitError: unknown) {
      setError(submitError instanceof Error ? submitError.message : 'The order could not be created')
    } finally {
      setSubmitting(false)
    }
  }

  const cancelOrder = async (order: TradingOrder) => {
    try {
      const cancelled = await tradingApi.cancelOrder(token, order.id)
      setOrders((current) => current.map((entry) => entry.id === order.id ? cancelled : entry))
      setNotice(`Order ${shortId(order.id)} cancelled`)
    } catch (cancelError: unknown) {
      setError(cancelError instanceof Error ? cancelError.message : 'The order could not be cancelled')
    }
  }

  const cancelAll = async () => {
    try {
      const response = await tradingApi.cancelAll(token)
      await refreshOrders()
      setNotice(`${response.cancelled} active order${response.cancelled === 1 ? '' : 's'} cancelled`)
    } catch (cancelError: unknown) {
      setError(cancelError instanceof Error ? cancelError.message : 'Active orders could not be cancelled')
    }
  }

  const openOrder = async (order: TradingOrder) => {
    setSelectedOrder(order)
    setHistory([])
    setExecutions([])
    try {
      const [orderHistory, orderExecutions] = await Promise.all([
        tradingApi.orderHistory(token, order.id),
        tradingApi.executions(token, order.id),
      ])
      setHistory(orderHistory)
      setExecutions(orderExecutions)
    } catch (historyError: unknown) {
      setError(historyError instanceof Error ? historyError.message : 'Order history could not be loaded')
    }
  }

  const startModify = (order: TradingOrder) => {
    setOrderToModify(order)
    setSelectedListing(order.listing)
    setSide(order.side)
    setOrderType(order.type)
    setQuantity(String(order.quantity))
    setPrice(order.limitPrice === null ? '' : String(order.limitPrice))
    setDestination(order.destination)
    setReviewing(false)
    document.getElementById('order-ticket')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const cancelModify = () => {
    setOrderToModify(null)
    setQuantity('10')
    setPrice('')
  }

  const togglePanel = (panel: string) => {
    setLayout((current) => ({
      ...current,
      panels: current.panels.includes(panel)
        ? current.panels.filter((candidate) => candidate !== panel)
        : [...current.panels, panel],
    }))
  }

  const movePanel = (panel: string, direction: -1 | 1) => {
    setLayout((current) => {
      const index = current.panels.indexOf(panel)
      const target = index + direction
      if (index < 0 || target < 0 || target >= current.panels.length) return current
      const panels = [...current.panels]
      ;[panels[index], panels[target]] = [panels[target], panels[index]]
      return { ...current, panels }
    })
  }

  const saveLayout = async () => {
    setLayoutSaving(true)
    try {
      await tradingApi.saveWorkspacePreference(token, layout)
      setNotice('Workspace layout saved')
    } catch (saveError: unknown) {
      setError(saveError instanceof Error ? saveError.message : 'Workspace layout could not be saved')
    } finally {
      setLayoutSaving(false)
    }
  }

  if (isLoading || loadingWorkspace) {
    return <main className="workspace-loading"><span className="workspace-loader" /><p>Preparing your trading desk…</p></main>
  }

  if (!isAuthenticated || !user) return <EmptyWorkspace login={login} busy={isLoading} />

  const estimatePrice = orderType === 'LIMIT' ? Number(price) : selectedQuote?.lastPrice ?? 0
  const estimatedNotional = Number(quantity) * estimatePrice
  const hasLiveOrders = orders.some((order) => order.status === 'LIVE' || order.status === 'PARTIALLY_FILLED')
  const parentOrders = orders.filter((order) => order.parentOrderId === null)
  const childOrders = orders.filter((order) => order.parentOrderId !== null)
  const panelVisible = (panel: string) => layout.panels.includes(panel)
  const panelOrder = (panel: string) => Math.max(0, layout.panels.indexOf(panel))
  const bestBid = selectedQuote?.bids[0]
  const bestOffer = selectedQuote?.offers[0]
  const spread = bestBid && bestOffer ? bestOffer.price - bestBid.price : null
  const depthLevelCount = selectedQuote
    ? Math.max(selectedQuote.bids.length, selectedQuote.offers.length)
    : 0
  const maximumDepthSize = selectedQuote
    ? Math.max(1, ...selectedQuote.bids.map((line) => line.size), ...selectedQuote.offers.map((line) => line.size))
    : 1

  return (
    <div className="trading-workspace">
      <header className="workspace-header">
        <Link className="workspace-brand" to="/">
          <span className="workspace-brand-mark"><BrandMark /></span>
          <strong>Emporia</strong><small>Trade</small>
        </Link>
        <nav className="workspace-nav" aria-label="Trading workspace">
          <span className="active">Trading desk</span><span>Portfolio</span><span>Analytics</span>
        </nav>
        <div className="workspace-account">
          <span className="simulation-badge"><i /> {marketSource}</span>
          <span className="workspace-avatar">{initials(name)}</span>
          <div><strong>{name} · {desk}</strong><button type="button" onClick={() => void logout()}>Sign out</button></div>
        </div>
      </header>

      {(error || notice) && (
        <div className={error ? 'workspace-toast workspace-toast--error' : 'workspace-toast'} role="status">
          <span>{error ?? notice}</span>
          <button type="button" onClick={() => { setError(null); setNotice(null) }}>×</button>
        </div>
      )}

      <div className="workspace-tools">
        <strong>Workspace</strong>
        {Object.entries(PANEL_LABELS).map(([panel, label]) => (
          <span className="workspace-tool" key={panel}>
            <label><input type="checkbox" checked={panelVisible(panel)} onChange={() => togglePanel(panel)} />{label}</label>
            {panelVisible(panel) && <button type="button" aria-label={`Move ${label} left`} onClick={() => movePanel(panel, -1)}>←</button>}
            {panelVisible(panel) && <button type="button" aria-label={`Move ${label} right`} onClick={() => movePanel(panel, 1)}>→</button>}
          </span>
        ))}
        <details>
          <summary>Columns</summary>
          <div>{Object.keys(DEFAULT_LAYOUT.columns).map((column) => (
            <label key={column}><input type="checkbox" checked={layout.columns[column] !== false} onChange={() => setLayout((current) => ({
              ...current,
              columns: { ...current.columns, [column]: current.columns[column] === false },
            }))} />{column}</label>
          ))}</div>
        </details>
        <button type="button" disabled={layoutSaving} onClick={() => void saveLayout()}>{layoutSaving ? 'Saving…' : 'Save layout'}</button>
      </div>

      <main className="desk-grid">
        <section className="desk-panel watch-panel" hidden={!panelVisible('watchlist')} style={{ order: panelOrder('watchlist') }}>
          <div className="panel-heading">
            <div><span>Market</span><h1>Watchlist</h1></div>
            <small>{watchlist.length} symbols</small>
          </div>
          <div className="instrument-search">
            <SearchIcon />
            <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search symbol or company" aria-label="Search instruments" />
            {searchResults.length > 0 && (
              <div className="search-results">
                {searchResults.map((listing) => (
                  <button type="button" key={listing.id} onClick={() => void addListing(listing)}>
                    <span><strong>{listing.symbol}</strong><small>{listing.name}</small></span>
                    <em>{listing.exchangeMic}</em>
                  </button>
                ))}
              </div>
            )}
          </div>
          <div className="watch-columns"><span>Symbol</span><span>Bid</span><span>Ask</span><span>Last</span></div>
          <div className="watch-rows">
            {watchlist.map((item) => {
              const quote = quotes[item.listing.id]
              const positive = (quote?.changePercent ?? 0) >= 0
              return (
                <button
                  type="button"
                  className={selectedListing?.id === item.listing.id ? 'watch-row active' : 'watch-row'}
                  key={item.id}
                  onClick={() => chooseListing(item.listing)}
                >
                  <span className="watch-symbol"><strong>{item.listing.symbol}</strong><small>{item.listing.exchangeMic}</small></span>
                  <span>{quote?.bids[0]?.price.toFixed(2) ?? '—'}</span>
                  <span>{quote?.offers[0]?.price.toFixed(2) ?? '—'}</span>
                  <span className={positive ? 'quote-up' : 'quote-down'}>
                    <strong>{quote ? quote.lastPrice.toFixed(2) : '—'}</strong>
                    <small>{quote ? `${positive ? '+' : ''}${quote.changePercent.toFixed(2)}%` : ''}</small>
                  </span>
                  <span
                    className="watch-remove"
                    role="button"
                    tabIndex={0}
                    aria-label={`Remove ${item.listing.symbol}`}
                    onClick={(event) => { event.stopPropagation(); void removeListing(item.listing.id) }}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') { event.stopPropagation(); void removeListing(item.listing.id) }
                    }}
                  >×</span>
                </button>
              )
            })}
          </div>
          <p className="panel-footnote">Search and add another US equity. Quotes update continuously.</p>
        </section>

        <section className="desk-panel depth-panel" hidden={!panelVisible('market-depth')} style={{ order: panelOrder('market-depth') }}>
          {selectedListing && selectedQuote ? (
            <>
              <div className="security-heading">
                <div>
                  <span>{selectedListing.exchangeName} · {selectedListing.currency}</span>
                  <h2>{selectedListing.symbol}</h2>
                  <p>{selectedListing.name}</p>
                </div>
                <div className="security-price">
                  <strong>{money(selectedQuote.lastPrice, selectedListing.currency)}</strong>
                  <span className={selectedQuote.change >= 0 ? 'quote-up' : 'quote-down'}>
                    {selectedQuote.change >= 0 ? '+' : ''}{selectedQuote.change.toFixed(2)} · {selectedQuote.changePercent.toFixed(2)}%
                  </span>
                </div>
              </div>
              <div className="quote-metrics">
                <div><span>Previous close</span><strong>{selectedQuote.previousClose.toFixed(2)}</strong></div>
                <div><span>Last size</span><strong>{compactNumber(selectedQuote.lastQuantity)}</strong></div>
                <div><span>Volume</span><strong>{compactNumber(selectedQuote.tradedVolume)}</strong></div>
                <div><span>Updated</span><strong>{time(selectedQuote.asOf)}</strong></div>
              </div>
              <div className="depth-heading"><div><span>Bid size</span><span>Bid</span></div><strong>Market depth</strong><div><span>Ask</span><span>Ask size</span></div></div>
              <div className="depth-book">
                {Array.from({ length: depthLevelCount }, (_, index) => {
                  const bid = selectedQuote.bids[index]
                  const offer = selectedQuote.offers[index]
                  return (
                    <div className="depth-line" key={`${bid?.price ?? 'none'}-${offer?.price ?? 'none'}-${index}`}>
                      <div className="depth-side depth-side--bid">
                        <i style={{ width: `${((bid?.size ?? 0) / maximumDepthSize) * 100}%` }} />
                        <span>{bid ? compactNumber(bid.size) : '—'}</span>
                        <strong>{bid?.price.toFixed(2) ?? '—'}</strong>
                      </div>
                      <span className="depth-level">{index + 1}</span>
                      <div className="depth-side depth-side--ask">
                        <i style={{ width: `${((offer?.size ?? 0) / maximumDepthSize) * 100}%` }} />
                        <strong>{offer?.price.toFixed(2) ?? '—'}</strong>
                        <span>{offer ? compactNumber(offer.size) : '—'}</span>
                      </div>
                    </div>
                  )
                })}
              </div>
              <div className="spread-line">
                <span>Spread</span>
                <strong>{spread === null ? 'N/A' : spread.toFixed(2)}</strong>
                <em>{selectedQuote.source === 'ALPACA_IEX' ? 'IEX' : selectedListing.exchangeMic}</em>
              </div>
              <p className={selectedQuote.streamInterrupted ? 'simulation-note simulation-note--interrupted' : 'simulation-note'}>
                <i />
                {selectedQuote.streamInterrupted
                  ? selectedQuote.streamStatusMessage
                  : selectedQuote.source === 'ALPACA_IEX'
                    ? 'Alpaca IEX top-of-book; updates are streamed continuously.'
                    : selectedQuote.source === 'AGGREGATED'
                      ? 'Aggregated depth across all available listings for this instrument.'
                      : 'Generated demonstration depth—not connected to an exchange.'}
              </p>
            </>
          ) : (
            <div className="panel-empty"><span>↗</span><h2>Select a symbol</h2><p>Choose an instrument from the watchlist to inspect its market depth.</p></div>
          )}
        </section>

        <section className="desk-panel ticket-panel" id="order-ticket" hidden={!panelVisible('order-ticket')} style={{ order: panelOrder('order-ticket') }}>
          <div className="panel-heading">
            <div><span>Execution</span><h2>{orderToModify ? `Modify ${shortId(orderToModify.id)}` : 'Order ticket'}</h2></div>
            <small>{selectedListing?.symbol ?? 'No symbol'}</small>
          </div>
          <form onSubmit={showReview}>
            <div className="side-switch" aria-label="Order side">
              <button type="button" disabled={Boolean(orderToModify)} className={side === 'BUY' ? 'active buy' : ''} onClick={() => { setSide('BUY'); setPrice('') }}>Buy</button>
              <button type="button" disabled={Boolean(orderToModify)} className={side === 'SELL' ? 'active sell' : ''} onClick={() => { setSide('SELL'); setPrice('') }}>Sell</button>
            </div>
            <label>Order type<select value={orderType} onChange={(event) => { setOrderType(event.target.value as OrderType); setPrice('') }}><option value="LIMIT">Limit</option><option value="MARKET">Market</option></select></label>
            <label>Quantity<div className="ticket-input"><input inputMode="decimal" value={quantity} onChange={(event) => setQuantity(event.target.value)} /><span>shares</span></div></label>
            <label>Limit price<div className="ticket-input"><span>{selectedListing?.currency === 'USD' ? '$' : selectedListing?.currency}</span><input inputMode="decimal" value={orderType === 'MARKET' ? '' : price} disabled={orderType === 'MARKET'} placeholder={orderType === 'MARKET' ? 'Market price' : '0.00'} onChange={(event) => setPrice(event.target.value)} /></div></label>
            <label>Destination<select value={destination} disabled={Boolean(orderToModify)} onChange={(event) => setDestination(event.target.value as typeof destination)}><option value="DMA">Direct market access</option><option value="SMART">Smart router</option><option value="VWAP">VWAP strategy</option></select></label>
            {destination === 'VWAP' && (
              <div className="strategy-fields">
                <label>Duration<input inputMode="numeric" value={durationMinutes} onChange={(event) => setDurationMinutes(event.target.value)} /><span>min</span></label>
                <label>Participation<input inputMode="numeric" value={participationRate} onChange={(event) => setParticipationRate(event.target.value)} /><span>%</span></label>
              </div>
            )}
            <div className="ticket-summary"><span>Estimated notional</span><strong>{money(Number.isFinite(estimatedNotional) ? estimatedNotional : 0)}</strong></div>
            <button className={side === 'BUY' ? 'ticket-submit ticket-submit--buy' : 'ticket-submit ticket-submit--sell'} type="submit" disabled={!selectedListing || !canTrade}>Review {orderToModify ? 'modification' : `${side.toLowerCase()} order`}</button>
            {orderToModify && <button className="review-back" type="button" onClick={cancelModify}>Cancel modification</button>}
            <p className="ticket-disclaimer">{canTrade ? 'Orders route through Emporia execution management.' : 'This account has view-only permission.'}</p>
          </form>
        </section>

        <section className="desk-panel blotter-panel" hidden={!panelVisible('parent-orders')} style={{ order: panelOrder('parent-orders') }}>
          <div className="blotter-heading">
            <div><span>Order management</span><h2>Parent order blotter</h2></div>
            <div><button type="button" onClick={() => void refreshOrders()}>Refresh</button><button className="danger-action" type="button" disabled={!hasLiveOrders || !canTrade} onClick={() => void cancelAll()}>Cancel all desk orders</button></div>
          </div>
          <div className="blotter-scroll">
            <table>
              <thead><tr><th>Order</th><th>Time</th>{layout.columns.owner !== false && <th>Owner</th>}<th>Symbol</th><th>Side</th><th>Qty</th>{layout.columns.filled !== false && <th>Filled</th>}{layout.columns.price !== false && <th>Price</th>}{layout.columns.destination !== false && <th>Destination</th>}<th>Status</th><th /></tr></thead>
              <tbody>
                {parentOrders.length === 0 ? (
                  <tr><td colSpan={10} className="empty-row">No orders yet. Build your first order in the ticket.</td></tr>
                ) : parentOrders.map((order) => {
                  const active = order.status === 'LIVE' || order.status === 'PARTIALLY_FILLED'
                  return (
                    <tr key={order.id} onClick={() => void openOrder(order)}>
                      <td className="mono">{shortId(order.id)}</td><td>{time(order.createdAt)}</td>{layout.columns.owner !== false && <td>{order.ownerSubject}</td>}<td><strong>{order.listing.symbol}</strong><small>{order.listing.exchangeMic}</small></td>
                      <td><span className={order.side === 'BUY' ? 'side-chip side-chip--buy' : 'side-chip side-chip--sell'}>{order.side}</span></td>
                      <td>{compactNumber(order.quantity)}</td>{layout.columns.filled !== false && <td>{compactNumber(order.tradedQuantity)}</td>}{layout.columns.price !== false && <td>{money(order.limitPrice, order.listing.currency)}</td>}{layout.columns.destination !== false && <td>{order.destination}</td>}
                      <td><span className={`status-chip status-chip--${order.status.toLowerCase().replace('_', '-')}`}><i />{order.status.replace('_', ' ')}</span></td>
                      <td>{active && canTrade && <><button className="row-cancel" type="button" onClick={(event) => { event.stopPropagation(); startModify(order) }}>Modify</button><button className="row-cancel" type="button" onClick={(event) => { event.stopPropagation(); void cancelOrder(order) }}>Cancel</button></>}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </section>

        <section className="desk-panel blotter-panel child-blotter" hidden={!panelVisible('child-orders')} style={{ order: panelOrder('child-orders') }}>
          <div className="blotter-heading"><div><span>Execution</span><h2>Child order blotter</h2></div><small>{childOrders.length} children</small></div>
          <div className="blotter-scroll">
            <table>
              <thead><tr><th>Child</th><th>Parent</th><th>Symbol</th><th>Venue</th><th>Qty</th><th>Filled</th><th>Average</th><th>Status</th></tr></thead>
              <tbody>{childOrders.length === 0
                ? <tr><td colSpan={8} className="empty-row">Strategy child orders will appear here.</td></tr>
                : childOrders.map((order) => <tr key={order.id} onClick={() => void openOrder(order)}>
                  <td className="mono">{shortId(order.id)}</td><td className="mono">{shortId(order.parentOrderId ?? '')}</td>
                  <td><strong>{order.listing.symbol}</strong></td><td>{order.listing.exchangeMic}</td>
                  <td>{compactNumber(order.quantity)}</td><td>{compactNumber(order.tradedQuantity)}</td>
                  <td>{money(order.averageTradePrice, order.listing.currency)}</td>
                  <td><span className={`status-chip status-chip--${order.status.toLowerCase().replace('_', '-')}`}><i />{order.status.replace('_', ' ')}</span></td>
                </tr>)}
              </tbody>
            </table>
          </div>
        </section>
      </main>

      {reviewing && selectedListing && (
        <div className="workspace-modal" role="dialog" aria-modal="true" aria-labelledby="review-title">
          <div className="review-card">
            <button className="modal-close" type="button" onClick={() => setReviewing(false)}>×</button>
            <span className={side === 'BUY' ? 'review-side review-side--buy' : 'review-side review-side--sell'}>{side}</span>
            <p className="eyebrow">{orderToModify ? 'Modification review' : 'Final order review'}</p>
            <h2 id="review-title">{quantity} shares of {selectedListing.symbol}</h2>
            <p>{selectedListing.name} · {selectedListing.exchangeMic}</p>
            <dl><div><dt>Order type</dt><dd>{orderType}</dd></div><div><dt>Price</dt><dd>{money(orderType === 'LIMIT' ? Number(price) : null)}</dd></div><div><dt>Destination</dt><dd>{destination}</dd></div><div><dt>Estimated notional</dt><dd>{money(estimatedNotional)}</dd></div></dl>
            <div className="review-warning"><strong>Execution route</strong><span>{destination === 'DMA' ? 'Direct venue execution' : destination === 'SMART' ? 'Best-venue smart routing' : 'Scheduled VWAP child orders'}</span></div>
            <button className={side === 'BUY' ? 'ticket-submit ticket-submit--buy' : 'ticket-submit ticket-submit--sell'} type="button" disabled={submitting} onClick={() => void submitOrder()}>{submitting ? 'Sending…' : orderToModify ? 'Confirm modification' : `Place ${side.toLowerCase()} order`}</button>
            <button className="review-back" type="button" onClick={() => setReviewing(false)}>Return to ticket</button>
          </div>
        </div>
      )}

      {selectedOrder && (
        <div className="workspace-modal" role="dialog" aria-modal="true" aria-labelledby="history-title">
          <div className="history-card">
            <button className="modal-close" type="button" onClick={() => setSelectedOrder(null)}>×</button>
            <p className="eyebrow">Order audit trail</p>
            <h2 id="history-title">{selectedOrder.listing.symbol} · {shortId(selectedOrder.id)}</h2>
            <div className="history-summary"><span className={selectedOrder.side === 'BUY' ? 'side-chip side-chip--buy' : 'side-chip side-chip--sell'}>{selectedOrder.side}</span><strong>{compactNumber(selectedOrder.quantity)} @ {money(selectedOrder.limitPrice)}</strong><span>{selectedOrder.destination}</span></div>
            <div className="history-list">
              {history.length === 0 ? <p>Loading event history…</p> : history.map((event) => (
                <article key={event.id}><i /><div><strong>{event.eventType.replace('_', ' ')}</strong><span>{event.message}</span></div><div><span>v{event.orderVersion}</span><time>{dateTime(event.occurredAt)}</time></div></article>
              ))}
            </div>
            <div className="execution-list">
              <h3>Executions</h3>
              {executions.length === 0 ? <p>No executions recorded.</p> : executions.map((execution) => (
                <article key={execution.id}>
                  <strong>{compactNumber(execution.quantity)} @ {money(execution.price, selectedOrder.listing.currency)}</strong>
                  <span>{execution.venue} · {execution.executionReference}</span>
                  <time>{dateTime(execution.executedAt)}</time>
                </article>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
