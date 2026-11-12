import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { fetcher } from '@/service/http'

type Port = { port: number; protocol: string; bind?: string; purpose?: string; target?: string | null }
type Tunnel = {
  kind: string
  role?: string
  peer?: string | null
  listen?: string | null
  raw_port?: number | null
  ports: Port[]
  running?: boolean | null
  process?: { pid?: number | null; name?: string } | null
  unit?: { name: string; active?: string | null; enabled?: string | null }
  firewall?: { panel: number; foreign: number; chains: string[] } | null
  interface?: string | null
}
type Analysis = {
  tunnels: Tunnel[]
  proxies: { config_path: string; inbounds: { tag?: string; protocol?: string; port: number }[]; outbounds: { tag?: string; address: string; port: number }[] }[]
  links: { from: string; via: string; tag?: string; tunnel: number; port: number; purpose: string }[]
  dns_tunnels: { tunnel: number; port: number; target?: string | null; protocol: string }[]
  dns_listeners: { proto: string; bind: string; port: number; name?: string | null }[]
  reservations: (Port & { owner: string })[]
  warnings: { code: string; detail: string }[]
  conflicts: { code: string; port: number; panel: string; existing: string; action: string }[]
  node_registered: boolean
  safe_to_proceed: boolean
  privileged: boolean
}
type Reservation = { id: number; address: string; name: string; kind: string; role?: string | null; peer?: string | null; ports: Port[] }

const scanErrors: Record<string, string> = {
  ssh_host_key_changed: 'کلید SSH سرور تغییر کرده؛ هویت سرور را دوباره بررسی کنید.',
  ssh_authentication_failed: 'اطلاعات ورود SSH پذیرفته نشد.',
  ssh_connection_failed: 'اتصال SSH برقرار نشد.',
  python3_required: 'روی سرور python3 لازم است؛ چیزی نصب نشد.',
  root_or_passwordless_sudo_required: 'دسترسی root یا sudo بدون رمز لازم است.',
  collector_failed: 'بازرسی روی سرور کامل نشد.',
}
const tunnelOwners = ['paqet', 'backhaul', 'gost', 'frp']
const norm = (row: Port) => ({ port: row.port, protocol: row.protocol === 'udp' ? 'udp' : 'tcp', bind: (row.bind || '').slice(0, 64), purpose: (row.purpose || '').slice(0, 200) })

