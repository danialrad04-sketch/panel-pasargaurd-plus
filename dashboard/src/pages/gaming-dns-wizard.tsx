import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Gamepad2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { fetcher } from '@/service/http'
import type { TunnelView, TunnelNodeOption } from '@/service/api'

type Blocker = { code: string; detail?: string | null }
type Plan = {
  ok: boolean
  blockers: Blocker[]
  warnings: string[]
  routes?: number
  policy?: { name: string; domains: number }
  coverage?: { service: string; label: string; domains: number; routes: number }[]
  dns?: { will_enable: boolean; bind_address: string; port: number }
}
type Applied = { policy_id: number; node_id: number; route_ids: number[]; revision: number; applied: boolean; detail?: string | null }
type Catalog = { services: { key: string; label: string }[]; presets?: { key: string; label: [string, string]; services: string[] }[] }
type Profile = { policy_id: number; name: string; node_id: number; routes: number; applied: boolean; accounts: number; address: string }

const GAMING = [
  'playstation',
  'xbox',
  'nintendo',
  'steam',
  'epic',
  'ea',
  'blizzard',
  'ubisoft',
  'riot',
  'rockstar',
  'bethesda',
  'gog',
  'roblox',
  'minecraft',
  'othergames',
  'activision',
  'valvegames',
  'supercell',
  'hoyoverse',
  'garena',
  'pubgmobile',
  'mmo',
  'survival',
  'wargaming',
  'bandainamco',
  'bethesdagames',
  'amongus',
  'streaming_games',
  'anticheat',
]
const blockers: Record<string, [string, string]> = {
  node_not_found: ['نود پیدا نشد.', 'Node not found.'],
  node_not_connected: ['نود متصل نیست.', 'The node is not connected.'],
  unknown_service: ['سرویس ناشناخته در فهرست.', 'Unknown service in the selection.'],
  policy_name_taken: ['این نام پروفایل قبلاً استفاده شده است.', 'This profile name is already used.'],
  route_limit: ['ظرفیت ۲۵۶ مسیر gateway کافی نیست؛ مسیرهای بلااستفاده را حذف کنید.', 'Not enough room under the 256 gateway-route limit. Remove unused routes.'],
  panel_url_missing: [
    'آدرس HTTPS پنل (SMARTDNS_PANEL_URL) تنظیم نشده است. بدون دامنه: روی سرور دستور «pasarguard-plus https-ip» را اجرا کنید.',
    'The panel HTTPS origin (SMARTDNS_PANEL_URL) is not set. Without a domain, run “pasarguard-plus https-ip” on the server.',
  ],
  node_adapter_unavailable: ['افزونهٔ SmartDNS روی نود پاسخ نمی‌دهد؛ نصب خودکار نود را اجرا کنید.', 'The SmartDNS adapter does not answer on the node. Run the automatic node install.'],
  node_adapter_incompatible: ['نسخهٔ افزونهٔ SmartDNS نود سازگار نیست؛ نود را به‌روزرسانی کنید.', 'The node SmartDNS adapter is not compatible. Update the node.'],
  egress_not_found: ['تونل خروجی پیدا نشد.', 'Egress tunnel not found.'],
  egress_wrong_source: ['تونل خروجی باید از همین نود gateway شروع شود.', 'The egress tunnel must start on the gateway node.'],
  egress_disabled: ['تونل خروجی غیرفعال است.', 'The egress tunnel is disabled.'],
  egress_not_applied: ['ابتدا تنظیمات تونل خروجی را اعمال کنید.', 'Apply the egress tunnel first.'],
  policy_invalid: ['سیاست DNS ساخته نشد.', 'The DNS policy could not be built.'],
  validation_failed: ['اعتبارسنجی gateway ناموفق بود.', 'Gateway validation failed.'],
}
const warnings: Record<string, [string, string]> = {
  direct_egress: [
    'بدون تونل خروجی، سرویس‌ها IP همین نود را می‌بینند. اگر نود در ایران است، تحریم دور زده نمی‌شود؛ نود باید خارج از ایران باشد یا یک تونل انتخاب کنید.',
    'Without an egress tunnel, services see this node’s own IP. A node inside Iran does not bypass sanctions: use a node abroad or pick a tunnel.',
  ],
  udp_not_covered: ['فقط TCP (پورت ۸۰ و ۴۴۳) از gateway عبور می‌کند. ترافیک UDP بازی‌ها پوشش داده نمی‌شود.', 'Only TCP (ports 80 and 443) goes through the gateway. Game UDP traffic is not covered.'],
}

