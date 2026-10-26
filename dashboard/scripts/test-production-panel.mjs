// Actual bundled dashboard and API. Delayed lazy chunk reproduces a lost-create-click regression.
import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const origin = process.env.PANEL_TEST_URL
assert(origin && ['127.0.0.1', 'localhost'].includes(new URL(origin).hostname), 'Use an isolated local test panel')
assert(process.env.PANEL_TEST_TOKEN && process.env.PANEL_TEST_REGISTRATION)
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] })
try {
  // Only this disposable loopback server uses a private short-lived test certificate.
  const page = await browser.newPage({ ignoreHTTPSErrors: true })
  const errors = []
  let writes = 0
  page.on('pageerror', error => errors.push(String(error)))
  page.on('request', request => {
    if (request.url().endsWith('/api/node') && request.method() === 'POST') writes++
  })
  await page.addInitScript(token => {
    localStorage.setItem('token', token)
    localStorage.setItem('i18nextLng', 'fa')
  }, process.env.PANEL_TEST_TOKEN)
  await page.goto(`${origin}/dashboard/#/tunnels`)
  await page.getByRole('heading', { name: 'تونل‌های بومی نودها' }).waitFor()
  await page.route('**/*_dashboard.nodes._index-*.js', async route => {
    await new Promise(resolve => setTimeout(resolve, 700))
    await route.continue()
  })
  await page.goto(`${origin}/dashboard/#/nodes`)
  await page.getByRole('button', { name: 'افزودن گره', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.waitFor()
  await dialog.locator('#native-node-registration').setInputFiles(process.env.PANEL_TEST_REGISTRATION)
  await page.waitForFunction(() => document.querySelector('[name="name"]')?.value === 'Production test node')
  assert.equal(await dialog.locator('[name="name"]').inputValue(), 'Production test node')
  assert.equal(await dialog.locator('[name="address"]').inputValue(), '192.0.2.10')
  await page.waitForTimeout(1500)
  assert.equal(writes, 0, 'Import must not automatically register a node')
  assert.deepEqual(errors, [])
  console.log('PASS: production tunnel page, first node-create click during lazy load, private registration import without automatic save')
} finally {
  await browser.close()
}
