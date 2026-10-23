// Actual native publication API and subscription rendering, disposable SQLite.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, rm, mkdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const root = fileURLToPath(new URL('../../', import.meta.url))
const temp = await mkdtemp(path.join(tmpdir(), 'tunnel-host-browser-'))
const port = process.env.TEST_DNS_PORT || '8088'
const backend = `http://127.0.0.1:${port}`
const server = spawn(path.join(root, '.venv/bin/python'), [path.join(root, 'tests/fixtures/native_dns_server.py')], {
  cwd: root,
  env: { ...process.env, TEST_DNS_DB_PATH: path.join(temp, 'test.db'), TEST_DNS_PORT: port, TEST_TUNNEL_HOST: '1' },
  stdio: ['ignore', 'pipe', 'pipe'],
})
let log = ''
server.stdout.on('data', b => {
  log += String(b)
})
server.stderr.on('data', b => {
  log += String(b)
})
let browser
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
  browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] })
  const page = await browser.newPage({ viewport: { width: 1440, height: 1050 } })
  const errors = []
  page.on('pageerror', e => errors.push(String(e)))
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
  await page.goto(`${process.env.DASHBOARD_URL || 'http://127.0.0.1:4173'}/#/hosts`)
  await page.getByRole('button', { name: 'افزودن ورودی تونل به سابلینک', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('مسیر تونل', { exact: true }).selectOption('1')
  assert.equal(await dialog.getByLabel('IP عمومی ورودی ایران', { exact: true }).inputValue(), '192.0.2.10')
  await dialog.getByLabel('هاست الگو', { exact: true }).selectOption('1')
  await dialog.getByLabel('نام در سابلینک', { exact: true }).fill('Iran entry via Germany')
  if (process.env.SCREENSHOT_DIR) {
    await mkdir(process.env.SCREENSHOT_DIR, { recursive: true })
    await page.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'tunnel-host-desktop.png'), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    assert(await dialog.evaluate(e => e.scrollWidth <= e.clientWidth + 1))
    await page.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'tunnel-host-mobile.png'), fullPage: true })
    await page.setViewportSize({ width: 1440, height: 1050 })
  }
  await dialog.getByRole('button', { name: 'ثبت هاست ورودی در سابلینک', exact: true }).click()
  await dialog.waitFor({ state: 'hidden', timeout: 10000 }).catch(async e => {
    console.log('Dialog:', await dialog.innerText())
    console.log('Server:', log.slice(-5000))
    throw e
  })
  const hosts = await (await page.request.get(`${backend}/api/hosts`)).json()
  assert.equal(hosts.length, 2)
  const host = hosts.find(h => h.native_tunnel_id === 1)
  assert.deepEqual(host.address, ['192.0.2.10'])
  assert.equal(host.port, 11080)
  assert.deepEqual(host.sni, ['vpn.example.com'])
  const links = await (await page.request.get(`${backend}/preview/tunnel-links`)).json()
  assert(links.links.includes('@192.0.2.10:11080'))
  assert(!links.links.includes(':40900'))
  // Repeat publication updates the same managed host.
  await page.getByRole('button', { name: 'افزودن ورودی تونل به سابلینک', exact: true }).click()
  await dialog.getByLabel('IP عمومی ورودی ایران', { exact: true }).fill('192.0.2.11')
  await dialog.getByRole('button', { name: 'به‌روزرسانی هاست ورودی', exact: true }).click()
  await dialog.waitFor({ state: 'hidden' })
  const again = await (await page.request.get(`${backend}/api/hosts`)).json()
  assert.equal(again.length, 2)
  assert.equal(again.find(h => h.native_tunnel_id === 1).id, host.id)
  assert.deepEqual(again.find(h => h.native_tunnel_id === 1).address, ['192.0.2.11'])
  // Actual tunnel edit removes the host from the next rendered subscription.
  const state = await (await page.request.get(`${backend}/api/tunnels`)).json()
  const item = state.items[0]
  const changed = await page.request.put(`${backend}/api/tunnels/1`, {
    data: {
      name: item.name,
      transport: item.transport,
      inbound_tags: item.inbound_tags,
      domains: item.domains,
      port: item.port,
      enabled: false,
      expected_revision: item.revision,
    },
  })
  assert.equal(changed.status(), 200, await changed.text())
  const disabledLinks = await (await page.request.get(`${backend}/preview/tunnel-links`)).json()
  assert(!disabledLinks.links.includes('192.0.2.11'))
  assert.equal(errors.length, 0, errors.join('\n'))
  console.log('Passed: actual host publication, automatic source IP/user port, preserved SNI, rendered subscription, idempotent update, disable removal and mobile layout.')
} finally {
  if (browser) await browser.close()
  server.kill('SIGTERM')
  await new Promise(resolve => server.once('exit', resolve))
  await rm(temp, { recursive: true, force: true })
}
