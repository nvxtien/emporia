import { expect, test, type Page, type Route } from '@playwright/test'

const ACCESS_TOKEN = 'playwright-access-token'
const AS_OF = '2026-07-23T14:30:00.200Z'

const aapl = {
  id: 1,
  version: 1,
  symbol: 'AAPL',
  name: 'Apple Inc.',
  marketSymbol: 'AAPL',
  exchangeMic: 'XNAS',
  exchangeName: 'Nasdaq',
  countryCode: 'US',
  currency: 'USD',
  tickSize: 0.01,
  sizeIncrement: 1,
}

const nvda = {
  ...aapl,
  id: 2,
  symbol: 'NVDA',
  name: 'NVIDIA Corporation',
  marketSymbol: 'NVDA',
}

const watchlist = [aapl, nvda].map((listing, index) => ({
  id: `watchlist-${listing.id}`,
  displayOrder: index,
  addedAt: AS_OF,
  listing,
}))

const twoSidedQuotes = [
  {
    listingId: 1,
    symbol: 'AAPL',
    currency: 'USD',
    lastPrice: 199.15,
    lastQuantity: 25,
    previousClose: 198,
    change: 1.15,
    changePercent: 0.58,
    tradedVolume: 12_500,
    bids: [
      { price: 199.1, size: 200, exchangeMic: 'IEXG' },
      { price: 199.05, size: 150, exchangeMic: 'IEXG' },
    ],
    offers: [
      { price: 199.2, size: 300, exchangeMic: 'IEXG' },
      { price: 199.25, size: 175, exchangeMic: 'IEXG' },
    ],
    asOf: AS_OF,
    source: 'ALPACA_IEX',
  },
  {
    listingId: 2,
    symbol: 'NVDA',
    currency: 'USD',
    lastPrice: 178.44,
    lastQuantity: 10,
    previousClose: 177.5,
    change: 0.94,
    changePercent: 0.53,
    tradedVolume: 8_100,
    bids: [{ price: 178.4, size: 100, exchangeMic: 'IEXG' }],
    offers: [{ price: 178.48, size: 200, exchangeMic: 'IEXG' }],
    asOf: AS_OF,
    source: 'ALPACA_IEX',
  },
]

const askOnlyQuote = {
  ...twoSidedQuotes[0],
  lastPrice: 201.1,
  change: 3.1,
  changePercent: 1.57,
  bids: [],
  offers: [{ price: 201.2, size: 400, exchangeMic: 'IEXG' }],
}

interface ApiOptions {
  quotes?: unknown[]
  quoteError?: string
}

async function installAuthenticatedSession(page: Page, canTrade = true) {
  await page.addInitScript(
    ({ accessToken, expiresAt, canTrade }) => {
      const user = {
        access_token: accessToken,
        token_type: 'Bearer',
        scope: 'openid profile',
        profile: {
          iss: window.location.origin,
          aud: 'emporia-web',
          sub: 'playwright-user',
          name: 'Playwright Trader',
          desk: 'playwright-desk',
          can_trade: canTrade,
          exp: expiresAt,
          iat: expiresAt - 3_600,
        },
        expires_at: expiresAt,
      }
      window.sessionStorage.setItem(
        `oidc.user:${window.location.origin}:emporia-web`,
        JSON.stringify(user),
      )
    },
    {
      accessToken: ACCESS_TOKEN,
      expiresAt: Math.floor(Date.now() / 1_000) + 3_600,
      canTrade,
    },
  )
}

async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: status >= 400 ? 'application/problem+json' : 'application/json',
    body: JSON.stringify(body),
  })
}

