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
  await dialog.locator('[name=transport]').selectOption('paqet')
  await dialog.locator('[name=name]').fill('Paqet Iran → Germany')
  await dialog.locator('[name=source]').selectOption('1')
  await dialog.locator('[name=exit]').selectOption('2')
  await dialog.getByRole('checkbox', { name: 'users', exact: true }).check()
  await dialog.locator('[name=enabled]').check()
  await dialog.getByLabel('حالت KCP', { exact: true }).selectOption('manual')
  await dialog.locator('summary').filter({ hasText: 'KCP، FEC و SMUX' }).click()
  await dialog.getByLabel('قطعه داده FEC', { exact: true }).fill('10')
  await dialog.getByLabel('قطعه افزونگی FEC', { exact: true }).fill('3')
  await dialog.getByLabel('MTU', { exact: true }).fill('1100')
  await dialog.locator('summary').filter({ hasText: 'فوروارد TCP/UDP و SOCKS5 اضافی' }).click()
  await dialog.getByLabel('پذیرش مسیرهای اضافی بدون حسابداری کاربر', { exact: true }).check()
  await dialog.getByRole('button', { name: 'افزودن SOCKS5', exact: true }).click()
  await dialog.getByLabel('نام کاربری', { exact: true }).fill('browser-user')
  await dialog.getByLabel('رمز جدید؛ خالی = حفظ رمز فعلی', { exact: true }).fill('browser-socks-secret')
  await dialog.locator('[name=paqet_key]').fill('browser-paqet-private-key')
  await dialog.getByRole('button', { name: 'ذخیره مسیر', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  const card = page.locator('article').filter({ hasText: 'Paqet Iran → Germany' })
  await card.getByRole('heading', { name: 'Paqet Iran → Germany', exact: true }).waitFor()
  const data = await (await page.request.get(`${backend}/api/tunnels`)).json()
  assert.equal(data.items[0].transport, 'paqet')
  assert.equal(data.items[0].paqet_options.kcp.mode, 'manual')
  assert.equal(data.items[0].paqet_options.kcp.dshard, 10)
  assert.equal(data.items[0].paqet_options.kcp.pshard, 3)
  assert(!JSON.stringify(data).includes('browser-socks-secret'))
  assert(!JSON.stringify(data).includes('browser-paqet-private-key'))
  if (process.env.SCREENSHOT_DIR) {
    await mkdir(process.env.SCREENSHOT_DIR, { recursive: true })
    await page.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'native-paqet-desktop.png'), fullPage: true })
  }
  await card.getByRole('button', { name: 'ویرایش مسیر', exact: true }).click()
  dialog = page.getByRole('dialog')
  assert(await dialog.locator('[name=transport]').isDisabled())
  assert.equal(await dialog.locator('[name=paqet_key]').inputValue(), '')
  await dialog.locator('summary').filter({ hasText: 'JSON کامل تنظیمات' }).click()
  const config = await dialog.getByRole('textbox', { name: 'Paqet JSON', exact: true }).inputValue()
  assert(!config.includes('browser-socks-secret') && !config.includes('browser-paqet-private-key'))
  assert(!config.includes('authenticated'))
  await dialog.locator('summary').filter({ hasText: 'KCP، FEC و SMUX' }).click()
  await dialog.getByLabel('MTU', { exact: true }).fill('1000')
  if (process.env.SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'native-paqet-settings.png'), fullPage: true })
  await dialog.getByRole('button', { name: 'ذخیره مسیر', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  const updated = await (await page.request.get(`${backend}/api/tunnels`)).json()
  assert.equal(updated.items[0].paqet_options.kcp.mtu, 1000)
  assert.equal(updated.items[0].paqet_options.socks5[0].authenticated, true)
  await card.locator('summary').filter({ hasText: 'وضعیت، ابزارها و لاگ Paqet' }).click()
  await card.getByRole('button', { name: 'دریافت وضعیت دو نود', exact: true }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByRole('heading', { name: 'وضعیت اجرای Paqet', exact: true }).waitFor()
  assert((await dialog.locator('pre').innerText()).includes('"available": false'))
  await page.keyboard.press('Escape')
  await dialog.waitFor({ state: 'hidden' })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.waitForTimeout(200)
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
  if (process.env.SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'native-paqet-mobile.png'), fullPage: true })
  assert.deepEqual(errors, [])
  console.log('Passed: real Paqet API, all-field editor, KCP/FEC, SOCKS credential preservation/redaction, immutable transport, unavailable-node status and mobile layout.')
} finally {
  if (browser) await browser.close()
  processServer.kill('SIGTERM')
  await new Promise(resolve => (processServer.exitCode === null ? processServer.once('exit', resolve) : resolve()))
  await rm(temp, { recursive: true, force: true })
}
