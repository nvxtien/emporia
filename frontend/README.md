# Emporia Frontend

React 19 and TypeScript stock-trading workspace for Emporia. It provides
instrument search, personal watchlists, five-level market depth, DMA/SMART/VWAP
order entry, an order blotter, and order audit history. Users
authenticate through the Emporia gateway with OpenID Connect Authorization
Code and PKCE. The application does not contain or use an OAuth client secret.

The workspace calls the Spring trading service through `/api`. Quotes and depth
are deliberately marked `SIMULATED`; orders are persisted locally but no
exchange or execution venue is connected yet.

## Requirements

- Node.js 22.12 or newer
- npm 11 or newer
- Gateway running on `http://localhost:8080`
- Authorisation service running behind the gateway
- Trading service running on `http://localhost:8081`

## Install and run

From the repository root:

```bash
cd emporia/frontend
npm install
npm run dev
```

Open `http://localhost:3000` and select **Sign in**. Successful login opens
`/workspace`. Vite proxies the OIDC and
login paths to the gateway, so the browser stays on the frontend origin (for
example, `http://localhost:3000/login`). After login and consent, the
authorization server returns to `/auth/callback` and the frontend exchanges the
code using its PKCE verifier.

To create the initial local user, start the authorisation service with its
bootstrap administrator enabled. See the service README for the environment
variables.

## Configuration

Copy `.env.example` to `.env.local` when overrides are needed:

| Variable | Default | Purpose |
|---|---|---|
| `VITE_OIDC_AUTHORITY` | Current frontend origin | Public browser-facing OIDC issuer |
| `VITE_OIDC_CLIENT_ID` | `emporia-web` | Registered public SPA client |
| `VITE_GATEWAY_PROXY_TARGET` | `http://localhost:8080` | Internal Vite proxy destination for gateway paths |

Never add a client secret to a `VITE_*` variable. Vite embeds those values in
the public browser bundle.

## Authentication behavior

- Authorization Code with PKCE is used for login.
- The user and access token are stored in `sessionStorage`, scoped to the tab.
- OIDC metadata, login, token, user-info, and logout requests use the frontend
  origin and are proxied internally to the gateway.
- The branded `/sign-in` route gets a session-bound CSRF token before posting
  credentials directly to Spring Security; passwords are not stored in React state.
- The first-party `emporia-web` client returns directly to the application after
  successful authentication without a third-party consent screen.
- The public SPA does not receive refresh tokens from Spring Authorization Server.
- When the short-lived access token expires, the user is asked to sign in again.
- Every `/api/**` request carries the access token to both the gateway and trading service.

## Validate and build

```bash
npm run lint
npm run build
```

The production build is written to `dist/`.

## Browser tests

The Playwright suite loads the trading workspace with a test OIDC session and
intercepts `/api/**` with deterministic responses. It verifies Alpaca IEX quote
rendering, market depth, ask-only books, bearer-token propagation, browser
errors, and the market-data unavailable state without using live credentials or
depending on market hours.

Install Chromium once, then run the suite:

```bash
npx playwright install chromium
npm run test:e2e
```

Playwright starts the Vite development server automatically. To test a frontend
that is already running elsewhere, set its origin and Playwright will not start
another server:

```bash
PLAYWRIGHT_BASE_URL=http://localhost:3001 npm run test:e2e
```

Failure screenshots and traces are written to `test-results/`. The HTML report
is written to `playwright-report/`.

## Container image

The included Nginx configuration supports SPA callback routes:

```bash
docker build -t emporia-frontend emporia/frontend
docker run --rm -p 3000:80 emporia-frontend
```