async function mockTradingApi(page: Page, options: ApiOptions = {}) {
  const authorizationHeaders: string[] = []
  const requestedQuoteIds: string[] = []
  const orderRequests: unknown[] = []
  const savedLayouts: unknown[] = []
  const currentOrders: unknown[] = []

  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    authorizationHeaders.push(request.headers().authorization ?? '')

    if (path === '/api/watchlist') {
      await fulfillJson(route, watchlist)
      return
    }
    if (path === '/api/orders' && request.method() === 'GET') {
      await fulfillJson(route, currentOrders)
      return
    }
    if (path === '/api/orders' && request.method() === 'POST') {
      const submitted = request.postDataJSON()
      orderRequests.push(submitted)
      const created = {
        id: '00000000-0000-0000-0000-000000000123',
        version: 0,
        ownerSubject: 'playwright-user',
        deskId: 'playwright-desk',
        listing: aapl,
        side: submitted.side,
        type: submitted.type,
        quantity: submitted.quantity,
        limitPrice: submitted.limitPrice,
        remainingQuantity: submitted.quantity,
        tradedQuantity: 0,
        averageTradePrice: null,
        status: 'LIVE',
        targetStatus: 'LIVE',
        destination: submitted.destination,
        originatorReference: 'playwright-order',
        parentOrderId: null,
        rootOrderId: '00000000-0000-0000-0000-000000000123',
        executionParameters: JSON.stringify(submitted.executionParameters),
        errorMessage: null,
        createdAt: AS_OF,
        updatedAt: AS_OF,
      }
      currentOrders.unshift(created)
      await fulfillJson(route, created, 201)
      return
    }
    if (path === '/api/workspace-preferences') {
      if (request.method() === 'PUT') {
        const submitted = request.postDataJSON()
        savedLayouts.push(submitted)
        await fulfillJson(route, { ...submitted, updatedAt: AS_OF })
        return
      }
      await fulfillJson(route, {
        layoutJson: JSON.stringify({
          version: 1,
          panels: ['watchlist', 'market-depth', 'order-ticket', 'parent-orders', 'child-orders'],
          columns: {},
        }),
        updatedAt: null,
      })
      return
    }
    if (path === '/api/orders/stream') {
      await route.fulfill({ status: 200, contentType: 'text/event-stream', body: ': connected\n\n' })
      return
    }
    if (path === '/api/market-data/stream') {
      requestedQuoteIds.push(url.searchParams.get('listingIds') ?? '')
      if (options.quoteError) {
        await fulfillJson(route, { detail: options.quoteError }, 503)
      } else {
        const quoteEvents = (options.quotes ?? twoSidedQuotes)
          .map((quote) => `event: quote\ndata: ${JSON.stringify(quote)}\n\n`)
          .join('')
        await route.fulfill({ status: 200, contentType: 'text/event-stream', body: quoteEvents })
      }
      return
    }
    if (path === '/api/market-data/quotes') {
      requestedQuoteIds.push(url.searchParams.get('listingIds') ?? '')
      if (options.quoteError) {
        await fulfillJson(route, { detail: options.quoteError }, 503)
      } else {
        await fulfillJson(route, options.quotes ?? twoSidedQuotes)
      }
      return
    }

    await fulfillJson(route, { detail: `Unexpected test request: ${path}` }, 404)
  })

  return { authorizationHeaders, requestedQuoteIds, orderRequests, savedLayouts }
}

function captureBrowserErrors(page: Page) {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text())
  })
  return errors
}

test.beforeEach(async ({ page }) => {
  await installAuthenticatedSession(page)
})

test('renders Alpaca IEX quotes from the API through the watchlist and depth panel', async ({ page }) => {
  const browserErrors = captureBrowserErrors(page)
  const { authorizationHeaders, requestedQuoteIds } = await mockTradingApi(page)

  await page.goto('/workspace')

  await expect(page.getByRole('heading', { name: 'Watchlist' })).toBeVisible()
  await expect(page.locator('.workspace-account')).toContainText('Playwright Trader')
  await expect(page.getByText('Live IEX', { exact: true })).toBeVisible()

  const appleRow = page.locator('.watch-row').filter({ hasText: 'AAPL' })
  await expect(appleRow).toContainText('199.10')
  await expect(appleRow).toContainText('199.20')
  await expect(appleRow).toContainText('199.15')

  const depthPanel = page.locator('.depth-panel')
  await expect(depthPanel.getByRole('heading', { name: 'AAPL' })).toBeVisible()
  await expect(depthPanel.getByText('Market depth', { exact: true })).toBeVisible()
  const firstLevel = depthPanel.locator('.depth-line').first()
  await expect(firstLevel.locator('.depth-side--bid')).toContainText('200')
  await expect(firstLevel.locator('.depth-side--bid')).toContainText('199.10')
  await expect(firstLevel.locator('.depth-side--ask')).toContainText('199.20')
  await expect(firstLevel.locator('.depth-side--ask')).toContainText('300')
  await expect(depthPanel.locator('.spread-line')).toContainText('0.10')
  await expect(depthPanel.locator('.simulation-note')).toContainText('Alpaca IEX top-of-book')

  await expect.poll(() => authorizationHeaders.length).toBeGreaterThanOrEqual(3)
  expect(authorizationHeaders).toEqual(
    expect.arrayContaining([`Bearer ${ACCESS_TOKEN}`]),
  )
  expect(authorizationHeaders.every((header) => header === `Bearer ${ACCESS_TOKEN}`)).toBe(true)
  expect(requestedQuoteIds).toContain('1,2')
  expect(browserErrors).toEqual([])
})

