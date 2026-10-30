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
  env: { ...process.env, TEST_DNS_DB_PATH: path.join(temporary, 'test.db'), TEST_DNS_PORT: '8093', TEST_TUNNEL_HOST: '', TEST_NATIVE_OPERATIONS: '', TEST_NATIVE_PROVISION: '1' },
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
  page.on('pageerror', error => {
    errors.push(String(error))
    console.error(error)
  })
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
  await page.getByRole('button', { name: 'نصب خودکار روی سرور', exact: true }).click()
  let dialog = page.getByRole('dialog')
  await dialog.locator('[name=name]').fill('Automatic Iran')
  await dialog.getByLabel('IP سرور SSH', { exact: true }).fill('192.0.2.25')
  const secret = 'ui-fixture-private-password-42'
  await dialog.locator('[name=password]').fill(secret)
  assert(await dialog.getByRole('button', { name: 'شروع نصب خودکار روی این سرور', exact: true }).isDisabled())
  await dialog.getByRole('button', { name: 'بررسی هویت SSH سرور', exact: true }).click()
  await dialog.getByText(/^SHA256:/).waitFor()
  if (process.env.SCREENSHOT_DIR) {
    await mkdir(process.env.SCREENSHOT_DIR, { recursive: true })
    await dialog.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'native-provision-desktop.png') })
  }
  await dialog.getByRole('button', { name: 'شروع نصب خودکار روی این سرور', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  let state = await (await fetch(`${backend}/api/provision`)).json()
  assert.equal(state.items.length, 1)
  assert.equal(state.items[0].state, 'queued')
  assert(!JSON.stringify(state).includes(secret))
  assert(!JSON.stringify(state).includes('credential_cipher'))
  await page.getByRole('button', { name: 'لغو کار در صف', exact: true }).click()
  await page.getByText('لغو شد', { exact: true }).waitFor()
  await page.getByRole('button', { name: 'تونل جدید', exact: true }).click()
  dialog = page.getByRole('dialog')
  await dialog.locator('[name=transport]').selectOption('gost')
  await dialog.locator('[name=name]').fill('Automatic GOST')
  await dialog.locator('[name=source]').selectOption('1')
  await dialog.locator('[name=exit]').selectOption('2')
  await dialog.getByRole('checkbox', { name: 'users', exact: true }).check()
  await dialog.getByRole('button', { name: 'ذخیره و راه‌اندازی خودکار', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  state = await (await fetch(`${backend}/api/provision`)).json()
  assert(state.items.some(job => job.kind === 'activate' && job.state === 'queued'))
  const tunnels = await (await fetch(`${backend}/api/tunnels`)).json()
  assert(tunnels.items[0].enabled && tunnels.items[0].health_enabled)
  await page.setViewportSize({ width: 390, height: 844 })
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
  await page.getByRole('button', { name: 'نصب خودکار روی سرور', exact: true }).click()
  await page.getByRole('dialog').waitFor()
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
  if (process.env.SCREENSHOT_DIR) await page.getByRole('dialog').screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'native-provision-mobile.png') })
  assert.deepEqual(errors, [])
  console.log(
    'PASS: automatic installation wizard, fingerprint review, real persistent queue, credential privacy, queued cancel, automatic tunnel activation submission, mobile layout; SSH scan fixture only',
  )
} catch (error) {
  console.error(log)
  throw error
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
