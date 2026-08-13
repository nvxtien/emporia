import { createHash, randomBytes, randomUUID } from 'node:crypto'

const origin = process.env.EMPORIA_ORIGIN ?? 'http://localhost:3000'
const username = process.env.EMPORIA_USERNAME
const password = process.env.EMPORIA_PASSWORD

if (!username || !password) {
  console.error('Set EMPORIA_USERNAME and EMPORIA_PASSWORD before running this check.')
  process.exit(2)
}

const cookies = new Map()

function base64url(value) {
  return value.toString('base64').replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '')
}

function captureCookies(response) {
  const values = typeof response.headers.getSetCookie === 'function'
    ? response.headers.getSetCookie()
    : [response.headers.get('set-cookie')].filter(Boolean)
  for (const value of values) {
    const [pair] = value.split(';', 1)
    const separator = pair.indexOf('=')
    cookies.set(pair.slice(0, separator), pair.slice(separator + 1))
  }
}

function cookieHeader() {
  return [...cookies.entries()].map(([name, value]) => `${name}=${value}`).join('; ')
}

async function request(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    redirect: 'manual',
    headers: {
      ...options.headers,
      ...(cookies.size ? { Cookie: cookieHeader() } : {}),
    },
  })
  captureCookies(response)
  return response
}

function redirectUrl(response) {
  const location = response.headers.get('location')
  if (!location) throw new Error(`Expected a redirect, received HTTP ${response.status}`)
  return new URL(location, origin)
}

const verifier = base64url(randomBytes(48))
const challenge = base64url(createHash('sha256').update(verifier).digest())
const state = base64url(randomBytes(24))
const nonce = base64url(randomBytes(24))
const redirectUri = `${origin}/auth/callback`
const authorizationUrl = new URL('/oauth2/authorize', origin)
authorizationUrl.search = new URLSearchParams({
  response_type: 'code',
  client_id: 'emporia-web',
  redirect_uri: redirectUri,
  scope: 'openid profile',
  state,
  nonce,
  code_challenge: challenge,
  code_challenge_method: 'S256',
}).toString()

