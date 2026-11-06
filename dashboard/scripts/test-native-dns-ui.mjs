// Real native API + disposable SQLite database. Start Vite on port 4173 first.
import { chromium } from 'playwright'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, rm, mkdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
const root = fileURLToPath(new URL('../../', import.meta.url))
const temp = await mkdtemp(path.join(tmpdir(), 'native-dns-browser-'))
const port = process.env.TEST_DNS_PORT || '8085'
const backend = `http://127.0.0.1:${port}`
const processServer = spawn(path.join(root, '.venv/bin/python'), [path.join(root, 'tests/fixtures/native_dns_server.py')], {
  cwd: root,
  env: { ...process.env, TEST_DNS_DB_PATH: path.join(temp, 'test.db'), TEST_DNS_PORT: port },
  stdio: ['ignore', 'pipe', 'pipe'],
})
let serverLog = ''
processServer.stdout.on('data', chunk => {
  serverLog += chunk.toString()
})
processServer.stderr.on('data', chunk => {
  serverLog += chunk.toString()
})
let browser
try {
  let ready = false
  for (let i = 0; i < 100; i++) {
    try {
      const result = await fetch(`${backend}/api/admin`)
      ready = result.ok
    } catch {}
    if (ready) break
    if (processServer.exitCode !== null) throw new Error(serverLog)
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  assert(ready, serverLog)
  browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] })
  const page = await browser.newPage({ viewport: { width: 1440, height: 1100 }, permissions: ['clipboard-read', 'clipboard-write'] })
  const errors = []
  page.on('pageerror', error => errors.push(String(error)))
  await page.addInitScript(() => {
    localStorage.setItem('token', 'local-test')
    localStorage.setItem('i18nextLng', 'fa')
  })
  // Proxy actual browser API requests to the real isolated backend.
  await page.route('**/api/**', async route => {
    if (route.request().url().includes('/src/')) return route.continue()
    const url = new URL(route.request().url())
    const response = await route.fetch({ url: backend + url.pathname + url.search })
    return route.fulfill({ response })
  })
  await page.goto(`${process.env.DASHBOARD_URL || 'http://127.0.0.1:4173'}/#/dns`)
  await page.getByRole('heading', { name: 'DNS و سیاست‌های دامنه' }).waitFor()
  await page.getByRole('button', { name: 'سیاست‌ها', exact: true }).click()
  await page.getByRole('button', { name: 'سیاست جدید', exact: true }).click()
  let dialog = page.getByRole('dialog')
  await dialog.locator('[name=name]').fill('Security')
  await dialog.getByRole('button', { name: 'افزودن قانون', exact: true }).click()
  await dialog.getByRole('textbox', { name: 'دامنه 1', exact: true }).fill('malware.example')
  await dialog.getByRole('button', { name: 'ذخیره سیاست', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  await page.getByText('Security', { exact: true }).waitFor()
  await page.getByRole('button', { name: 'حساب‌ها', exact: true }).click()
  await page.getByRole('button', { name: 'حساب DNS جدید', exact: true }).click()
  dialog = page.getByRole('dialog')
  await dialog.locator('[name=user_id]').selectOption('1')
  await dialog.locator('[name=policy_id]').selectOption('1')
  await dialog.locator('[name=query_limit]').fill('2')
  await dialog.getByRole('button', { name: 'ذخیره حساب', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  await page.getByRole('button').filter({ hasText: 'native-user' }).click()
  await page.getByRole('heading', { name: /جزئیات حساب/ }).waitFor()
  if (process.env.SCREENSHOT_DIR) {
    await mkdir(process.env.SCREENSHOT_DIR, { recursive: true })
    await page.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'native-dns-desktop.png'), fullPage: true })
    await page.getByRole('button', { name: 'تغییر پوسته', exact: true }).click()
    await page.getByRole('menuitem', { name: 'تیره', exact: true }).click()
    await page.locator('html.dark').waitFor()
    await page.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'native-dns-dark.png'), fullPage: true })
    await page.getByRole('button', { name: 'تغییر پوسته', exact: true }).click()
    await page.getByRole('menuitem', { name: 'روشن', exact: true }).click()
    await page.setViewportSize({ width: 390, height: 844 })
    await page.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'native-dns-mobile.png'), fullPage: true })
    await page.setViewportSize({ width: 1440, height: 1100 })
  }
  await page.getByRole('button', { name: 'نمایش آدرس خصوصی DoH', exact: true }).click()
  const input = page.getByRole('textbox', { name: 'DoH URL', exact: true })
  await input.waitFor()
  const url = new URL(await input.inputValue())
  // Standard DNS packet for blocked.example A; exact protocol response is decoded by Python unit tests.
  const query = Buffer.from('12340100000100000000000007626c6f636b6564076578616d706c650000010001', 'hex')
  for (const expected of [200, 200, 403]) {
    const response = await page.request.post(backend + url.pathname, { data: query, headers: { 'Content-Type': 'application/dns-message' } })
    assert.equal(response.status(), expected)
  }
  await page.getByRole('button', { name: 'تازه‌سازی', exact: true }).click()
  await page.getByText('پایان سهمیه', { exact: true }).waitFor()
  await page.getByRole('button', { name: 'دوره جدید', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'تأیید و اجرا', exact: true }).click()
  await page.getByRole('dialog').waitFor({ state: 'hidden' })
  await page.getByRole('textbox', { name: 'Client IP', exact: true }).fill('127.0.0.1')
  await page.getByRole('button', { name: 'ثبت IP', exact: true }).click()
  await page.getByText('127.0.0.1', { exact: true }).waitFor()
  for (const width of [390, 768]) {
    await page.setViewportSize({ width, height: 844 })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  }
  await page.goto(backend + '/preview/subscription')
  await page.locator('#copy-all').click()
  await page.getByRole('status').filter({ hasText: 'لینک با موفقیت کپی شد.' }).waitFor()
  assert.equal(await page.evaluate(() => navigator.clipboard.readText()), 'vless://example#Germany\ntrojan://example#Finland')
  assert.match(await page.locator('#dns-url').inputValue(), /\/dns-query\/pgdns_/)
  await page.locator('.qr-button').first().click()
  assert.equal(await page.locator('#qr-dialog').isVisible(), true)
  await page.keyboard.press('Escape')
  await page.goto(`${process.env.DASHBOARD_URL || 'http://127.0.0.1:4173'}/#/dns`)
  await page.getByRole('button').filter({ hasText: 'native-user' }).click()
  await page.getByRole('button', { name: 'ویرایش', exact: true }).click()
  dialog = page.getByRole('dialog')
  await dialog.locator('[name=enabled]').uncheck()
  await dialog.getByRole('button', { name: 'ذخیره حساب', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  assert.equal((await page.request.post(backend + url.pathname, { data: query, headers: { 'Content-Type': 'application/dns-message' } })).status(), 403)
  await page.getByRole('button', { name: 'کاتالوگ سرویس‌ها', exact: true }).click()
  await page.getByRole('heading', { name: 'PlayStation', exact: true }).waitFor()
  await page.getByRole('button', { name: 'رویدادها', exact: true }).click()
  await page.getByText('dns.reset', { exact: true }).waitFor()
  assert.deepEqual(errors, [])
  console.log('Passed: real native API, policy create, native user account, DoH budget exhaustion, period reset, IP lease, account disable, service catalog, audit and mobile layout.')
} finally {
  if (browser) await browser.close()
  processServer.kill('SIGTERM')
  await new Promise(resolve => (processServer.exitCode === null ? processServer.once('exit', resolve) : resolve()))
  await rm(temp, { recursive: true, force: true })
}
