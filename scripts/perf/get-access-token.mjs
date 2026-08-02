// Prints an access token for the local stack on stdout, and nothing else, so
// shell scripts can capture it with $(...).
//
// The authorization server only allows the authorization-code + PKCE flow for
// the emporia-web client; there is no password/direct grant. This is the same
// login sequence scripts/oidc-smoke-test.mjs performs, extracted so smoke
// scripts do not each reimplement PKCE.
//
//   ACCESS_TOKEN=$(EMPORIA_USERNAME=admin EMPORIA_PASSWORD=admin123 \
//       node scripts/perf/get-access-token.mjs)
import { createHash, randomBytes } from 'node:crypto'

const origin = process.env.EMPORIA_ORIGIN ?? 'http://localhost:3001'
const username = process.env.EMPORIA_USERNAME
const password = process.env.EMPORIA_PASSWORD

if (!username || !password) {
  console.error('Set EMPORIA_USERNAME and EMPORIA_PASSWORD before running this.')
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
  if (authorizationResponse.status !== 302) {
    throw new Error(`Authorization request returned HTTP ${authorizationResponse.status}`)
  }

  const csrfResponse = await request(new URL('/auth/csrf', origin), {
    headers: { Accept: 'application/json' },
  })
  if (!csrfResponse.ok) throw new Error(`CSRF endpoint returned HTTP ${csrfResponse.status}`)
  const csrf = await csrfResponse.json()

  let response = await request(new URL('/login', origin), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username, password, [csrf.parameterName]: csrf.token }),
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
  if (!code) {
    throw new Error(`Authorization failed: ${callback.searchParams.get('error') ?? 'missing code'}`)
  }

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
  if (!tokens.access_token) throw new Error('Token response contained no access_token')

  process.stdout.write(tokens.access_token)
} catch (error) {
  console.error(`Could not obtain an access token: ${error.message}`)
  process.exit(1)
}
