import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, rm, mkdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const root = fileURLToPath(new URL('../../', import.meta.url))
const temporary = await mkdtemp(path.join(tmpdir(), 'gaming-dns-ui-'))
const backend = 'http://127.0.0.1:8095'
const server = spawn(path.join(root, '.venv/bin/python'), [path.join(root, 'tests/fixtures/native_dns_server.py')], {
  cwd: root,
  env: { ...process.env, TEST_DNS_DB_PATH: path.join(temporary, 'test.db'), TEST_DNS_PORT: '8095', TEST_TUNNEL_HOST: '', TEST_NATIVE_OPERATIONS: '', TEST_NATIVE_PROVISION: '1' },
  stdio: ['ignore', 'pipe', 'pipe'],
})
let log = ''
for (const stream of [server.stdout, server.stderr])
  stream.on('data', bytes => {
    log += String(bytes)
  })
let browser
let frontend
const origin = process.env.DASHBOARD_URL || 'http://127.0.0.1:4185'
const json = body => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
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
  if (!process.env.DASHBOARD_URL) {
    frontend = spawn(process.execPath, [path.join(root, 'dashboard/node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '4185'], {
      cwd: path.join(root, 'dashboard'),
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    for (const stream of [frontend.stdout, frontend.stderr])
      stream.on('data', bytes => {
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
  browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH, args: ['--no-sandbox'] } : { args: ['--no-sandbox'] })
  const page = await browser.newPage({ viewport: { width: 1440, height: 1050 } })
  const errors = []
  page.on('pageerror', error => errors.push(String(error)))
  await page.addInitScript(() => {
    localStorage.setItem('token', 'local-test')
    localStorage.setItem('i18nextLng', 'en')
  })
  // Phase 1 runs against the real API and database; phase 2 scripts the success path.
  let scripted = false
  const posted = { plan: [], create: [], attach: [] }
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.includes('/src/')) return route.continue()
    if (scripted && url.pathname === '/api/smartdns/gaming/plan') {
      posted.plan.push(route.request().postDataJSON())
      return route.fulfill(
        json({
          ok: true,
          blockers: [],
          warnings: ['direct_egress', 'udp_not_covered'],
          routes: 14,
          policy: { name: 'Gaming DNS', domains: 31 },
          coverage: [{ service: 'playstation', label: 'PlayStation', domains: 12, routes: 6 }],
          dns: { will_enable: true, bind_address: '0.0.0.0', port: 53 },
        }),
      )
    }
    if (scripted && url.pathname === '/api/smartdns/gaming' && route.request().method() === 'POST') {
      posted.create.push(route.request().postDataJSON())
      return route.fulfill({ ...json({ policy_id: 9, node_id: 1, route_ids: [1, 2, 3], revision: 2, applied: true, detail: null }), status: 201 })
    }
    if (scripted && url.pathname === '/api/smartdns/gaming/9/attach') {
      posted.attach.push(route.request().postDataJSON())
      return route.fulfill(json({ account_id: 4, policy_id: 9, leases: [], byte_limit: 0, portal_path: '/dns-sub/abc123' }))
    }
    if (scripted && url.pathname === '/api/users/simple') return route.fulfill(json({ users: [{ id: 5, username: 'player' }] }))
    const response = await route.fetch({ url: backend + url.pathname + url.search })
    await route.fulfill({ response })
  })
  await page.goto(`${origin}/#/smartdns`)
  await page.getByRole('heading', { name: /Gaming DNS/ }).waitFor()
  await page.getByRole('button', { name: 'New gaming profile', exact: true }).click()
  const dialog = page.getByRole('dialog')
  const check = dialog.getByRole('button', { name: 'Check first', exact: true })
  const create = dialog.getByRole('button', { name: 'Create and apply', exact: true })
  assert(await check.isDisabled(), 'check needs node and address')
  await dialog.getByLabel('Gateway node').selectOption({ label: 'Germany' })
  await dialog.getByPlaceholder('203.0.113.10').fill('203.0.113.10')
  await dialog.getByRole('checkbox', { name: 'PlayStation', exact: true }).waitFor()
  assert(await dialog.getByRole('checkbox', { name: 'PlayStation', exact: true }).isChecked())
  assert(!(await dialog.getByRole('checkbox', { name: 'Roblox', exact: true }).isChecked()))
  assert(await create.isDisabled())

  const policiesBefore = (await (await fetch(`${backend}/api/dns/policies`)).json()).length
  // Real API: the fixture nodes run no adapter and no panel URL is configured, so the plan must refuse.
  await check.click()
  await dialog.getByText(/SMARTDNS_PANEL_URL/).waitFor()
  await dialog.getByText(/SmartDNS adapter does not answer/).waitFor()
  assert(await dialog.getByRole('checkbox', { name: /gateway configuration/ }).count() === 0, 'a blocked plan offers no confirmation')
  assert(await create.isDisabled(), 'a blocked plan must never be creatable')
  assert.equal((await (await fetch(`${backend}/api/dns/policies`)).json()).length, policiesBefore, 'planning must be read-only')
  assert.equal((await (await fetch(`${backend}/api/smartdns`)).json()).routes.length, 0)

  // Scripted: plan, honest warnings, confirmation gate, create, attach.
  scripted = true
  await check.click()
  await dialog.getByText(/31 domains in 14 gateway routes/).waitFor()
  await dialog.getByText(/Game UDP traffic is not covered/).waitFor()
  await dialog.getByText(/does not bypass sanctions/).waitFor()
  assert(await create.isDisabled(), 'creating needs explicit confirmation')
  await dialog.getByRole('checkbox', { name: /gateway configuration/ }).check()
  await page.setViewportSize({ width: 390, height: 844 })
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'mobile: plan view overflows')
  await page.setViewportSize({ width: 1440, height: 1050 })
  await create.click()
  await dialog.getByText(/Profile created and applied to the node \(3 routes\)/).waitFor()
  assert.equal(posted.create.length, 1)
  assert.deepEqual(posted.create[0], { node_id: 2, name: 'Gaming DNS', public_address: '203.0.113.10', services: ['playstation', 'xbox', 'steam'], confirm_apply: true })
  const attach = dialog.getByRole('button', { name: 'Attach user', exact: true })
  assert(await attach.isDisabled())
  await dialog.getByLabel(/^User/).selectOption({ label: 'player' })
  await dialog.getByPlaceholder('198.51.100.7').fill('198.51.100.7, 198.51.100.8')
  await dialog.getByLabel(/Byte cap/).fill('2.5')
  await attach.click()
  await dialog.getByText('User attached:').waitFor()
  assert.equal(await dialog.getByLabel('DNS link').inputValue(), origin + '/dns-sub/abc123')
  assert.deepEqual(posted.attach[0], { user_id: 5, client_ips: ['198.51.100.7', '198.51.100.8'], byte_limit: Math.round(2.5 * 1024 ** 3) })
  if (process.env.SCREENSHOT_DIR) {
    await mkdir(process.env.SCREENSHOT_DIR, { recursive: true })
    await dialog.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'gaming-dns-result.png') })
  }
  await page.setViewportSize({ width: 390, height: 844 })
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'mobile: result view overflows')
  await dialog.getByRole('button', { name: 'Close', exact: true }).first().click()
  await dialog.waitFor({ state: 'hidden' })
  assert.deepEqual(errors, [])
  console.log('PASS: gaming DNS wizard — real-API plan is read-only and refuses without panel URL/adapter; scripted flow covers warnings, confirmation gate, create payload, user attach and mobile layout (backend behavior is covered by tests/test_gaming_dns.py)')
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