export default function TunnelDiscovery({ fa }: { fa: boolean }) {
  const t = (f: string, e: string) => (fa ? f : e)
  const client = useQueryClient()
  const reservations = useQuery({ queryKey: ['discovery-reservations'], queryFn: () => fetcher<{ items: Reservation[] }>('/api/tunnel/discovery/reservations') })
  const [open, setOpen] = useState(false)
  const [host, setHost] = useState('')
  const [port, setPort] = useState('22')
  const [method, setMethod] = useState('password')
  const [key, setKey] = useState<{ server_host_key: string; fingerprint: string } | null>(null)
  const [result, setResult] = useState<Analysis | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)

  const fail = (value: unknown) => {
    const detail = (value as { data?: { detail?: unknown } }).data?.detail
    setError(typeof detail === 'string' ? (fa && scanErrors[detail]) || detail : t('درخواست انجام نشد.', 'Request failed.'))
  }
  const reserve = async () => {
    if (!result) return
    setBusy(true)
    setError('')
    try {
      const items = result.tunnels
        .map((tunnel, index) => ({
          name: `${tunnel.kind}-${tunnel.role || 'unknown'}-${index + 1}`,
          kind: tunnel.kind,
          role: tunnel.role || null,
          peer: tunnel.peer || null,
          ports: [...tunnel.ports.map(norm), ...(tunnel.raw_port ? [norm({ port: tunnel.raw_port, protocol: 'tcp', purpose: 'raw port' })] : [])],
        }))
        .filter(item => item.ports.length)
      const others = result.reservations.filter(row => !tunnelOwners.includes(row.owner)).map(norm)
      if (others.length) items.push({ name: 'proxy-dns-firewall', kind: 'services', role: null, peer: null, ports: others })
      await fetcher('/api/tunnel/discovery/reservations', { method: 'POST', body: { address: host, items } })
      setSaved(true)
      client.invalidateQueries({ queryKey: ['discovery-reservations'] })
    } catch (value) {
      fail(value)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="bg-card space-y-4 rounded-2xl border p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="font-medium">{t('تشخیص تونل‌های موجود', 'Detect existing tunnels')}</h2>
          <p className="text-muted-foreground mt-2 max-w-3xl text-xs leading-6">
            {t(
              'فقط‌خواندنی است: چیزی روی سرور نصب، ذخیره یا ری‌استارت نمی‌شود. تونل Paqet، کانفیگ V2Ray/Xray، مسیر DNS و قوانین فایروال پیدا می‌شوند و پورت‌هایشان رزرو می‌شود تا پنل رویشان ننویسد.',
              'Read-only: nothing is installed, saved or restarted on the server. Finds Paqet tunnels, V2Ray/Xray configs, DNS paths and firewall rules, and reserves their ports so the panel never touches them.',
            )}
          </p>
        </div>
        <Button
          variant="outline"
          onClick={() => {
            setOpen(true)
            setKey(null)
            setResult(null)
            setError('')
            setSaved(false)
          }}
        >
          {t('بررسی یک سرور', 'Inspect a server')}
        </Button>
      </div>
      {!!reservations.data?.items.length && (
        <div className="space-y-2">
          <p className="text-xs font-medium">{t('پورت‌های رزروشده (پنل به آن‌ها دست نمی‌زند)', 'Reserved ports (the panel will not touch them)')}</p>
          {reservations.data.items.map(row => (
            <div key={row.id} className="bg-background flex flex-wrap items-center justify-between gap-2 rounded-xl border p-3 text-xs">
              <span dir="ltr">
                {row.address} · {row.name} · {row.ports.map(p => `${p.port}/${p.protocol}`).join(', ')}
              </span>
              <Button
                size="sm"
                variant="ghost"
                onClick={async () => {
                  await fetcher(`/api/tunnel/discovery/reservations/${row.id}`, { method: 'DELETE' })
                  client.invalidateQueries({ queryKey: ['discovery-reservations'] })
                }}
              >
                {t('آزاد‌سازی', 'Release')}
              </Button>
            </div>
          ))}
        </div>
      )}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{t('تشخیص تونل‌های موجود', 'Detect existing tunnels')}</DialogTitle>
            <DialogDescription>{t('دسترسی SSH فقط برای همین بررسی استفاده می‌شود و ذخیره نمی‌شود.', 'SSH access is used for this inspection only and is not stored.')}</DialogDescription>
          </DialogHeader>
          <form
            className="space-y-4"
            onSubmit={async event => {
              event.preventDefault()
              if (!key) return
              const form = new FormData(event.currentTarget)
              setBusy(true)
              setError('')
              setResult(null)
              setSaved(false)
              try {
                const body: Record<string, unknown> = { host, ssh_port: Number(port), username: form.get('username'), server_host_key: key.server_host_key }
                if (method === 'password') body.password = form.get('password')
                else {
                  body.private_key = form.get('private_key')
                  if (form.get('passphrase')) body.key_passphrase = form.get('passphrase')
                }
                setResult(await fetcher<Analysis>('/api/tunnel/discovery/scan', { method: 'POST', body }))
              } catch (value) {
                fail(value)
              } finally {
                setBusy(false)
              }
            }}
          >
            <div className="grid grid-cols-3 gap-3">
              <label className="col-span-2 space-y-2 text-sm">
                <span>{t('IP سرور', 'Server IP')}</span>
                <Input value={host} onChange={event => setHost(event.target.value.trim())} required dir="ltr" autoComplete="off" />
              </label>
              <label className="space-y-2 text-sm">
                <span>{t('پورت SSH', 'SSH port')}</span>
                <Input value={port} onChange={event => setPort(event.target.value)} required type="number" min={1} max={65535} />
              </label>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <label className="space-y-2 text-sm">
                <span>{t('کاربر SSH', 'SSH user')}</span>
                <Input name="username" required defaultValue="root" autoComplete="off" dir="ltr" />
              </label>
              <label className="space-y-2 text-sm">
                <span>{t('روش ورود', 'Authentication')}</span>
                <select aria-label={t('روش ورود', 'Authentication')} className="bg-background h-10 w-full rounded-md border px-3" value={method} onChange={event => setMethod(event.target.value)}>
                  <option value="password">{t('رمز عبور', 'Password')}</option>
                  <option value="key">{t('کلید خصوصی SSH', 'Private key')}</option>
                </select>
              </label>
            </div>
            {method === 'password' ? (
              <label className="block space-y-2 text-sm">
                <span>{t('رمز SSH', 'SSH password')}</span>
                <Input name="password" type="password" required autoComplete="new-password" />
              </label>
            ) : (
              <>
                <label className="block space-y-2 text-sm">
                  <span>{t('کلید خصوصی SSH', 'SSH private key')}</span>
                  <textarea name="private_key" required autoComplete="off" dir="ltr" className="bg-background min-h-24 w-full rounded-md border p-3 font-mono text-xs" />
                </label>
                <label className="block space-y-2 text-sm">
                  <span>{t('رمز کلید؛ اختیاری', 'Key passphrase; optional')}</span>
                  <Input name="passphrase" type="password" autoComplete="new-password" />
                </label>
              </>
            )}
            <div className="flex flex-wrap gap-2">
              <Button
                type="button"
                variant="outline"
                disabled={busy || !host || !port}
                onClick={async () => {
                  setBusy(true)
                  setError('')
                  setKey(null)
                  try {
                    setKey(await fetcher('/api/provision/inspect', { method: 'POST', body: { host, ssh_port: Number(port) } }))
                  } catch (value) {
                    fail(value)
                  } finally {
                    setBusy(false)
                  }
                }}
              >
                {t('۱. بررسی هویت SSH', '1. Check SSH identity')}
              </Button>
              <Button type="submit" disabled={busy || !key}>
                {busy ? t('در حال بررسی…', 'Inspecting…') : t('۲. شروع بررسی فقط‌خواندنی', '2. Run read-only inspection')}
              </Button>
            </div>
            {key && (
              <p className="bg-muted/30 rounded-xl border p-3 font-mono text-xs break-all" dir="ltr">
                {key.fingerprint}
              </p>
            )}
            {error && (
              <p role="alert" className="text-destructive text-sm leading-6">
                {error}
              </p>
            )}
          </form>
          {result && (
            <div className="space-y-4 text-sm" aria-live="polite">
              {result.conflicts.map(row => (
                <p key={`${row.code}-${row.port}`} role="alert" className="text-destructive rounded-xl border p-3 text-xs leading-6">
                  {t(`پورت ${row.port}: ${row.panel} با سرویس موجود (${row.existing}) تداخل دارد. `, `Port ${row.port}: ${row.panel} clashes with an existing service (${row.existing}). `)}
                  {row.action}
                </p>
              ))}
              {result.warnings.map(row => (
                <p key={row.code} className="rounded-xl border p-3 text-xs leading-6">
                  {row.detail}
                </p>
              ))}
              {result.tunnels.length === 0 && <p className="text-muted-foreground text-xs">{t('تونلی پیدا نشد.', 'No tunnel found.')}</p>}
              {result.tunnels.map((tunnel, index) => (
                <div key={index} className="bg-background space-y-2 rounded-xl border p-4">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <strong dir="ltr">
                      {tunnel.kind} · {tunnel.role || '?'}
                    </strong>
                    <span className="text-xs">{tunnel.running === false ? t('پردازش در حال اجرا نیست', 'not running') : tunnel.running ? t('در حال اجرا', 'running') : ''}</span>
                  </div>
                  <p className="text-muted-foreground text-xs leading-6" dir="ltr">
                    {tunnel.peer ? `peer ${tunnel.peer}` : tunnel.listen ? `listen ${tunnel.listen}` : ''}
                    {tunnel.interface ? ` · ${tunnel.interface}` : ''}
                    {tunnel.unit ? ` · ${tunnel.unit.name} (${tunnel.unit.active})` : ''}
                    {tunnel.firewall ? ` · firewall rules: ${tunnel.firewall.foreign} existing, ${tunnel.firewall.panel} panel` : ''}
                  </p>
                  <p className="text-xs leading-6" dir="ltr">
                    {[...tunnel.ports.map(row => `${row.port}/${row.protocol} ${row.purpose || ''}`), ...(tunnel.raw_port ? [`${tunnel.raw_port}/tcp raw`] : [])].join(' · ')}
                  </p>
                  {result.links
                    .filter(link => link.tunnel === index)
                    .map(link => (
                      <p key={`${link.from}-${link.port}-${link.via}`} className="text-xs" dir="ltr">
                        {link.via === 'outbound' ? '→' : '←'} {link.from} {link.tag ? `[${link.tag}]` : ''} :{link.port}
                      </p>
                    ))}
                  {result.dns_tunnels
                    .filter(row => row.tunnel === index)
                    .map(row => (
                      <p key={row.port} className="text-xs" dir="ltr">
                        DNS {row.protocol} :{row.port} → {row.target || '?'}
                      </p>
                    ))}
                </div>
              ))}
              {result.proxies.map(proxy => (
                <p key={proxy.config_path} className="text-xs leading-6" dir="ltr">
                  {proxy.config_path}: {proxy.inbounds.map(row => `in ${row.protocol}:${row.port}`).join(', ')}
                </p>
              ))}
              <div className="flex flex-wrap items-center gap-3">
                <Button type="button" onClick={reserve} disabled={busy || saved || !result.reservations.length}>
                  {saved ? t('رزرو شد', 'Reserved') : t(`رزرو ${result.reservations.length} پورت`, `Reserve ${result.reservations.length} ports`)}
                </Button>
                <span className="text-muted-foreground text-xs">
                  {t('رزرو فقط از دست‌کاری پنل جلوگیری می‌کند؛ تونل موجود تغییر نمی‌کند.', 'Reserving only stops the panel from using these ports; the existing tunnel is not changed.')}
                </span>
              </div>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </section>
  )
}
