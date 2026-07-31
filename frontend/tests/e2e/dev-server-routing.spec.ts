import { expect, test } from '@playwright/test'

test('serves Chrome app-specific well-known probes from the SPA shell', async ({ request }) => {
  const response = await request.get('/.well-known/appspecific/com.chrome.devtools.json?continue', {
    headers: { Accept: 'text/html' },
  })
  const body = await response.text()

  expect(response.status()).toBe(200)
  expect(response.headers()['content-type']).toContain('text/html')
  expect(body).toContain('<div id="root"></div>')
  expect(body).not.toContain('Whitelabel Error Page')
  expect(body).not.toContain('This application has no explicit mapping for /error')
})
