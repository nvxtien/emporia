import { defineConfig, loadEnv, type ProxyOptions } from 'vite'
import react from '@vitejs/plugin-react'

const gatewayPaths = [
  '/.well-known/openid-configuration',
  '/.well-known/oauth-authorization-server',
  '/oauth2',
  '/userinfo',
  '/connect',
  '/auth/csrf',
  '/login',
  '/logout',
  '/default-ui.css',
  '/favicon.ico',
  '/api',
]

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const gatewayProxy: ProxyOptions = {
    target: env.VITE_GATEWAY_PROXY_TARGET ?? 'http://localhost:8082',
    changeOrigin: false,
  }
  const proxy = Object.fromEntries(
    gatewayPaths.map((path) => [path, gatewayProxy]),
  )

  return {
    plugins: [react()],
    server: {
      host: 'localhost',
      port: 3000,
      strictPort: true,
      proxy,
    },
    preview: {
      host: 'localhost',
      port: 3000,
      strictPort: true,
      proxy,
    },
  }
})
