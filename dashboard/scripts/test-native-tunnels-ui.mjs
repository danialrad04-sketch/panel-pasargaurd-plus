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
  await page.goto(`${process.env.DASHBOARD_URL || 'http://127.0.0.1:4173'}/#/tunnels`)
  await page.getByRole('heading', { name: 'تونل‌های بومی نودها', exact: true }).waitFor()
  await page.getByRole('button', { name: 'تونل جدید', exact: true }).click()
  let dialog = page.getByRole('dialog')
  await dialog.locator('[name=name]').fill('Iran → Germany')
  await dialog.locator('[name=source]').selectOption('1')
  await dialog.locator('[name=exit]').selectOption('2')
  await dialog.getByRole('checkbox', { name: 'users', exact: true }).check()
  await dialog.locator('[name=enabled]').check()
  await dialog.locator('summary').click()
  await dialog.getByPlaceholder('جستجوی سرویس').fill('playstation')
  await dialog.getByRole('button', { name: 'افزودن دامنه‌ها', exact: true }).click()
  assert((await dialog.locator('[name=domains]').inputValue()).includes('playstation.com'))
  await dialog.getByRole('button', { name: 'ذخیره مسیر', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  const card = page.locator('article').filter({ hasText: 'Iran → Germany' })
  await card.getByRole('heading', { name: 'Iran → Germany', exact: true }).waitFor()
  await card.getByText('در انتظار اعمال', { exact: true }).waitFor()
  const initial = await (await page.request.get(`${backend}/api/tunnels`)).json()
  assert.equal(initial.items.length, 1)
  assert.equal(initial.items[0].transport, 'shadowsocks-2022')
  assert(!JSON.stringify(initial).includes('shared_key'))
  assert.deepEqual(initial.items[0].networks, ['tcp', 'udp'])
  if (process.env.SCREENSHOT_DIR) {
    await mkdir(process.env.SCREENSHOT_DIR, { recursive: true })
    await page.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'native-tunnels-desktop.png'), fullPage: true })
    await page.getByRole('button', { name: 'تغییر پوسته', exact: true }).click()
    await page.getByRole('menuitem', { name: 'تیره', exact: true }).click()
    await page.locator('html.dark').waitFor()
    await page.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'native-tunnels-dark.png'), fullPage: true })
  }
  await card.getByRole('button', { name: 'ویرایش مسیر', exact: true }).click()
  dialog = page.getByRole('dialog')
  assert(await dialog.locator('[name=source]').isDisabled())
  await dialog.locator('[name=domains]').fill('')
  await dialog.getByRole('button', { name: 'ذخیره مسیر', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  await card.getByText('تمام مقصدهای ورودی‌های منتخب', { exact: true }).waitFor()
  await card.getByRole('button', { name: 'تعویض کلید', exact: true }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: 'تأیید و اجرا', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  assert.equal((await (await page.request.get(`${backend}/api/tunnels`)).json()).items[0].revision, 3)
  assert(await card.getByRole('button', { name: 'حذف', exact: true }).isDisabled())
  // Intentionally invalid node credentials: real API must report partial failure.
  await page.getByRole('button', { name: /اعمال روی نودها/ }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: 'تأیید و اجرا', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  await page.getByText('partial_failure', { exact: true }).waitFor()
  const after = await (await page.request.get(`${backend}/api/tunnels`)).json()
  assert.equal(after.items[0].applied_revision, 0)
  assert.equal(after.last_result.ok, false)
  assert.deepEqual(after.last_result.nodes, [{ node_id: 2, ok: false }])
  await page.setViewportSize({ width: 390, height: 844 })
  await page.waitForTimeout(300)
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
  if (process.env.SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'native-tunnels-mobile.png'), fullPage: true })
  assert.deepEqual(errors, [])
  console.log('Passed: real native API, create path, catalog, edit, key rotation, guarded deletion, partial failure, theme and mobile layout.')
} finally {
  if (browser) await browser.close()
  processServer.kill('SIGTERM')
  await new Promise(resolve => (processServer.exitCode === null ? processServer.once('exit', resolve) : resolve()))
  await rm(temp, { recursive: true, force: true })
}
