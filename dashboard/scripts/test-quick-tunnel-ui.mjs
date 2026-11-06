import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, rm, mkdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const root = fileURLToPath(new URL('../../', import.meta.url))
const temporary = await mkdtemp(path.join(tmpdir(), 'quick-tunnel-ui-'))
const backend = 'http://127.0.0.1:8094'
const server = spawn(path.join(root, '.venv/bin/python'), [path.join(root, 'tests/fixtures/native_dns_server.py')], {
  cwd: root,
  env: { ...process.env, TEST_DNS_DB_PATH: path.join(temporary, 'test.db'), TEST_DNS_PORT: '8094', TEST_TUNNEL_HOST: '', TEST_NATIVE_OPERATIONS: '', TEST_NATIVE_PROVISION: '1' },
  stdio: ['ignore', 'pipe', 'pipe'],
})
let log = ''
for (const stream of [server.stdout, server.stderr])
  stream.on('data', bytes => {
    log += String(bytes)
  })
let browser
let frontend
const origin = process.env.DASHBOARD_URL || 'http://127.0.0.1:4184'
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
    frontend = spawn(process.execPath, [path.join(root, 'dashboard/node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '4184'], {
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
  // Phase 1 runs against the real API and database; phase 2 swaps in scripted wizard responses.
  let scripted = false
  const startBodies = []
  let polls = 0
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.includes('/src/')) return route.continue()
    if (scripted && url.pathname === '/api/tunnels/wizard/plan') {
      return route.fulfill(
        json({
          ok: true,
          blockers: [],
          warnings: ['firewall_not_managed_exit'],
          max_deployments: 6,
          candidates: [
            { id: 'paqet', label: 'Paqet (KCP over raw TCP)', compatible: true, reason: null },
            { id: 'gost-tls', label: 'GOST TLS', compatible: false, reason: 'node_adapter_incompatible:exit' },
          ],
        }),
      )
    }
    if (scripted && url.pathname === '/api/tunnels/wizard') {
      startBodies.push(route.request().postDataJSON())
      return route.fulfill({ ...json({ id: 'job1', kind: 'wizard', state: 'queued', progress: 0, result: {} }), status: 202 })
    }
    if (scripted && url.pathname === '/api/provision/jobs/job1') {
      polls += 1
      const steps = [
        {
          id: 'paqet',
          label: 'Paqet (KCP over raw TCP)',
          state: 'healthy',
          reason: null,
          score: 3.1,
          metrics: { tcp: true, udp: true, tcp_rtt_ms: 31, udp_rtt_ms: 33, udp_loss_percent: 0, sample_mbps: 42.5 },
        },
        { id: 'backhaul-tcp', label: 'Backhaul TCP', state: 'unhealthy', reason: 'measured', score: null, metrics: { tcp: true, udp: false } },
      ]
      if (polls < 2) return route.fulfill(json({ id: 'job1', kind: 'wizard', state: 'testing', progress: 40, result: { steps: [{ ...steps[0], state: 'measuring', metrics: null }] } }))
      return route.fulfill(json({ id: 'job1', kind: 'wizard', state: 'ready', progress: 100, result: { steps, winner: { id: 'paqet', label: 'Paqet (KCP over raw TCP)', tunnel_id: 7, score: 3.1 } } }))
    }
    const response = await route.fetch({ url: backend + url.pathname + url.search })
    await route.fulfill({ response })
  })
  await page.goto(`${origin}/#/tunnels`)
  await page.getByRole('heading', { name: 'Native node tunnels', exact: true }).waitFor()
  await page.getByRole('button', { name: 'Start quick tunnel', exact: true }).click()
  let dialog = page.getByRole('dialog')
  const check = dialog.getByRole('button', { name: 'Check first', exact: true })
  const run = dialog.getByRole('button', { name: 'Run', exact: true })
  assert(await check.isDisabled(), 'check needs source, exit and an inbound')
  await dialog.getByLabel('Source node (Iran)').selectOption({ label: 'Iran' })
  await dialog.getByLabel('Exit node (abroad)').selectOption({ label: 'Germany' })
  await dialog.getByRole('checkbox', { name: 'users', exact: true }).check()
  assert(await run.isDisabled())

  // Real API. Fixture nodes expose no native adapter, so only SS2022 (plain Xray) is compatible.
  await check.click()
  await dialog.getByText('SS2022 direct').waitFor()
  for (const label of ['Paqet (KCP over raw TCP)', 'Backhaul TCP', 'GOST TLS', 'FRP TCP']) {
    assert(await dialog.getByText(label).locator('xpath=..').getByText('Incompatible').isVisible(), `${label} must be incompatible`)
  }
  assert(await dialog.getByText('SS2022 direct').locator('xpath=..').getByText('Ready').isVisible())
  await dialog.getByText(/source firewall is not managed/).waitFor()
  assert(await run.isDisabled(), 'running needs explicit restart confirmation')
  assert.equal((await (await fetch(`${backend}/api/tunnels`)).json()).items.length, 0, 'planning must be read-only')
  assert.equal((await (await fetch(`${backend}/api/provision`)).json()).items.length, 0)

  // Real API blocker: an enabled tunnel already uses Iran as its source.
  const existing = await fetch(`${backend}/api/tunnels`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'Existing', source_node_id: 1, exit_node_id: 3, inbound_tags: ['users'], enabled: true }),
  })
  assert.equal(existing.status, 201, await existing.text())
  await check.click()
  await dialog.getByText(/source already has an active tunnel/).waitFor()
  assert.equal(await dialog.getByRole('checkbox', { name: /restarts Xray/ }).count(), 0, 'a blocked plan offers no confirmation')
  assert(await run.isDisabled(), 'a blocked plan must never be runnable')
  assert.equal((await (await fetch(`${backend}/api/provision`)).json()).items.length, 0)

  // Scripted: compatible plan, confirmation gate, live progress, comparison table and winner.
  scripted = true
  await check.click()
  await dialog.getByText(/exit firewall is not managed/).waitFor()
  await dialog.getByText('GOST TLS').waitFor()
  assert(await run.isDisabled(), 'running needs explicit restart confirmation')
  await dialog.getByRole('checkbox', { name: /restarts Xray on both nodes/ }).check()
  assert(await run.isEnabled())
  await page.setViewportSize({ width: 390, height: 844 })
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'mobile: plan view overflows')
  await page.setViewportSize({ width: 1440, height: 1050 })
  await run.click()
  await dialog.getByText('Testing carriers').waitFor()
  await dialog.getByText('Best path is active').waitFor({ timeout: 15000 })
  assert.equal(startBodies.length, 1)
  assert.deepEqual(startBodies[0], { source_node_id: 1, exit_node_id: 2, inbound_tags: ['users'], mode: 'fast', priority: 'balanced', confirm_restart: true })
  await dialog.getByRole('cell', { name: 'Paqet (KCP over raw TCP) ★' }).waitFor()
  await dialog.getByText('42.5').waitFor()
  await dialog.getByText(/other test paths were removed/).waitFor()
  if (process.env.SCREENSHOT_DIR) {
    await mkdir(process.env.SCREENSHOT_DIR, { recursive: true })
    await dialog.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, 'quick-tunnel-result.png') })
  }
  await page.setViewportSize({ width: 390, height: 844 })
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'mobile: result view overflows')
  await dialog.getByRole('button', { name: 'Close', exact: true }).first().click()
  await dialog.waitFor({ state: 'hidden' })
  assert.deepEqual(errors, [])
  console.log(
    'PASS: quick tunnel wizard — real-API plan is read-only, lists compatible vs incompatible carriers and blocks an in-use source; scripted-response flow covers confirmation gate, live progress, comparison table, winner and mobile layout (wizard job responses are scripted here; backend behavior is covered by tests/test_tunnel_wizard.py)',
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
