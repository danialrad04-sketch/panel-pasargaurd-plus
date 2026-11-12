import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const root = fileURLToPath(new URL('../../', import.meta.url))
const temporary = await mkdtemp(path.join(tmpdir(), 'tunnel-discovery-ui-'))
const backend = 'http://127.0.0.1:8096'
const server = spawn(path.join(root, '.venv/bin/python'), [path.join(root, 'tests/fixtures/native_dns_server.py')], {
  cwd: root,
  env: { ...process.env, TEST_DNS_DB_PATH: path.join(temporary, 'test.db'), TEST_DNS_PORT: '8096', TEST_TUNNEL_HOST: '', TEST_NATIVE_OPERATIONS: '', TEST_NATIVE_PROVISION: '1' },
  stdio: ['ignore', 'pipe', 'pipe'],
})
let log = ''
for (const stream of [server.stdout, server.stderr])
  stream.on('data', bytes => {
    log += String(bytes)
  })
let browser
let frontend
const origin = process.env.DASHBOARD_URL || 'http://127.0.0.1:4186'
const json = body => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
const clientYaml = `role: "client"
socks5:
  - listen: "127.0.0.1:1080"
forward:
  - listen: "127.0.0.1:5300"
    target: "127.0.0.1:53"
    protocol: "udp"
network:
  interface: "eth0"
  ipv4:
    addr: "198.51.100.7:0"
    router_mac: "aa:bb:cc:dd:ee:01"
server:
  addr: "203.0.113.9:9999"
`
const xray = JSON.stringify({ inbounds: [{ tag: 'users', protocol: 'vless', port: 443 }], outbounds: [{ tag: 'to-de', protocol: 'socks', settings: { servers: [{ address: '127.0.0.1', port: 1080 }] } }] })
const facts = {
  privileged: true,
  processes: [{ pid: 410, name: 'paqet', args: ['run', '-c', '/etc/paqet/client.yaml'] }],
  listeners: [{ proto: 'tcp', bind: '127.0.0.1', port: 1080, pid: 410, name: 'paqet' }],
  units: [{ name: 'paqet.service', exec_start: '/usr/local/bin/paqet run -c /etc/paqet/client.yaml', active: 'active', enabled: 'enabled' }],
  files: [
    { path: '/etc/paqet/client.yaml', text: clientYaml },
    { path: '/usr/local/etc/xray/config.json', text: xray },
  ],
  firewall: { iptables: '*raw\n-A OUTPUT -s 198.51.100.7/32 -d 203.0.113.9/32 -p tcp -m tcp --dport 9999 -j NOTRACK\nCOMMIT\n' },
}
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
  const analysis = await fetch(`${backend}/api/tunnel/discovery/analyze`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ address: '198.51.100.7', facts }) })
  const analysed = await analysis.json()
  assert.equal(analysis.status, 200, JSON.stringify(analysed))
  assert.equal(analysed.tunnels[0].kind, 'paqet')
  if (!process.env.DASHBOARD_URL) {
    frontend = spawn(process.execPath, [path.join(root, 'dashboard/node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '4186'], { cwd: path.join(root, 'dashboard'), stdio: ['ignore', 'pipe', 'pipe'] })
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
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } })
  const errors = []
  page.on('pageerror', error => errors.push(String(error)))
  let scanBody
  await page.addInitScript(() => {
    localStorage.setItem('token', 'local-test')
    localStorage.setItem('i18nextLng', 'en')
  })
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.includes('/src/')) return route.continue()
    if (url.pathname === '/api/provision/inspect') return route.fulfill(json({ host: '198.51.100.7', ssh_port: 22, server_host_key: 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIexample0000000000000000000000000000', fingerprint: 'SHA256:examplefingerprint' }))
    if (url.pathname === '/api/tunnel/discovery/scan') {
      scanBody = route.request().postDataJSON()
      return route.fulfill(json(analysed))
    }
    const response = await route.fetch({ url: backend + url.pathname + url.search })
    await route.fulfill({ response })
  })
  await page.goto(`${origin}/#/tunnels`)
  await page.getByRole('button', { name: 'Inspect a server' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Server IP').fill('198.51.100.7')
  await dialog.getByRole('button', { name: '1. Check SSH identity' }).click()
  await dialog.getByText('SHA256:examplefingerprint').waitFor()
  await dialog.locator('input[name="password"]').fill('not-a-real-password')
  await dialog.getByRole('button', { name: '2. Run read-only inspection' }).click()
  await dialog.getByText('paqet · client').waitFor()
  assert.equal(scanBody.host, '198.51.100.7')
  assert.equal(scanBody.password, 'not-a-real-password')
  const text = await dialog.innerText()
  assert.match(text, /peer 203\.0\.113\.9:9999/)
  assert.match(text, /1080\/tcp/)
  assert.match(text, /DNS udp :5300/)
  await page.screenshot({ path: process.env.SHOT || path.join(temporary, 'discovery.png') })
  await dialog.getByRole('button', { name: /Reserve \d+ ports/ }).click()
  await dialog.getByRole('button', { name: 'Reserved' }).waitFor()
  const listed = await (await fetch(`${backend}/api/tunnel/discovery/reservations`)).json()
  assert(listed.items.some(row => row.address === '198.51.100.7' && row.ports.some(p => p.port === 9999)))
  assert.deepEqual(errors, [])
  console.log('tunnel discovery ui ok')
} finally {
  await browser?.close()
  frontend?.kill()
  server.kill()
  await rm(temporary, { recursive: true, force: true })
}