try {
  const authorizationResponse = await request(authorizationUrl, {
    headers: { Accept: 'text/html,application/xhtml+xml' },
  })
  if (authorizationResponse.status !== 302) throw new Error(`Authorization request returned HTTP ${authorizationResponse.status}`)

  const csrfResponse = await request(new URL('/auth/csrf', origin), {
    headers: { Accept: 'application/json' },
  })
  if (!csrfResponse.ok) throw new Error(`CSRF endpoint returned HTTP ${csrfResponse.status}`)
  const csrf = await csrfResponse.json()

  const loginBody = new URLSearchParams({
    username,
    password,
    [csrf.parameterName]: csrf.token,
  })
  let response = await request(new URL('/login', origin), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: loginBody,
  })
  if (response.status !== 302 || response.headers.get('location')?.includes('error')) {
    throw new Error('Login failed; check EMPORIA_USERNAME and EMPORIA_PASSWORD')
  }

  let callback
  for (let redirectCount = 0; redirectCount < 6; redirectCount += 1) {
    const next = redirectUrl(response)
    if (next.pathname === '/auth/callback') {
      callback = next
      break
    }
    response = await request(next, { headers: { Accept: 'text/html,application/xhtml+xml' } })
  }
  if (!callback) throw new Error('Authorization server did not return to the frontend callback')
  if (callback.searchParams.get('state') !== state) throw new Error('OAuth state did not match')
  const code = callback.searchParams.get('code')
  if (!code) throw new Error(`Authorization failed: ${callback.searchParams.get('error') ?? 'missing code'}`)

  const tokenResponse = await request(new URL('/oauth2/token', origin), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: 'emporia-web',
      redirect_uri: redirectUri,
      code,
      code_verifier: verifier,
    }),
  })
  if (!tokenResponse.ok) throw new Error(`Token exchange returned HTTP ${tokenResponse.status}`)
  const tokens = await tokenResponse.json()

  const apiHeaders = { Authorization: `Bearer ${tokens.access_token}`, Accept: 'application/json' }
  const [apiResponse, watchlistResponse, quotesResponse, ordersResponse] = await Promise.all([
    fetch(`${origin}/api/instruments?query=AAPL`, { headers: apiHeaders }),
    fetch(`${origin}/api/watchlist`, { headers: apiHeaders }),
    fetch(`${origin}/api/market-data/quotes?listingIds=1,2`, { headers: apiHeaders }),
    fetch(`${origin}/api/orders`, { headers: apiHeaders }),
  ])
  for (const responseToCheck of [apiResponse, watchlistResponse, quotesResponse, ordersResponse]) {
    if (!responseToCheck.ok) {
      throw new Error(`Authenticated API request returned HTTP ${responseToCheck.status}`)
    }
  }
  const [listings, watchlist, quotes, orders] = await Promise.all([
    apiResponse.json(),
    watchlistResponse.json(),
    quotesResponse.json(),
    ordersResponse.json(),
  ])
  if (!Array.isArray(listings) || listings[0]?.symbol !== 'AAPL') {
    throw new Error('Authenticated API response did not contain the expected listing')
  }
  if (!Array.isArray(watchlist) || watchlist.length === 0) throw new Error('Watchlist was not initialized')
  if (!Array.isArray(quotes) || quotes.length !== 2) throw new Error('Quote batch was not returned')
  if (!Array.isArray(orders)) throw new Error('Order blotter response was invalid')

  const placeOrder = (overrides = {}) => fetch(`${origin}/api/orders`, {
    method: 'POST',
    headers: { ...apiHeaders, 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
    body: JSON.stringify({
      listingId: 1,
      side: 'BUY',
      type: 'MARKET',
      quantity: 1,
      limitPrice: null,
      destination: 'DMA',
      originatorReference: `smoke-${Date.now()}-${base64url(randomBytes(6))}`,
      executionParameters: {},
      ...overrides,
    }),
  })

  const readOrder = async (orderId) => {
    const response = await fetch(`${origin}/api/orders/${orderId}`, { headers: apiHeaders })
    if (!response.ok) throw new Error(`Order ${orderId} returned HTTP ${response.status}`)
    return response.json()
  }

  const waitForOrder = async (orderId, predicate, description, timeoutMs = 10_000) => {
    const deadline = Date.now() + timeoutMs
    let current
    while (Date.now() < deadline) {
      current = await readOrder(orderId)
      if (predicate(current)) return current
      await new Promise((resolve) => setTimeout(resolve, 100))
    }
    throw new Error(`${description}; last state was ${current?.status}/${current?.targetStatus}`)
  }

  const createResponse = await placeOrder()
  if (createResponse.status !== 201) throw new Error(`Order create returned HTTP ${createResponse.status}`)
  const createdOrder = await createResponse.json()
  if (createdOrder.status !== 'LIVE' || !createdOrder.id) throw new Error('Created order was not live')

  const cancelResponse = await fetch(`${origin}/api/orders/${createdOrder.id}/cancel`, {
    method: 'POST',
    headers: { ...apiHeaders, 'Idempotency-Key': randomUUID() },
  })
  if (!cancelResponse.ok) throw new Error(`Order cancel returned HTTP ${cancelResponse.status}`)
  const pendingCancel = await cancelResponse.json()
  if (pendingCancel.targetStatus !== 'CANCELLED') {
    throw new Error('Cancel command did not persist a pending cancellation target')
  }
  const cancelledOrder = await waitForOrder(
    createdOrder.id,
    (order) => order.status === 'CANCELLED' || order.status === 'FILLED',
    'Cancel/fill race did not reach a venue-confirmed terminal state',
  )

  const historyResponse = await fetch(`${origin}/api/orders/${createdOrder.id}/history`, { headers: apiHeaders })
  if (!historyResponse.ok) throw new Error(`Order history returned HTTP ${historyResponse.status}`)
  const history = await historyResponse.json()
  if (!Array.isArray(history) || history.length < 2) throw new Error('Order history was not materialized')

  const smartResponse = await placeOrder({
    type: 'LIMIT',
    quantity: 3,
    limitPrice: 1_000_000,
    destination: 'SMART',
  })
  if (smartResponse.status !== 201) throw new Error(`SMART order create returned HTTP ${smartResponse.status}`)
  const smart = await smartResponse.json()
  await waitForOrder(smart.id, (order) => order.status === 'FILLED',
    'Depth-aware SMART parent did not fill through DMA children')

  const vwapStart = Math.floor(Date.now() / 1000)
  const vwapResponse = await placeOrder({
    type: 'LIMIT',
    quantity: 2,
    limitPrice: 1_000_000,
    destination: 'VWAP',
    executionParameters: {
      utcStartTimeSecs: vwapStart,
      utcEndTimeSecs: vwapStart + 60,
      buckets: 2,
    },
  })
  if (vwapResponse.status !== 201) throw new Error(`VWAP order create returned HTTP ${vwapResponse.status}`)
  const vwap = await vwapResponse.json()
  await waitForOrder(vwap.id, (order) => order.status === 'FILLED',
    'Legacy-parameter VWAP parent did not fill through scheduled children')

  const secondCreateResponse = await placeOrder()
  if (secondCreateResponse.status !== 201) throw new Error(`Second order create returned HTTP ${secondCreateResponse.status}`)
  const cancelAllResponse = await fetch(`${origin}/api/orders/cancel-all`, { method: 'POST', headers: apiHeaders })
  if (!cancelAllResponse.ok) throw new Error(`Order command service cancel-all returned HTTP ${cancelAllResponse.status}`)
  const cancelAll = await cancelAllResponse.json()
  if (!Number.isInteger(cancelAll.cancelled) || cancelAll.cancelled < 0) {
    throw new Error('Order monitor returned an invalid cancel-all count')
  }

  console.log(`OIDC, gateway, ${watchlist.length} watchlist rows, ${quotes.length} quotes, DMA cancel/fill race (${cancelledOrder.status}), SMART, VWAP, history, and cancel-all passed.`)
} catch (error) {
  console.error(error instanceof Error ? error.message : error)
  process.exit(1)
}
