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
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.includes('/src/')) return route.continue()
    if (url.pathname === '/api/smartdns/gaming/profiles') return route.fulfill(json({ items: [{ policy_id: 9, name: 'Gaming DNS', routes: 14, applied: true, accounts: 0, address: '185.10.20.30' }] }))
    const response = await route.fetch({ url: backend + url.pathname + url.search })
    await route.fulfill({ response })
  })
  await page.goto(`${origin}/#/users`)
  await page
    .getByRole('button', { name: /create user/i })
    .first()
    .click()
  const dialog = page.getByRole('dialog')
  const section = dialog.locator('.gaming-dns-section')
  await section.waitFor()
  assert.match(await section.innerText(), /Gaming DNS/)
  await dialog.getByPlaceholder('Enter username').fill('player_one')
  await section.getByRole('switch', { name: /Enable gaming DNS/i }).click()
  await section.locator('select').waitFor()
  assert.equal(await section.locator('select').inputValue(), '9')
  const inputs = await section.locator('input').count()
  assert(inputs >= 3, 'cap, speed and ip inputs are shown')
  await page.screenshot({ path: process.env.SHOT || path.join(temporary, 'user-form.png') })
  assert.deepEqual(errors, [])
  console.log('user form DNS section ok')
} finally {
  await browser?.close()
  frontend?.kill()
  server.kill()
  await rm(temporary, { recursive: true, force: true })
}
