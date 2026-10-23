// Run with the dashboard dev server: node scripts/test-donino-ui.mjs
// API responses are fixtures; no controller, pairing or deployment is contacted.
import { chromium } from 'playwright'
import assert from 'node:assert/strict'
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] })
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } })
  await page.addInitScript(() => {
    localStorage.setItem('token', 'test')
    localStorage.setItem('i18nextLng', 'fa')
  })
  let available = true
  let owner = true
  let starts = 0
  let previewBody
  const errors = []
  page.on('pageerror', error => errors.push(String(error)))
  await page.route('**/api/**', route => {
    if (route.request().url().includes('/src/')) return route.continue()
    const path = new URL(route.request().url()).pathname
    let data = {}
    if (path === '/api/admin') data = { username: 'danial', status: 'active', role: { is_owner: owner, permissions: {}, features: {}, access: {} } }
    else if (path === '/api/tunnels/state') data = { available, busy: false, profiles: [{ id: 'a'.repeat(32), name: 'Frankfurt', host: 'exit.example.com' }], jobs: [] }
    else if (path === '/api/tunnels/connection') data = { ok: true, ready: true, runtime_match: true }
    else if (path === '/api/tunnels/preview') {
      previewBody = route.request().postDataJSON()
      data = {
        id: 'a'.repeat(32),
        expires_at: 2000000000,
        summary: {
          iran: 'iran.example.com',
          foreign: 'exit.example.com',
          transports: ['frp-wss', 'frp-quic'],
          services: [{ id: 'vpn', listen: '0.0.0.0:443', target: '127.0.0.1:8443' }],
          firewall: { iran: { tcp: [443, 24443], udp: [24444] }, foreign: { tcp: [], udp: [] } },
        },
      }
    } else if (path === '/api/tunnels/start') {
      starts++
      assert.equal(route.request().postDataJSON().confirm_replace, true)
      data = { id: 'b'.repeat(32), plan_id: 'a'.repeat(32), status: 'running', stage: 'prepare', created: 1, updated: 1 }
    }
    return route.fulfill({ json: data })
  })
  const url = `${process.env.DASHBOARD_URL || 'http://127.0.0.1:4173'}/#/nodes/tunnels`
  await page.goto(url)
  await page.getByRole('heading', { name: 'Donino Tunnel', exact: true }).waitFor()
  const form = page.locator('form').nth(1)
  await form.locator('select').selectOption('a'.repeat(32))
  await form.locator('[name=iran]').fill('iran.example.com')
  await page.getByRole('button', { name: 'تست اتصال', exact: true }).click()
  await page.getByRole('status').filter({ hasText: 'کانال مدیریت و نسخه آماده‌اند' }).waitFor()
  const preview = page.getByRole('button', { name: 'پیش‌نمایش نقشه و پورت‌ها', exact: true })
  await preview.click()
  const start = page.getByRole('button', { name: 'شروع نصب دوطرفه', exact: true })
  await start.waitFor()
  assert.equal(await start.isDisabled(), true)
  assert.equal(previewBody.services[0].target_port, 8443)
  // Every edit to a reviewed form must discard the previous plan and consent.
  await form.locator('[name=health_port]').fill('19091')
  assert.equal(await start.count(), 0)
  await preview.click()
  await start.waitFor()
  await page.getByLabel('نقشه و جایگزینی نصب قبلی را تأیید می‌کنم.').check()
  assert.equal(await start.isEnabled(), true)
  await start.click()
  await page.getByRole('status').filter({ hasText: 'عملیات ثبت شد' }).waitFor()
  assert.equal(starts, 1)
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  available = false
  await page.getByRole('button', { name: 'تازه‌سازی', exact: true }).click()
  await page.getByText('نقش ایران Donino Tunnel باید روی میزبان پنل آماده باشد.', { exact: false }).waitFor()
  assert.equal(await preview.isDisabled(), true)
  owner = false
  await page.reload()
  await page.waitForURL(url => !url.hash.includes('/nodes/tunnels'))
  assert.deepEqual(errors, [])
  console.log('Passed: owner navigation, connection check, preview, invalidation on edit, consent, single start, disabled controller and mobile layout.')
} finally {
  await browser.close()
}