test('preserves and displays an ask-only IEX book', async ({ page }) => {
  const browserErrors = captureBrowserErrors(page)
  await mockTradingApi(page, { quotes: [askOnlyQuote] })

  await page.goto('/workspace')

  const depthPanel = page.locator('.depth-panel')
  await expect(depthPanel.getByRole('heading', { name: 'AAPL' })).toBeVisible()
  const firstLevel = depthPanel.locator('.depth-line').first()
  await expect(firstLevel.locator('.depth-side--bid')).toContainText('—')
  await expect(firstLevel.locator('.depth-side--ask')).toContainText('201.20')
  await expect(firstLevel.locator('.depth-side--ask')).toContainText('400')
  await expect(depthPanel.locator('.spread-line')).toContainText('N/A')
  expect(browserErrors).toEqual([])
})

test('keeps the workspace visible when market data is unavailable', async ({ page }) => {
  await mockTradingApi(page, { quoteError: 'Market data temporarily unavailable' })

  await page.goto('/workspace')

  await expect(page.getByRole('heading', { name: 'Watchlist' })).toBeVisible()
  await expect(page.getByRole('status')).toContainText('Market-data stream returned 503')
  await expect(page.locator('.watch-row')).toHaveCount(2)
  await expect(page.locator('.depth-panel')).toContainText('Select a symbol')
  await expect(page.locator('main.desk-grid')).toBeVisible()
})

test('submits a smart-routed desk order and persists workspace changes', async ({ page }) => {
  const { orderRequests, savedLayouts } = await mockTradingApi(page)

  await page.goto('/workspace')
  await page.getByRole('combobox', { name: /Destination/ }).selectOption('SMART')
  await page.getByLabel('Quantity').fill('25')
  await page.getByLabel('Limit price').fill('200.50')
  await page.getByRole('button', { name: 'Review buy order' }).click()
  await expect(page.getByRole('dialog', { name: '25 shares of AAPL' })).toContainText('Best-venue smart routing')
  await page.getByRole('button', { name: 'Place buy order' }).click()

  await expect.poll(() => orderRequests.length).toBe(1)
  expect(orderRequests[0]).toMatchObject({
    listingId: 1,
    side: 'BUY',
    type: 'LIMIT',
    quantity: 25,
    limitPrice: 200.5,
    destination: 'SMART',
  })
  const parentBlotter = page.locator('.blotter-panel').filter({ hasText: 'Parent order blotter' })
  await expect(parentBlotter).toContainText('AAPL')
  await expect(parentBlotter).toContainText('SMART')
  await expect(parentBlotter).toContainText('LIVE')

  await page.getByRole('checkbox', { name: 'Market depth' }).uncheck()
  await page.getByRole('button', { name: 'Save layout' }).click()
  await expect.poll(() => savedLayouts.length).toBe(1)
  const saved = savedLayouts[0] as { layoutJson: string }
  expect(JSON.parse(saved.layoutJson).panels).not.toContain('market-depth')
})

test('submits the legacy-compatible absolute VWAP parameter contract', async ({ page }) => {
  const { orderRequests } = await mockTradingApi(page)

  await page.goto('/workspace')
  await page.getByRole('combobox', { name: /Destination/ }).selectOption('VWAP')
  await page.getByLabel('Quantity').fill('25')
  await page.getByLabel('Limit price').fill('200.50')
  await page.getByLabel('Duration').fill('15')
  await page.getByLabel('Participation').fill('20')
  await page.getByRole('button', { name: 'Review buy order' }).click()
  await page.getByRole('button', { name: 'Place buy order' }).click()

  await expect.poll(() => orderRequests.length).toBe(1)
  const request = orderRequests[0] as {
    executionParameters: {
      utcStartTimeSecs: number
      utcEndTimeSecs: number
      buckets: number
    }
  }
  expect(request.executionParameters.utcStartTimeSecs).toBeGreaterThan(0)
  expect(request.executionParameters.utcEndTimeSecs - request.executionParameters.utcStartTimeSecs)
    .toBe(15 * 60)
  expect(request.executionParameters.buckets).toBe(5)
})

test('keeps order mutations disabled for a view-only identity', async ({ page }) => {
  await installAuthenticatedSession(page, false)
  await mockTradingApi(page)

  await page.goto('/workspace')

  await expect(page.getByText('This account has view-only permission.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Review buy order' })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Cancel all desk orders' })).toBeDisabled()
})
