import { expect, test, type Page, type Route } from '@playwright/test'

const ACCESS_TOKEN = 'playwright-admin-token'
const AS_OF = '2026-07-30T17:53:31.658Z'

async function installAdminSession(page: Page, authorities = ['ROLE_USER', 'ROLE_ADMIN']) {
  await page.addInitScript(
    ({ accessToken, expiresAt, authorities }) => {
      const user = {
        access_token: accessToken,
        token_type: 'Bearer',
        scope: 'openid profile',
        profile: {
          iss: window.location.origin,
          aud: 'emporia-web',
          sub: 'playwright-admin',
          name: 'Playwright Admin',
          preferred_username: 'playwright-admin',
          desk: 'admin',
          can_trade: true,
          authorities,
          exp: expiresAt,
          iat: expiresAt - 3_600,
        },
        expires_at: expiresAt,
      }
      window.sessionStorage.setItem(
        `oidc.user:${window.location.origin}:emporia-web`,
        JSON.stringify(user),
      )
      window.sessionStorage.setItem(
        'oidc.user:http://localhost:9000:emporia-web',
        JSON.stringify(user),
      )
    },
    {
      accessToken: ACCESS_TOKEN,
      expiresAt: Math.floor(Date.now() / 1_000) + 3_600,
      authorities,
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

async function mockAdminUsersApi(page: Page) {
  const authorizationHeaders: string[] = []

  await page.route('**/api/admin/users**', async (route) => {
    authorizationHeaders.push(route.request().headers().authorization ?? '')
    await fulfillJson(route, [
      {
        id: 'cd424859-f43f-43c8-bc0b-7a3593bd3a31',
        username: 'admin',
        email: 'admin@localhost',
        desk: 'admin',
        enabled: true,
        canTrade: true,
        authorities: ['ROLE_USER', 'ROLE_ADMIN'],
        createdAt: AS_OF,
      },
    ])
  })

  return { authorizationHeaders }
}

async function mockAdminReadApis(page: Page) {
  const auditCalls: string[] = []
  const executionCalls: string[] = []
  const portfolioCalls: string[] = []
  const staticDataCalls: string[] = []

  await page.route('**/api/admin/static-data/listings**', async (route) => {
    const url = new URL(route.request().url())
    staticDataCalls.push(url.toString())
    if (route.request().method() === 'PUT') {
      await fulfillJson(route, {
        id: 1,
        version: 4,
        symbol: 'AAPL',
        name: 'Apple Inc',
        marketSymbol: 'AAPL',
        exchangeMic: 'XNAS',
        exchangeName: 'Nasdaq',
        countryCode: 'US',
        currency: 'USD',
        enabled: true,
        tickSize: 0.01,
        sizeIncrement: 1,
        referencePrice: 240.5,
        previousClose: 235.7,
      })
      return
    }
    if (route.request().method() === 'POST' && url.pathname.endsWith('/listings/import')) {
      await fulfillJson(route, {
        requested: 1,
        created: 1,
        updated: 0,
        listingIds: [9001],
      }, 201)
      return
    }
    const pageNumber = Number(url.searchParams.get('page') ?? '0')
    const pageSize = Number(url.searchParams.get('size') ?? '100')
    const firstPageItems = [
      {
        id: 1,
        version: 3,
        symbol: 'AAPL',
        name: 'Apple Inc',
        marketSymbol: 'AAPL',
        exchangeMic: 'XNAS',
        exchangeName: 'Nasdaq',
        countryCode: 'US',
        currency: 'USD',
        enabled: true,
        tickSize: 0.01,
        sizeIncrement: 1,
        referencePrice: 236.12,
        previousClose: 235.7,
      },
      {
        id: 2,
        version: 1,
        symbol: 'MSFT',
        name: 'Microsoft Corporation',
        marketSymbol: 'MSFT',
        exchangeMic: 'XNAS',
        exchangeName: 'Nasdaq',
        countryCode: 'US',
        currency: 'USD',
        enabled: false,
        tickSize: 0.01,
        sizeIncrement: 1,
        referencePrice: 410.1,
        previousClose: 409.5,
      },
    ]
    const secondPageItems = [
      {
        id: 3,
        version: 2,
        symbol: 'GOOG',
        name: 'Alphabet Inc',
        marketSymbol: 'GOOG',
        exchangeMic: 'XNAS',
        exchangeName: 'Nasdaq',
        countryCode: 'US',
        currency: 'USD',
        enabled: true,
        tickSize: 0.01,
        sizeIncrement: 1,
        referencePrice: 190.15,
        previousClose: 189.7,
      },
    ]
    const items = pageNumber === 0 ? firstPageItems : secondPageItems
    await fulfillJson(route, {
      items,
      page: pageNumber,
      size: pageSize,
      totalElements: 3,
      totalPages: 2,
      first: pageNumber === 0,
      last: pageNumber >= 1,
    })
  })

  await page.route('**/api/admin/static-data/facets', async (route) => {
    staticDataCalls.push(route.request().url())
    await fulfillJson(route, {
      totalListings: 2,
      enabledListings: 1,
      disabledListings: 1,
      exchanges: [{ mic: 'XNAS', name: 'Nasdaq', listingCount: 2 }],
      currencies: ['USD'],
      countries: ['US'],
    })
  })

  await page.route('**/api/orders/executions**', async (route) => {
    executionCalls.push(route.request().url())
    await fulfillJson(route, [
      {
        id: '8a0a5662-8d82-4b90-b3ab-6da019fd0942',
        executionReference: 'XNAS-fill-1',
        orderId: '6d1a6d18-f83a-4185-b5a4-2c2d682c0820',
        rootOrderId: '6d1a6d18-f83a-4185-b5a4-2c2d682c0820',
        parentOrderId: null,
        deskId: 'admin',
        ownerSubject: 'admin',
        symbol: 'AAPL',
        side: 'BUY',
        destination: 'DMA',
        orderStatus: 'FILLED',
        quantity: 10,
        price: 236.12,
        venue: 'XNAS',
        executedAt: AS_OF,
      },
    ])
  })

  await page.route('**/api/orders/strategies**', async (route) => {
    executionCalls.push(route.request().url())
    await fulfillJson(route, [
      {
        orderId: '45a1d81e-3304-4e49-a9b7-bae6b2767698',
        rootOrderId: '45a1d81e-3304-4e49-a9b7-bae6b2767698',
        deskId: 'admin',
        ownerSubject: 'admin',
        symbol: 'MSFT',
        side: 'BUY',
        destination: 'VWAP',
        status: 'LIVE',
        targetStatus: 'LIVE',
        quantity: 20,
        tradedQuantity: 5,
        remainingQuantity: 15,
        averageTradePrice: 410.1,
        childOrderCount: 2,
        createdAt: AS_OF,
        updatedAt: AS_OF,
      },
    ])
  })

  await page.route('**/api/portfolio/state/100', async (route) => {
    portfolioCalls.push(route.request().url())
    if (route.request().method() === 'POST') {
      await fulfillJson(route, {
        schemaVersion: 1,
        clientId: 100,
        firstTransactionId: 60,
        updatedAt: AS_OF,
        balances: [
          { assetId: 0, amount: 100000 },
          { assetId: 840, amount: 2500 },
        ],
        latestReceipt: null,
      }, 201)
      return
    }
    await fulfillJson(route, {
      schemaVersion: 1,
      clientId: 100,
      firstTransactionId: 50,
      updatedAt: AS_OF,
      balances: [
        { assetId: 0, amount: 100000 },
        { assetId: 1, amount: 250 },
      ],
      latestReceipt: {
        eventId: 'exchange-1:10:100',
        exchangeId: 'exchange-1',
        deliveryId: 10,
        receivedAt: AS_OF,
      },
    })
  })

  await page.route('**/api/admin/audit/events**', async (route) => {
    auditCalls.push(route.request().url())
    await fulfillJson(route, {
      items: [
        {
          id: '9a7d7ba4-bc62-4f32-927f-df4cdb188e25',
          occurredAt: AS_OF,
          actorSubject: 'playwright-admin',
          actorUsername: 'playwright-admin',
          actorDesk: 'admin',
          action: 'USER_CREATED',
          entityType: 'USER',
          entityId: 'cd424859-f43f-43c8-bc0b-7a3593bd3a31',
          result: 'SUCCESS',
          requestId: 'playwright-audit-1',
          beforeJson: null,
          afterJson: JSON.stringify({
            id: 'cd424859-f43f-43c8-bc0b-7a3593bd3a31',
            username: 'managed-user',
            email: 'managed-user@localhost',
            enabled: true,
            desk: 'default',
            canTrade: false,
            authorities: ['ROLE_USER'],
          }),
          metadataJson: null,
        },
      ],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    })
  })

  await page.route('**/api/admin/static-data/audit/events**', async (route) => {
    auditCalls.push(route.request().url())
    await fulfillJson(route, {
      items: [
        {
          id: 'fd6a33f4-8d28-46fb-bf52-0ed05685dbfb',
          occurredAt: AS_OF,
          actorSubject: 'playwright-admin',
          actorUsername: 'playwright-admin',
          actorDesk: 'admin',
          action: 'STATIC_DATA_LISTING_UPDATED',
          entityType: 'INSTRUMENT_LISTING',
          entityId: '1',
          result: 'SUCCESS',
          requestId: 'playwright-static-data-1',
          beforeJson: null,
          afterJson: JSON.stringify({ id: 1, symbol: 'AAPL', referencePrice: 240.5 }),
          metadataJson: null,
        },
      ],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    })
  })

  await page.route('**/api/portfolio/audit/events**', async (route) => {
    auditCalls.push(route.request().url())
    await fulfillJson(route, {
      items: [
        {
          id: '7181c5e5-76b0-4510-a202-2ce0531092d0',
          occurredAt: AS_OF,
          actorSubject: 'playwright-admin',
          actorUsername: 'playwright-admin',
          actorDesk: 'admin',
          action: 'PORTFOLIO_PROVISIONED',
          entityType: 'PORTFOLIO',
          entityId: '100',
          result: 'SUCCESS',
          requestId: 'playwright-portfolio-1',
          beforeJson: null,
          afterJson: JSON.stringify({ clientId: 100, firstTransactionId: 60 }),
          metadataJson: null,
        },
      ],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    })
  })

  return { auditCalls, executionCalls, portfolioCalls, staticDataCalls }
}

test('opens the admin shell from /admin and renders section navigation', async ({ page }) => {
  await installAdminSession(page)
  const { authorizationHeaders } = await mockAdminUsersApi(page)

  await page.goto('/admin')

  await expect(page).toHaveURL(/\/admin\/users$/)
  await expect(page.getByRole('navigation', { name: 'Admin sections' })).toBeVisible()
  await expect(page.getByRole('link', { name: /Users Accounts and roles/ })).toHaveClass(/active/)
  await expect(page.getByRole('heading', { name: 'User accounts' })).toBeVisible()
  await expect(page.locator('.admin-account')).toContainText('Playwright Admin')
  expect(authorizationHeaders).toContain(`Bearer ${ACCESS_TOKEN}`)
})

test('switches between admin shell sections and renders read-only views', async ({ page }) => {
  await installAdminSession(page)
  const { authorizationHeaders } = await mockAdminUsersApi(page)
  const { auditCalls, executionCalls, portfolioCalls, staticDataCalls } = await mockAdminReadApis(page)

  await page.goto('/admin/users')
  await expect(page.getByRole('heading', { name: 'User accounts' })).toBeVisible()
  const callsAfterUsersLoad = authorizationHeaders.length
  expect(callsAfterUsersLoad).toBeGreaterThan(0)

  await page.getByRole('link', { name: /Static data Listings and venues/ }).click()
  await expect(page).toHaveURL(/\/admin\/static-data$/)
  await expect(page.getByRole('heading', { name: 'Static Data' })).toBeVisible()
  await expect(page.getByRole('link', { name: /Static data Listings and venues/ })).toHaveClass(/active/)
  await expect(page.getByRole('cell', { name: /Apple Inc/ })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'AAPL' })).toBeVisible()
  await expect(page.getByText('Page 1 of 2')).toBeVisible()
  await page.getByLabel('Reference price').fill('240.5')
  await page.getByRole('button', { name: 'Save listing' }).click()
  await expect(page.getByText('AAPL saved')).toBeVisible()
  await page.getByLabel('Static-data import JSON').fill(JSON.stringify([
    {
      id: 9001,
      symbol: 'DEMO',
      name: 'Demo Instrument',
      marketSymbol: 'DEMO',
      exchangeMic: 'XNAS',
      exchangeName: 'Nasdaq',
      countryCode: 'US',
      currency: 'USD',
      enabled: true,
      tickSize: 0.01,
      sizeIncrement: 1,
      referencePrice: 100,
      previousClose: 99,
    },
  ]))
  await page.getByRole('button', { name: 'Import' }).click()
  await expect(page.getByText('Imported 1 listings')).toBeVisible()
  await page.getByRole('button', { name: 'Next listings page' }).click()
  await expect(page.getByRole('cell', { name: /Alphabet Inc/ })).toBeVisible()
  await expect(page.getByText('Page 2 of 2')).toBeVisible()

  await page.getByRole('link', { name: /Execution Strategy health/ }).click()
  await expect(page).toHaveURL(/\/admin\/execution$/)
  await expect(page.getByRole('heading', { name: 'Execution Monitoring' })).toBeVisible()
  await expect(page.getByRole('link', { name: /Execution Strategy health/ })).toHaveClass(/active/)
  await expect(page.getByRole('cell', { name: 'XNAS' })).toBeVisible()
  await expect(page.getByRole('cell', { name: /MSFT VWAP/ })).toBeVisible()

  await page.getByRole('link', { name: /Portfolio state Positions and cash/ }).click()
  await expect(page).toHaveURL(/\/admin\/portfolio$/)
  await expect(page.getByRole('heading', { name: 'Portfolio State' })).toBeVisible()
  expect(portfolioCalls).toHaveLength(0)
  await page.getByLabel('Client', { exact: true }).fill('100')
  await page.getByRole('button', { name: 'Load' }).click()
  await expect(page.getByRole('cell', { name: /Asset 0/ })).toBeVisible()
  await expect(page.getByRole('cell', { name: '100,000' })).toBeVisible()
  await page.getByLabel('New client', { exact: true }).fill('100')
  await page.getByLabel('First transaction', { exact: true }).fill('60')
  await page.getByLabel('Portfolio balances JSON', { exact: true }).fill(JSON.stringify([
    { assetId: 0, amount: 100000 },
    { assetId: 840, amount: 2500 },
  ]))
  await page.getByRole('button', { name: 'Provision' }).click()
  await expect(page.getByText('Portfolio 100 provisioned')).toBeVisible()
  await expect(page.getByRole('cell', { name: /Asset 840/ })).toBeVisible()

  await page.getByRole('link', { name: /Audit Write history/ }).click()
  await expect(page).toHaveURL(/\/admin\/audit$/)
  await expect(page.getByRole('heading', { name: 'Audit Log' })).toBeVisible()
  await expect(page.getByRole('link', { name: /Audit Write history/ })).toHaveClass(/active/)
  await expect(page.getByRole('cell', { name: /USER CREATED/ })).toBeVisible()
  await expect(page.getByRole('cell', { name: /Static data/ })).toBeVisible()
  await expect(page.getByText('"username": "managed-user"')).toBeVisible()
  await expect(page.getByText('super-secret')).toHaveCount(0)

  expect(authorizationHeaders).toHaveLength(callsAfterUsersLoad)
  expect(staticDataCalls.length).toBeGreaterThanOrEqual(2)
  expect(executionCalls.length).toBeGreaterThanOrEqual(2)
  expect(portfolioCalls).toHaveLength(2)
  expect(auditCalls.length).toBeGreaterThanOrEqual(3)
})

test('blocks non-admin sessions before the user-management API loads', async ({ page }) => {
  await installAdminSession(page, ['ROLE_USER'])
  let adminApiCalls = 0
  await page.route('**/api/admin/users**', async (route) => {
    adminApiCalls += 1
    await fulfillJson(route, { detail: 'Unexpected admin API request' }, 403)
  })

  await page.goto('/admin/users')

  await expect(page.getByRole('heading', { name: 'Administrator access required' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Return home' })).toBeVisible()
  expect(adminApiCalls).toBe(0)
})
