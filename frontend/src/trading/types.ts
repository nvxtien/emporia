export interface Listing {
  id: number
  version: number
  symbol: string
  name: string
  marketSymbol: string
  exchangeMic: string
  exchangeName: string
  countryCode: string
  currency: string
  tickSize: number
  sizeIncrement: number
}

export interface WatchlistItem {
  id: string
  displayOrder: number
  addedAt: string
  listing: Listing
}

export interface DepthLevel {
  price: number
  size: number
  exchangeMic: string
  entryId: string
  listingId: number
}

export interface Quote {
  listingId: number
  symbol: string
  currency: string
  lastPrice: number
  lastQuantity: number
  previousClose: number
  change: number
  changePercent: number
  tradedVolume: number
  bids: DepthLevel[]
  offers: DepthLevel[]
  asOf: string
  source: string
  streamInterrupted: boolean
  streamStatusMessage: string
}

export type OrderSide = 'BUY' | 'SELL'
export type OrderType = 'MARKET' | 'LIMIT'
export type OrderStatus = 'LIVE' | 'PARTIALLY_FILLED' | 'FILLED' | 'CANCELLED' | 'REJECTED'

export interface TradingOrder {
  id: string
  version: number
  ownerSubject: string
  deskId: string
  listing: Listing
  side: OrderSide
  type: OrderType
  quantity: number
  limitPrice: number | null
  remainingQuantity: number
  tradedQuantity: number
  averageTradePrice: number | null
  status: OrderStatus
  targetStatus: OrderStatus
  destination: 'DMA' | 'SMART' | 'VWAP'
  originatorReference: string
  parentOrderId: string | null
  rootOrderId: string
  executionParameters: string
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface Execution {
  id: string
  executionReference: string
  quantity: number
  price: number
  venue: string
  executedAt: string
}

export interface OrderEvent {
  id: string
  orderVersion: number
  eventType: string
  status: OrderStatus
  quantity: number
  price: number | null
  message: string | null
  occurredAt: string
}

export interface CreateOrder {
  listingId: number
  side: OrderSide
  type: OrderType
  quantity: number
  limitPrice: number | null
  destination: 'DMA' | 'SMART' | 'VWAP'
  originatorReference?: string
  executionParameters: Record<string, unknown>
}

export interface WorkspaceLayout {
  version: number
  panels: string[]
  columns: Record<string, boolean>
}

export interface WorkspacePreference {
  layoutJson: string
  updatedAt: string | null
}