const detailOf = (value: unknown) => (value as { data?: { detail?: unknown } }).data?.detail

export default function GamingDNSWizard({ nodes, tunnels, fa }: { nodes: TunnelNodeOption[]; tunnels: TunnelView[]; fa: boolean }) {
  const t = (f: string, e: string) => (fa ? f : e)
  const pick = (table: Record<string, [string, string]>, key: string) => (table[key] ? table[key][fa ? 0 : 1] : key)
  const client = useQueryClient()
  const [open, setOpen] = useState(false)
  const [node, setNode] = useState('')
  const [name, setName] = useState('Gaming DNS')
  const [address, setAddress] = useState('')
  const [egress, setEgress] = useState('')
  const [services, setServices] = useState<string[]>(['playstation', 'xbox', 'steam'])
  const [plan, setPlan] = useState<Plan | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const [applied, setApplied] = useState<Applied | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [userSearch, setUserSearch] = useState('')
  const [userId, setUserId] = useState('')
  const [ips, setIps] = useState('')
  const [limitGb, setLimitGb] = useState('')
  const [attached, setAttached] = useState('')
  const [portal, setPortal] = useState('')

  const catalog = useQuery({ queryKey: ['dns-catalog'], queryFn: () => fetcher<Catalog>('/api/dns/catalog'), enabled: open })
  const profiles = useQuery({ queryKey: ['gaming-profiles'], queryFn: () => fetcher<{ items: Profile[] }>('/api/smartdns/gaming/profiles') })
  const users = useQuery({
    queryKey: ['gaming-users', userSearch],
    queryFn: () => fetcher<{ users: { id: number; username: string }[] }>(`/api/users/simple?limit=20&search=${encodeURIComponent(userSearch)}`),
    enabled: !!applied,
  })
  const labels = new Map((catalog.data?.services ?? []).map(service => [service.key, service.label]))
  const others = (catalog.data?.services ?? []).filter(service => !GAMING.includes(service.key))
  const egressOptions = tunnels.filter(tunnel => tunnel.enabled && String(tunnel.source_node_id) === node)
  const ready = !!node && !!address.trim() && !!name.trim() && services.length > 0

  const body = () => ({
    node_id: Number(node),
    name: name.trim(),
    public_address: address.trim(),
    services,
    ...(egress ? { egress_tunnel_id: Number(egress) } : {}),
  })
  const reset = () => {
    setPlan(null)
    setConfirmed(false)
    setError('')
  }
  const report = (value: unknown) => {
    const detail = detailOf(value)
    if (detail && typeof detail === 'object' && 'blockers' in detail) {
      setPlan({ ok: false, warnings: [], blockers: (detail as { blockers: Blocker[] }).blockers })
      return
    }
    setError(
      typeof detail === 'string'
        ? detail
        : Array.isArray(detail)
          ? t('ورودی نامعتبر است؛ آدرس و نام را بررسی کنید.', 'Invalid input. Check the address and name.')
          : t('درخواست انجام نشد.', 'Request failed.'),
    )
  }
  const check = async () => {
    setBusy(true)
    reset()
    try {
      setPlan(await fetcher<Plan>('/api/smartdns/gaming/plan', { method: 'POST', body: body() }))
    } catch (value) {
      report(value)
    } finally {
      setBusy(false)
    }
  }
  const create = async () => {
    setBusy(true)
    setError('')
    try {
      setApplied(await fetcher<Applied>('/api/smartdns/gaming', { method: 'POST', body: { ...body(), confirm_apply: true } }))
      await Promise.all([client.invalidateQueries({ queryKey: ['smartdns'] }), client.invalidateQueries({ queryKey: ['gaming-profiles'] }), client.invalidateQueries({ queryKey: ['dns-policies'] })])
    } catch (value) {
      report(value)
    } finally {
      setBusy(false)
    }
  }
  const retry = async () => {
    if (!applied) return
    setBusy(true)
    setError('')
    try {
      const result = await fetcher<{ ok: boolean }>(`/api/smartdns/nodes/${applied.node_id}/apply`, { method: 'POST', body: { expected_revision: applied.revision } })
      setApplied({ ...applied, applied: result.ok })
      await client.invalidateQueries({ queryKey: ['smartdns'] })
    } catch (value) {
      report(value)
    } finally {
      setBusy(false)
    }
  }
  const attach = async () => {
    if (!applied) return
    setBusy(true)
    setError('')
    try {
      const addresses = ips.split(/[\s,]+/).filter(Boolean)
      const done = await fetcher<{ portal_path: string }>(`/api/smartdns/gaming/${applied.policy_id}/attach`, {
        method: 'POST',
        body: { user_id: Number(userId), client_ips: addresses, byte_limit: Math.round(Number(limitGb || 0) * 1024 ** 3) },
      })
      setAttached(users.data?.users.find(user => String(user.id) === userId)?.username ?? userId)
      setPortal(window.location.origin + done.portal_path)
      setUserId('')
      setIps('')
      await client.invalidateQueries({ queryKey: ['gaming-profiles'] })
    } catch (value) {
      report(value)
    } finally {
      setBusy(false)
    }
  }
  const close = () => {
    if (busy) return
    setOpen(false)
    setApplied(null)
    setAttached('')
    setPortal('')
    reset()
  }
  const toggle = (key: string) => {
    setServices(services.includes(key) ? services.filter(item => item !== key) : [...services, key])
    reset()
  }
  const addPreset = (members: string[]) => {
    setServices(Array.from(new Set([...services, ...members])))
    reset()
  }
  const input = 'border-input bg-background h-10 w-full rounded-md border px-3 text-sm'

  return (
    <section className="bg-card space-y-3 rounded-2xl border p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 font-medium">
            <Gamepad2 className="size-4" />
            {t('DNS گیمینگ (دورزدن تحریم)', 'Gaming DNS (sanction bypass)')}
          </h2>
          <p className="text-muted-foreground mt-2 max-w-3xl text-xs leading-6">
            {t(
              'سرویس‌ها را انتخاب کنید؛ پنل سیاست DNS، مسیرهای gateway و DNS نود را یک‌جا می‌سازد و اعمال می‌کند. سپس کاربر و IP او را متصل می‌کنید.',
              'Pick services. The panel builds the DNS policy, gateway routes and node DNS in one step and applies them. Then you attach a user and their IP.',
            )}
          </p>
          {(profiles.data?.items ?? []).map(item => (
            <p key={item.policy_id} className="mt-2 text-xs">
              <bdi>{item.name}</bdi> · {item.routes} {t('مسیر', 'routes')} · {item.accounts} {t('کاربر', 'users')} · <bdi dir="ltr">{item.address}</bdi> ·{' '}
              {item.applied ? t('اعمال‌شده', 'Applied') : t('در انتظار اعمال', 'Pending apply')}
            </p>
          ))}
        </div>
        <Button onClick={() => setOpen(true)}>{t('ساخت پروفایل گیمینگ', 'New gaming profile')}</Button>
      </div>
      <Dialog open={open} onOpenChange={value => (value ? setOpen(true) : close())}>
        <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-2xl" dir={fa ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>{t('پروفایل DNS گیمینگ', 'Gaming DNS profile')}</DialogTitle>
            <DialogDescription>
              {t(
                'موفقیت یعنی تنظیمات ذخیره و روی نود اعمال شد. کارکرد بازی، تأخیر و UDP هنوز روی شبکهٔ واقعی آزمون نشده‌اند.',
                'Success means the configuration was stored and pushed to the node. Game behavior, latency and UDP have not been tested on a live network.',
              )}
            </DialogDescription>
          </DialogHeader>
          {!applied && (
            <div className="space-y-4">
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="space-y-1 text-sm">
                  <span>{t('نود gateway', 'Gateway node')}</span>
                  <select
                    className={input}
                    value={node}
                    onChange={event => {
                      setNode(event.target.value)
                      setEgress('')
                      reset()
                    }}
                  >
                    <option value="">{t('انتخاب کنید', 'Select')}</option>
                    {nodes.map(item => (
                      <option key={item.id} value={item.id}>
                        {item.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="space-y-1 text-sm">
                  <span>{t('IP عمومی gateway', 'Gateway public IP')}</span>
                  <Input
                    dir="ltr"
                    placeholder="203.0.113.10"
                    value={address}
                    onChange={event => {
                      setAddress(event.target.value)
                      reset()
                    }}
                  />
                </label>
                <label className="space-y-1 text-sm">
                  <span>{t('نام پروفایل', 'Profile name')}</span>
                  <Input
                    value={name}
                    maxLength={80}
                    onChange={event => {
                      setName(event.target.value)
                      reset()
                    }}
                  />
                </label>
                <label className="space-y-1 text-sm">
                  <span>{t('تونل خروجی (اختیاری)', 'Egress tunnel (optional)')}</span>
                  <select
                    className={input}
                    value={egress}
                    onChange={event => {
                      setEgress(event.target.value)
                      reset()
                    }}
                  >
                    <option value="">{t('خروج مستقیم از همین نود', 'Direct from this node')}</option>
                    {egressOptions.map(tunnel => (
                      <option key={tunnel.id} value={tunnel.id}>
                        {tunnel.name}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
              <fieldset className="space-y-2">
                <legend className="text-sm">{t('سرویس‌ها', 'Services')}</legend>
                {(catalog.data?.presets?.length ?? 0) > 0 && (
                  <div className="flex flex-wrap items-center gap-2" role="group" aria-label={t('بسته‌های آماده', 'Ready-made bundles')}>
                    {catalog.data?.presets?.map(preset => (
                      <button key={preset.key} type="button" className="hover:border-primary rounded-full border px-3 py-1 text-xs" onClick={() => addPreset(preset.services)}>
                        + {preset.label[fa ? 0 : 1]}
                      </button>
                    ))}
                    {services.length > 0 && (
                      <button
                        type="button"
                        className="text-muted-foreground text-xs underline"
                        onClick={() => {
                          setServices([])
                          reset()
                        }}
                      >
                        {t('پاک‌کردن انتخاب', 'Clear selection')}
                      </button>
                    )}
                  </div>
                )}
                <div className="flex flex-wrap gap-x-4 gap-y-2">
                  {GAMING.map(key => (
                    <label key={key} className="flex items-center gap-2 text-sm">
                      <input type="checkbox" checked={services.includes(key)} onChange={() => toggle(key)} />
                      <bdi>{labels.get(key) ?? key}</bdi>
                    </label>
                  ))}
                </div>
                {others.length > 0 && (
                  <details>
                    <summary className="text-muted-foreground cursor-pointer text-xs">{t('سرویس‌های دیگر', 'Other services')}</summary>
                    <div className="mt-2 flex flex-wrap gap-x-4 gap-y-2">
                      {others.map(service => (
                        <label key={service.key} className="flex items-center gap-2 text-sm">
                          <input type="checkbox" checked={services.includes(service.key)} onChange={() => toggle(service.key)} />
                          <bdi>{service.label}</bdi>
                        </label>
                      ))}
                    </div>
                  </details>
                )}
              </fieldset>
              {plan && (
                <div className="space-y-3 rounded-xl border p-4 text-sm" aria-live="polite">
                  {plan.blockers.map(item => (
                    <p key={item.code} role="alert" className="text-destructive">
                      {pick(blockers, item.code)}
                      {item.detail ? <bdi className="text-muted-foreground ms-1 text-xs">({item.detail})</bdi> : null}
                    </p>
                  ))}
                  {plan.warnings.map(code => (
                    <p key={code} className="text-xs text-amber-600">
                      {pick(warnings, code)}
                    </p>
                  ))}
                  {plan.policy && (
                    <p>
                      {t(`${plan.policy.domains} دامنه در ${plan.routes} مسیر gateway`, `${plan.policy.domains} domains in ${plan.routes} gateway routes`)}
                      {plan.dns && (
                        <span className="text-muted-foreground ms-2 text-xs" dir="ltr">
                          DNS {plan.dns.bind_address}:{plan.dns.port}
                          {plan.dns.will_enable ? ' (new)' : ''}
                        </span>
                      )}
                    </p>
                  )}
                  <ul className="space-y-1 text-xs">
                    {plan.coverage?.map(item => (
                      <li key={item.service} className="flex justify-between gap-2">
                        <bdi>{item.label}</bdi>
                        <span>{t(`${item.domains} دامنه`, `${item.domains} domains`)}</span>
                      </li>
                    ))}
                  </ul>
                  {plan.ok && (
                    <label className="flex items-start gap-2 text-xs leading-6">
                      <input type="checkbox" className="mt-1" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />
                      <span>
                        {t(
                          'تنظیمات gateway روی نود جایگزین و در صورت نیاز شنوندهٔ DNS آن باز می‌شود. تأیید می‌کنم.',
                          'The gateway configuration on the node is replaced and its DNS listener may open. I confirm.',
                        )}
                      </span>
                    </label>
                  )}
                </div>
              )}
              {error && (
                <p role="alert" className="text-destructive text-sm">
                  {error}
                </p>
              )}
              <DialogFooter>
                <Button variant="outline" disabled={!ready || busy} onClick={check}>
                  {busy ? t('در حال بررسی…', 'Checking…') : t('بررسی پیش از اجرا', 'Check first')}
                </Button>
                <Button disabled={!plan?.ok || !confirmed || busy} onClick={create}>
                  {t('ساخت و اعمال', 'Create and apply')}
                </Button>
              </DialogFooter>
            </div>
          )}
          {applied && (
            <div className="space-y-4" aria-live="polite">
              <p className={applied.applied ? 'text-sm' : 'text-destructive text-sm'} role={applied.applied ? undefined : 'alert'}>
                {applied.applied
                  ? t(`پروفایل ساخته و روی نود اعمال شد (${applied.route_ids.length} مسیر).`, `Profile created and applied to the node (${applied.route_ids.length} routes).`)
                  : t(
                      'پروفایل ذخیره شد اما نود تأیید نکرد؛ تا تأیید، DNS وابسته بسته می‌ماند.',
                      'The profile is stored but the node did not acknowledge it. Dependent DNS stays closed until it does.',
                    )}
              </p>
              {!applied.applied && (
                <Button variant="outline" disabled={busy} onClick={retry}>
                  {t('تلاش دوباره برای اعمال', 'Retry apply')}
                </Button>
              )}
              <div className="space-y-3 rounded-xl border p-4">
                <h3 className="text-sm font-medium">{t('اتصال کاربر', 'Attach a user')}</h3>
                <p className="text-muted-foreground text-xs leading-6">
                  {t(
                    'بدون کاربر و IP تأییدشده هیچ ترافیکی از gateway عبور نمی‌کند. روی دستگاه بازیکن، DNS را روی IP نود بگذارید.',
                    'No traffic passes the gateway without an attached user and verified IP. On the player’s device set DNS to the node IP.',
                  )}
                </p>
                <div className="grid gap-3 sm:grid-cols-2">
                  <label className="space-y-1 text-sm">
                    <span>{t('جستجوی کاربر', 'Search users')}</span>
                    <Input value={userSearch} onChange={event => setUserSearch(event.target.value)} />
                  </label>
                  <label className="space-y-1 text-sm">
                    <span>{t('کاربر', 'User')}</span>
                    <select className={input} value={userId} onChange={event => setUserId(event.target.value)}>
                      <option value="">{t('انتخاب کنید', 'Select')}</option>
                      {users.data?.users.map(user => (
                        <option key={user.id} value={user.id}>
                          {user.username}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="space-y-1 text-sm">
                    <span>{t('IP عمومی بازیکن (حداکثر ۳)', 'Player public IPs (up to 3)')}</span>
                    <Input dir="ltr" placeholder="198.51.100.7" value={ips} onChange={event => setIps(event.target.value)} />
                  </label>
                  <label className="space-y-1 text-sm">
                    <span>{t('سقف حجم (GB؛ خالی = نامحدود)', 'Byte cap (GB; empty = unlimited)')}</span>
                    <Input dir="ltr" inputMode="decimal" value={limitGb} onChange={event => setLimitGb(event.target.value)} />
                  </label>
                </div>
                {attached && (
                  <div className="space-y-2 text-xs">
                    <p>
                      {t('کاربر متصل شد:', 'User attached:')} <bdi>{attached}</bdi>
                    </p>
                    <p className="text-muted-foreground leading-6">
                      {t('این لینک ساب DNS اختصاصی کاربر است؛ خودش IP فعلی‌اش را در آن ثبت می‌کند.', 'This is the user’s own DNS link. They register their current IP on it.')}
                    </p>
                    <div className="flex gap-2">
                      <Input dir="ltr" readOnly aria-label="DNS link" value={portal} className="min-w-0 flex-1" />
                      <Button variant="outline" onClick={() => void navigator.clipboard.writeText(portal)}>
                        {t('کپی', 'Copy')}
                      </Button>
                    </div>
                  </div>
                )}
                <Button disabled={!userId || !ips.trim() || busy} onClick={attach}>
                  {t('اتصال کاربر', 'Attach user')}
                </Button>
              </div>
              {error && (
                <p role="alert" className="text-destructive text-sm">
                  {error}
                </p>
              )}
              <DialogFooter>
                <Button variant="outline" disabled={busy} onClick={close}>
                  {t('بستن', 'Close')}
                </Button>
              </DialogFooter>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </section>
  )
}
