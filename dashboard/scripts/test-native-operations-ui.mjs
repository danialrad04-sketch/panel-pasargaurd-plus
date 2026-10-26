import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const root = fileURLToPath(new URL('../../', import.meta.url))
const temporary = await mkdtemp(path.join(tmpdir(), 'native-operations-ui-'))
const backend = 'http://127.0.0.1:8089'
const server = spawn(path.join(root, '.venv/bin/python'), [path.join(root, 'tests/fixtures/native_dns_server.py')], {
  cwd: root,
  env: { ...process.env, TEST_DNS_DB_PATH: path.join(temporary, 'test.db'), TEST_DNS_PORT: '8089', TEST_TUNNEL_HOST: '1', TEST_NATIVE_OPERATIONS: '1' },
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
const origin = process.env.DASHBOARD_URL || 'http://127.0.0.1:4179'
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
    frontend = spawn(process.execPath, [path.join(root, 'dashboard/node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '4179'], {
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
  await page.goto(`${origin}/#/smartdns`)
  await page.getByRole('heading', { name: 'دسترسی سرویس‌ها بدون VPN', exact: true }).waitFor()
  await page.getByRole('button', { name: 'gateway جدید', exact: true }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByLabel('نود gateway', { exact: true }).selectOption('1')
  await dialog.getByLabel('سیاست DNS', { exact: true }).selectOption('1')
  await dialog.getByLabel('دامنهٔ سرویس', { exact: true }).fill('game.example.com')
  await dialog.getByLabel('IP قابل دسترس کاربران', { exact: true }).fill('192.0.2.10')
  await dialog.getByLabel('پورت‌های TCP؛ جدا با کاما', { exact: true }).fill('8444')
  await dialog.getByLabel('پورت‌های UDP؛ مقصد دقیق', { exact: true }).fill('3074')
  await dialog.getByLabel('خروج ترافیک', { exact: true }).selectOption('1')
  await dialog.getByRole('button', { name: 'ذخیره gateway', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  const state = await (await fetch(`${backend}/api/smartdns`)).json()
  assert.equal(state.routes[0].egress_tunnel_id, 1)
  assert.deepEqual(state.routes[0].udp_ports, [3074])
  assert(!JSON.stringify(state).includes('password'))
  await page.getByRole('button', { name: 'دسترسی و سهمیه', exact: true }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('اجازهٔ عبور ترافیک از gateway', { exact: true }).check()
  await dialog.getByLabel('سقف حجم؛ GiB، صفر نامحدود', { exact: true }).fill('1.5')
  await dialog.getByRole('button', { name: 'ذخیره سهمیه', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  const accounts = await (await fetch(`${backend}/api/dns/accounts`)).json()
  assert.equal(accounts.items[0].relay_byte_limit, 1610612736)
  assert.equal(accounts.items[0].relay_enabled, true)
  await page.getByRole('button', { name: 'تنظیم DNS نود', exact: true }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('نود DNS', { exact: true }).selectOption('1')
  await dialog.getByLabel('فعال‌سازی DNS روی این نود', { exact: true }).check()
  await dialog.getByLabel('پورت DNS؛ UDP و TCP', { exact: true }).fill('5354')
  await dialog.getByRole('button', { name: 'ذخیره DNS نود', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  const dnsState = (await (await fetch(`${backend}/api/smartdns`)).json()).nodes.find(node => node.node_id === 1)
  assert.equal(dnsState.dns.enabled, true)
  assert.equal(dnsState.dns.port, 5354)
  assert.notEqual(dnsState.revision, dnsState.applied_revision)
  await page.goto(`${origin}/#/tunnels`)
  await page.locator('summary').filter({ hasText: 'پایش مسیر، بازیابی و سلامت سرویس‌ها' }).click()
  const probeDone = page.waitForResponse(response => response.url().endsWith('/health') && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'سنجش TCP/UDP', exact: true }).first().click()
  await probeDone
  await page.getByText('نتیجهٔ معتبر فعلی موجود نیست', { exact: false }).first().waitFor()
  const measured = await (await fetch(`${backend}/api/tunnels/health/state`)).json()
  assert.equal(measured[0].result.status, 'unavailable')
  assert.equal(measured[0].failures, 0, 'Control-plane failure must not trigger traffic failover')
  await page.getByRole('button', { name: 'سیاست جدید', exact: true }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('نود مبدا', { exact: true }).selectOption('1')
  await dialog.getByRole('checkbox', { name: 'Iran → Germany', exact: true }).check()
  await dialog.getByRole('checkbox', { name: 'Standby Finland', exact: true }).check()
  await dialog.getByRole('button', { name: 'ذخیره سیاست', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  assert.equal((await (await fetch(`${backend}/api/tunnels/failover/state`)).json())[0].enabled, false)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(`${origin}/#/smartdns`)
  await page.getByRole('heading', { name: 'دسترسی سرویس‌ها بدون VPN', exact: true }).waitFor()
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
  assert.deepEqual(errors, [])
  console.log('PASS: real-API SmartDNS route/tunnel selection, native account byte budget, unavailable health stays unknown, failover policy, mobile layout')
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
