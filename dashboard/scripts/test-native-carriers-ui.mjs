import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, rm, mkdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const root = fileURLToPath(new URL('../../', import.meta.url))
const temporary = await mkdtemp(path.join(tmpdir(), 'native-carriers-ui-'))
const backend = 'http://127.0.0.1:8093'
const server = spawn(path.join(root, '.venv/bin/python'), [path.join(root, 'tests/fixtures/native_dns_server.py')], {
  cwd: root,
  env: { ...process.env, TEST_DNS_DB_PATH: path.join(temporary, 'test.db'), TEST_DNS_PORT: '8093', TEST_TUNNEL_HOST: '', TEST_NATIVE_OPERATIONS: '' },
  stdio: ['ignore', 'pipe', 'pipe'],
})
let log = ''
server.stdout.on('data', bytes => {
  log += String(bytes)
})
server.stderr.on('data', bytes => {
  log += String(bytes)
})
let browser
let frontend
const origin = process.env.DASHBOARD_URL || 'http://127.0.0.1:4183'
try {
  let ready = false
  for (let i = 0; i < 300; i++) {
    try {
      ready = (await fetch(`${backend}/api/admin`)).ok
    } catch {}
    if (ready) break
    if (server.exitCode !== null) throw new Error(log)
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  assert(ready, log)
  const account = await fetch(`${backend}/api/dns/accounts`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ user_id: 1, policy_id: 1 }) })
  assert.equal(account.status, 201, await account.text())
  if (!process.env.DASHBOARD_URL) {
    frontend = spawn(process.execPath, [path.join(root, 'dashboard/node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '4183'], {
      cwd: path.join(root, 'dashboard'),
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    frontend.stdout.on('data', bytes => {
      log += String(bytes)
    })
    frontend.stderr.on('data', bytes => {
      log += String(bytes)
    })
    let started = false
    for (let i = 0; i < 150; i++) {
      try {
        started = (await fetch(origin)).ok
      } catch {}
      if (started) break
      if (frontend.exitCode !== null) throw new Error(log)
      await new Promise(resolve => setTimeout(resolve, 100))
    }
    assert(started, log)
  }
  browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] })
  const page = await browser.newPage({ viewport: { width: 1440, height: 1050 } })
  const errors = []
  page.on('pageerror', error => errors.push(String(error)))
  await page.addInitScript(() => {
    localStorage.setItem('token', 'local-test')
    localStorage.setItem('i18nextLng', 'fa')
  })
  await page.route('**/api/**', async route => {
    if (route.request().url().includes('/src/')) return route.continue()
    const url = new URL(route.request().url())
    const response = await route.fetch({ url: backend + url.pathname + url.search })
    await route.fulfill({ response })
  })
  await page.goto(`${origin}/#/tunnels`)
  await page.getByRole('heading', { name: 'تونل‌های بومی نودها', exact: true }).waitFor()
  for (const [engine, profile] of [
    ['backhaul', 'tcp'],
    ['gost', 'tls'],
    ['frp', 'quic'],
  ]) {
    await page.getByRole('button', { name: 'تونل جدید', exact: true }).click()
    const dialog = page.getByRole('dialog')
    assert.equal(await dialog.locator('[name=transport]').inputValue(), 'paqet')
    await dialog.locator('[name=transport]').selectOption(engine)
    await dialog.locator('[name=name]').fill(`Native ${engine}`)
    await dialog.locator('[name=source]').selectOption('1')
    await dialog.locator('[name=exit]').selectOption('2')
    await dialog.getByRole('checkbox', { name: 'users', exact: true }).check()
    await dialog.getByLabel('پروفایل موتور', { exact: true }).selectOption(profile)
    await dialog.locator('[name=carrier_key]').fill('browser-native-carrier-private-token-0123456789')
    await dialog.getByRole('button', { name: 'ذخیره مسیر', exact: true }).click()
    await dialog.waitFor({ state: 'hidden' })
    const state = await (await fetch(`${backend}/api/tunnels`)).json()
    const item = state.items.find(item => item.transport === engine)
    assert.equal(item.carrier_options.profile, profile)
    assert(!JSON.stringify(state).includes('browser-native-carrier-private-token'))
    assert(!JSON.stringify(state).includes('carrier_key'))
  }
  const card = page.locator('article').filter({ hasText: 'Native frp' })
  await card.getByRole('button', { name: 'ویرایش مسیر', exact: true }).click()
  let dialog = page.getByRole('dialog')
  assert(await dialog.locator('[name=transport]').isDisabled())
  assert.equal(await dialog.locator('[name=carrier_key]').inputValue(), '')
  await dialog.getByLabel('اندازهٔ connection pool', { exact: true }).fill('17')
  await dialog.getByRole('button', { name: 'ذخیره مسیر', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  assert.equal((await (await fetch(`${backend}/api/tunnels`)).json()).items.find(item => item.transport === 'frp').carrier_options.pool, 17)
  await card.locator('summary').filter({ hasText: 'وضعیت و کنترل موتور' }).click()
  await card.getByRole('button', { name: 'وضعیت موتور', exact: true }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByRole('heading', { name: 'frp', exact: true }).waitFor()
  assert((await dialog.locator('pre').innerText()).includes('"available": false'))
  await page.keyboard.press('Escape')
  await dialog.waitFor({ state: 'hidden' })
  await page.locator('summary').filter({ hasText: 'هستهٔ تونل · Paqet و موتورهای مکمل' }).click()
  await page.getByLabel('مبدا برای بررسی موتور', { exact: true }).selectOption('1')
  await page.getByLabel('خروجی برای بررسی موتور', { exact: true }).selectOption('2')
  const done = page.waitForResponse(response => response.url().endsWith('/nodes/2/engines'))
  await page.getByRole('button', { name: 'بررسی موتورهای دو نود', exact: true }).click()
  await done
  await page.getByText('ارتقا یا بررسی نود لازم است', { exact: true }).first().waitFor()
  if (process.env.SCREENSHOT_DIR) {
    await mkdir(process.env.SCREENSHOT_DIR, { recursive: true })
    await page.locator('details').filter({ hasText: 'هستهٔ تونل · Paqet و موتورهای مکمل' }).screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'native-engine-core-desktop.png') })
  }
  await page.setViewportSize({ width: 390, height: 844 })
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
  if (process.env.SCREENSHOT_DIR) await page.locator('details').filter({ hasText: 'هستهٔ تونل · Paqet و موتورهای مکمل' }).screenshot({ style: '[class~=sticky] { visibility: hidden }', path: path.join(process.env.SCREENSHOT_DIR, 'native-engine-core-mobile.png') })
  assert.deepEqual(errors, [])
  console.log('PASS: Paqet primary default, three native engine forms and real API, profiles, write-only keys, edit preservation, runtime and capability failures, mobile layout')
} finally {
  if (browser) await browser.close()
  if (frontend) {
    frontend.kill('SIGTERM')
    await new Promise(resolve => frontend.once('exit', resolve))
  }
  server.kill('SIGTERM')
  await new Promise(resolve => server.once('exit', resolve))
  await rm(temporary, { recursive: true, force: true })
}
