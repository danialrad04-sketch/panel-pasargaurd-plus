import { useDeferredValue, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useAdmin } from '@/hooks/use-admin'
import { isOwner } from '@/utils/rbac'
import { fetcher } from '@/service/http'
import { formatBytes } from '@/utils/formatByte'
import type { DNSAccountList, DNSAccountView, DNSPolicyView, TunnelState } from '@/service/api'

type Route = {
  id: number
  node_id: number
  policy_id: number
  domain: string
  public_address: string
  bind_address: string
  tcp_ports: number[]
  udp_ports: number[]
  mode: 'sniff' | 'fixed'
  enabled: boolean
  revision: number
  egress_tunnel_id?: number | null
}
type State = { routes: Route[]; nodes: { node_id: number; revision: number; applied_revision: number; result: { ok?: boolean } }[] }
const select = 'bg-background h-10 w-full rounded-md border px-3 text-sm'

export default function SmartDNS() {
  const { admin } = useAdmin()
  const owner = isOwner(admin)
  const { i18n } = useTranslation()
  const fa = i18n.language.startsWith('fa')
  const t = (f: string, e: string) => (fa ? f : e)
  const client = useQueryClient()
  const [search, setSearch] = useState('')
  const deferred = useDeferredValue(search)
  const state = useQuery({ queryKey: ['smartdns'], queryFn: () => fetcher<State>('/api/smartdns'), enabled: owner, refetchInterval: 15000 })
  const topology = useQuery({ queryKey: ['native-tunnels'], queryFn: () => fetcher<TunnelState>('/api/tunnels'), enabled: owner })
  const policies = useQuery({ queryKey: ['dns-policies'], queryFn: () => fetcher<DNSPolicyView[]>('/api/dns/policies'), enabled: owner })
  const accounts = useQuery({ queryKey: ['smartdns-accounts', deferred], queryFn: () => fetcher<DNSAccountList>(`/api/dns/accounts?limit=50&search=${encodeURIComponent(deferred)}`), enabled: owner })
  const [editing, setEditing] = useState<Route | 'new' | null>(null)
  const [node, setNode] = useState('')
  const [account, setAccount] = useState<DNSAccountView | null>(null)
  const [confirm, setConfirm] = useState<{ title: string; description: string; run: () => Promise<unknown> } | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const perform = async (run: () => Promise<unknown>) => {
    if (busy) return
    setBusy(true)
    setError('')
    try {
      await run()
      setConfirm(null)
      await Promise.all([
        client.invalidateQueries({ queryKey: ['smartdns'] }),
        client.invalidateQueries({ queryKey: ['smartdns-accounts'] }),
        client.invalidateQueries({ queryKey: ['native-operations'] }),
      ])
    } catch (error) {
      const detail = (error as { data?: { detail?: unknown } }).data?.detail
      setError(
        typeof detail === 'string' ? detail : t('عملیات انجام نشد؛ دامنه، پورت‌ها، نسل تنظیمات و وضعیت نود را بررسی کنید.', 'Operation failed. Check domains, ports, generation and node state.'),
      )
    } finally {
      setBusy(false)
    }
  }
  const open = (route: Route | 'new') => {
    setEditing(route)
    setNode(route === 'new' ? '' : String(route.node_id))
    setError('')
  }
  const nodeName = (id: number) => topology.data?.nodes.find(node => node.id === id)?.name || `#${id}`
  if (!owner) return <div className="rounded-xl border p-5">{t('مدیریت gateway فقط برای مالک پنل در دسترس است.', 'Gateway infrastructure is owner-only.')}</div>
  return (
    <div className="mx-auto w-full max-w-7xl space-y-6 pb-8" dir={fa ? 'rtl' : 'ltr'}>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="text-muted-foreground mb-2 text-xs">PASARGUARD PLUS · SMARTDNS</p>
          <h1 className="text-2xl font-semibold">{t('دسترسی سرویس‌ها بدون VPN', 'Service access without VPN')}</h1>
          <p className="text-muted-foreground mt-2 max-w-3xl text-sm leading-7">
            {t(
              'پاسخ DNS به gateway مجاز هدایت می‌شود؛ ترافیک با IP تأییدشدهٔ کاربر و سهمیهٔ مستقل از مسیر مستقیم خروجی یا تونل بومی عبور می‌کند. TLS سرتاسری حفظ می‌شود.',
              'DNS points to an authorized gateway. Verified client IPs and a separate byte budget control traffic through direct egress or a native tunnel. End-to-end TLS is preserved.',
            )}
          </p>
        </div>
        <Button disabled={busy} onClick={() => open('new')}>
          {t('gateway جدید', 'New gateway route')}
        </Button>
      </div>
      {(error || state.error || policies.error || accounts.error) && (
        <p role="alert" className="text-destructive rounded-xl border p-4 text-sm">
          {error || t('دریافت وضعیت انجام نشد.', 'Could not load gateway state.')}
        </p>
      )}
      <div className="grid gap-3 md:grid-cols-3">
        {[
          { title: t('وب‌سایت‌ها', 'Websites'), text: t('TLS SNI و HTTP Host برای اشتراک IP؛ بدون رمزگشایی محتوای TLS.', 'TLS SNI and HTTP Host share an IP without decrypting TLS.') },
          {
            title: t('بازی و UDP', 'Gaming and UDP'),
            text: t(
              'دامنه و پورت مقصد باید صریح باشند. دو مقصد UDP متفاوت نمی‌توانند یک IP و پورت یکسان داشته باشند.',
              'Explicit domain and port mapping is required. Different UDP targets cannot share one IP/port.',
            ),
          },
          {
            title: t('کنترل دسترسی', 'Access control'),
            text: t(
              'حساب DNS، IP lease فعال و بودجهٔ حجم لازم است. قطع پنل، ترافیک را پس از پایان مجوز کوتاه متوقف می‌کند.',
              'A DNS account, active IP lease and byte budget are required. Panel loss stops traffic after its short grant expires.',
            ),
          },
        ].map(item => (
          <div key={item.title} className="bg-card rounded-2xl border p-4">
            <h2 className="text-sm font-medium">{item.title}</h2>
            <p className="text-muted-foreground mt-2 text-xs leading-6">{item.text}</p>
          </div>
        ))}
      </div>
      <section className="space-y-3">
        <h2 className="font-medium">{t('اعمال تنظیمات gateway روی نود', 'Apply gateway configuration')}</h2>
        {state.data?.nodes.map(state => (
          <div key={state.node_id} className="bg-card flex flex-wrap items-center justify-between gap-3 rounded-xl border p-4 text-sm">
            <div>
              <span className="font-medium">{nodeName(state.node_id)}</span>
              <span className="text-muted-foreground ms-3 text-xs">
                {state.revision === state.applied_revision ? t('تنظیمات تأیید شده', 'Acknowledged') : t('در انتظار اعمال؛ DNS وابسته بسته است', 'Pending apply; dependent DNS fails closed')}
              </span>
            </div>
            <Button
              variant="outline"
              disabled={busy}
              onClick={() =>
                setConfirm({
                  title: t('اعمال gateway', 'Apply gateway'),
                  description: t(
                    'اتصال‌های فعال gateway این نود ممکن است قطع شوند. تونل خروجی انتخاب‌شده باید قبلاً اعمال شده باشد.',
                    'Active gateway connections on this node may disconnect. Selected tunnel egress must already be applied.',
                  ),
                  run: async () => {
                    const result = await fetcher<{ ok: boolean }>(`/api/smartdns/nodes/${state.node_id}/apply`, { method: 'POST', body: { expected_revision: state.revision } })
                    if (!result.ok) throw new Error('Gateway did not acknowledge')
                  },
                })
              }
            >
              {t('اعمال روی نود', 'Apply to node')}
            </Button>
          </div>
        ))}
      </section>
      <section className="grid gap-3 lg:grid-cols-2">
        {state.data?.routes.map(route => (
          <article key={route.id} className="bg-card space-y-3 rounded-2xl border p-5">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h2 dir="ltr" className="font-medium">
                {route.domain}
              </h2>
              <span className="text-muted-foreground text-xs">{route.enabled ? t('فعال در تنظیمات', 'Enabled') : t('غیرفعال', 'Disabled')}</span>
            </div>
            <p dir="ltr" className="text-muted-foreground text-xs">
              {route.public_address} → {nodeName(route.node_id)} · TCP {route.tcp_ports.join(', ') || '—'} · UDP {route.udp_ports.join(', ') || '—'}
            </p>
            <p className="text-xs">
              {route.egress_tunnel_id
                ? `${t('خروجی تونل', 'Tunnel egress')}: ${topology.data?.items.find(item => item.id === route.egress_tunnel_id)?.name || route.egress_tunnel_id}`
                : t('خروج مستقیم از کشور نود gateway', 'Direct egress from the gateway node country')}
            </p>
            <div className="flex gap-2">
              <Button size="sm" variant="outline" onClick={() => open(route)}>
                {t('ویرایش', 'Edit')}
              </Button>
              <Button
                size="sm"
                variant="ghost"
                disabled={busy}
                onClick={() =>
                  setConfirm({
                    title: t('حذف gateway', 'Remove gateway'),
                    description: t('ابتدا مسیر را غیرفعال و روی نود اعمال کنید؛ سپس حذف آن تأیید می‌شود.', 'Disable and apply this route before removing it.'),
                    run: () => fetcher(`/api/smartdns/routes/${route.id}`, { method: 'DELETE', body: { expected_revision: route.revision } }),
                  })
                }
              >
                {t('حذف', 'Remove')}
              </Button>
            </div>
          </article>
        ))}
      </section>
      <section className="bg-card space-y-4 rounded-2xl border p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="font-medium">{t('دسترسی و حجم کاربران DNS', 'DNS user access and volume')}</h2>
          <Input className="max-w-xs" value={search} onChange={event => setSearch(event.target.value)} placeholder={t('جستجوی نام کاربر', 'Search username')} />
        </div>
        <p className="text-muted-foreground text-xs leading-6">
          {t(
            'ابتدا حساب DNS و IP مجاز کاربر را در بخش DNS بسازید. سقف حجم این سرویس مستقل از VPN است. حجم رزروشده، جلوی مصرف هم‌زمان بیشتر از سقف را می‌گیرد؛ در قطع ناگهانی نود، رزرو نامشخص محافظه‌کارانه حساب می‌شود.',
            'Create the DNS account and authorized IP lease in DNS first. This budget is separate from VPN. Reservations prevent concurrent overspending; uncertain crash usage remains conservatively accounted.',
          )}
        </p>
        {accounts.data?.items.map(item => (
          <div key={item.id} className="flex flex-wrap items-center justify-between gap-3 border-t pt-3 text-sm">
            <div>
              <span className="font-medium">{item.username}</span>
              <p className="text-muted-foreground mt-1 text-xs">
                {item.relay_enabled ? t('دسترسی فعال', 'Access enabled') : t('دسترسی غیرفعال', 'Access disabled')} · {t('مصرف', 'Used')} {formatBytes(item.relay_bytes || 0)} · {t('رزرو', 'Reserved')}{' '}
                {formatBytes(item.relay_reserved || 0)} · {t('سقف', 'Cap')} {item.relay_byte_limit ? formatBytes(item.relay_byte_limit) : t('نامحدود', 'Unlimited')}
              </p>
              {!!item.relay_uncertain_bytes && (
                <p className="mt-1 text-xs text-amber-600">
                  {t('مصرف نامشخص پس از قطع نود', 'Uncertain crash usage')}: {formatBytes(item.relay_uncertain_bytes)}
                </p>
              )}
            </div>
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setAccount(item)
                setError('')
              }}
            >
              {t('دسترسی و سهمیه', 'Access and budget')}
            </Button>
          </div>
        ))}
        {accounts.data && accounts.data.total > accounts.data.items.length && (
          <p className="text-muted-foreground text-xs">{t('برای یافتن کاربران بیشتر، نام را جستجو کنید.', 'Search a username to find more accounts.')}</p>
        )}
      </section>
      <Dialog
        open={editing !== null}
        onOpenChange={open => {
          if (!open && !busy) setEditing(null)
        }}
      >
        <DialogContent className="max-h-[90dvh] overflow-auto sm:max-w-2xl" dir={fa ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>{t('تنظیم gateway بومی', 'Native gateway configuration')}</DialogTitle>
            <DialogDescription>{t('تنظیم ابتدا ذخیره می‌شود؛ پس از بررسی پورت‌ها آن را روی نود اعمال کنید.', 'Save desired configuration, then apply it after checking listeners.')}</DialogDescription>
          </DialogHeader>
          <form
            className="grid gap-4 sm:grid-cols-2"
            onSubmit={event => {
              event.preventDefault()
              const form = new FormData(event.currentTarget)
              const ports = (key: string) =>
                String(form.get(key))
                  .split(/[\s,]+/)
                  .filter(Boolean)
                  .map(Number)
              perform(async () => {
                await fetcher(editing === 'new' ? '/api/smartdns/routes' : `/api/smartdns/routes/${editing?.id}`, {
                  method: editing === 'new' ? 'POST' : 'PUT',
                  body: {
                    node_id: Number(node),
                    policy_id: Number(form.get('policy')),
                    domain: String(form.get('domain')),
                    public_address: String(form.get('public')),
                    bind_address: String(form.get('bind')),
                    tcp_ports: ports('tcp'),
                    udp_ports: ports('udp'),
                    mode: form.get('mode'),
                    enabled: form.get('enabled') === 'on',
                    egress_tunnel_id: form.get('egress') ? Number(form.get('egress')) : null,
                    ...(editing && editing !== 'new' ? { expected_revision: editing.revision } : {}),
                  },
                })
                setEditing(null)
              })
            }}
          >
            <label className="space-y-2 text-sm">
              <span>{t('نود gateway', 'Gateway node')}</span>
              <select className={select} value={node} onChange={event => setNode(event.target.value)} required disabled={editing !== 'new'}>
                <option value="">—</option>
                {topology.data?.nodes.map(node => (
                  <option key={node.id} value={node.id}>
                    {node.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-2 text-sm">
              <span>{t('سیاست DNS', 'DNS policy')}</span>
              <select name="policy" className={select} required defaultValue={editing && editing !== 'new' ? editing.policy_id : ''}>
                <option value="">—</option>
                {policies.data?.map(policy => (
                  <option key={policy.id} value={policy.id}>
                    {policy.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-2 text-sm">
              <span>{t('دامنهٔ سرویس', 'Service domain')}</span>
              <Input name="domain" required dir="ltr" defaultValue={editing && editing !== 'new' ? editing.domain : ''} placeholder="service.example.com" />
            </label>
            <label className="space-y-2 text-sm">
              <span>{t('روش مسیریابی TCP', 'TCP routing mode')}</span>
              <select name="mode" className={select} defaultValue={editing && editing !== 'new' ? editing.mode : 'sniff'}>
                <option value="sniff">TLS SNI / HTTP Host</option>
                <option value="fixed">{t('مقصد ثابت؛ بازی و پروتکل خام', 'Fixed target; games and raw protocols')}</option>
              </select>
            </label>
            <label className="space-y-2 text-sm">
              <span>{t('IP قابل دسترس کاربران', 'Client-reachable IP')}</span>
              <Input name="public" required dir="ltr" defaultValue={editing && editing !== 'new' ? editing.public_address : ''} />
            </label>
            <label className="space-y-2 text-sm">
              <span>{t('IP محلی شنونده روی نود', 'Local listener IP on node')}</span>
              <Input name="bind" required dir="ltr" defaultValue={editing && editing !== 'new' ? editing.bind_address : '0.0.0.0'} />
            </label>
            <label className="space-y-2 text-sm">
              <span>{t('پورت‌های TCP؛ جدا با کاما', 'TCP ports; comma-separated')}</span>
              <Input name="tcp" dir="ltr" defaultValue={editing && editing !== 'new' ? editing.tcp_ports.join(',') : '80,443'} />
            </label>
            <label className="space-y-2 text-sm">
              <span>{t('پورت‌های UDP؛ مقصد دقیق', 'UDP ports; explicit destination')}</span>
              <Input name="udp" dir="ltr" defaultValue={editing && editing !== 'new' ? editing.udp_ports.join(',') : ''} placeholder="3074" />
            </label>
            <label className="space-y-2 text-sm sm:col-span-2">
              <span>{t('خروج ترافیک', 'Traffic egress')}</span>
              <select name="egress" className={select} defaultValue={editing && editing !== 'new' ? editing.egress_tunnel_id || '' : ''}>
                <option value="">{t('مستقیم از همین نود', 'Direct from this node')}</option>
                {topology.data?.items
                  .filter(item => item.source_node_id === Number(node))
                  .map(item => (
                    <option key={item.id} value={item.id}>
                      {item.name}
                    </option>
                  ))}
              </select>
            </label>
            <label className="flex gap-2 text-sm">
              <input name="enabled" type="checkbox" defaultChecked={editing && editing !== 'new' ? editing.enabled : false} />
              {t('فعال در تنظیمات', 'Enabled in desired configuration')}
            </label>
            {error && (
              <p role="alert" className="text-destructive text-sm sm:col-span-2">
                {error}
              </p>
            )}
            <Button className="sm:col-span-2" type="submit" disabled={busy}>
              {t('ذخیره gateway', 'Save gateway')}
            </Button>
          </form>
        </DialogContent>
      </Dialog>
      <Dialog
        open={!!account}
        onOpenChange={open => {
          if (!open && !busy) setAccount(null)
        }}
      >
        <DialogContent dir={fa ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>
              {t('سهمیهٔ SmartDNS', 'SmartDNS budget')} · {account?.username}
            </DialogTitle>
            <DialogDescription>{t('مجوز IP و وضعیت کاربر همچنان باید معتبر باشد.', 'The IP lease and native user eligibility must also remain valid.')}</DialogDescription>
          </DialogHeader>
          <form
            className="space-y-4"
            onSubmit={event => {
              event.preventDefault()
              const form = new FormData(event.currentTarget)
              perform(async () => {
                await fetcher(`/api/smartdns/accounts/${account?.id}`, {
                  method: 'PUT',
                  body: { enabled: form.get('enabled') === 'on', byte_limit: Math.round(Number(form.get('limit')) * 1024 ** 3), grants_per_second: Number(form.get('rate')) },
                })
                setAccount(null)
              })
            }}
          >
            <label className="flex gap-2 text-sm">
              <input name="enabled" type="checkbox" defaultChecked={account?.relay_enabled} />
              {t('اجازهٔ عبور ترافیک از gateway', 'Allow gateway traffic')}
            </label>
            <label className="block space-y-2 text-sm">
              <span>{t('سقف حجم؛ GiB، صفر نامحدود', 'Byte cap; GiB, zero means unlimited')}</span>
              <Input name="limit" type="number" min={0} step="0.001" defaultValue={account?.relay_byte_limit ? account.relay_byte_limit / 1024 ** 3 : 0} required />
            </label>
            <label className="block space-y-2 text-sm">
              <span>{t('سقف درخواست مجوز در ثانیه', 'Authorization requests per second')}</span>
              <Input name="rate" type="number" min={1} max={2000} defaultValue={account?.relay_rps || 200} required />
            </label>
            {error && (
              <p role="alert" className="text-destructive text-sm">
                {error}
              </p>
            )}
            <Button disabled={busy}>{t('ذخیره سهمیه', 'Save budget')}</Button>
          </form>
        </DialogContent>
      </Dialog>
      <Dialog
        open={!!confirm}
        onOpenChange={open => {
          if (!open && !busy) setConfirm(null)
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{confirm?.title}</DialogTitle>
            <DialogDescription>{confirm?.description}</DialogDescription>
          </DialogHeader>
          {error && (
            <p role="alert" className="text-destructive text-sm">
              {error}
            </p>
          )}
          <Button disabled={busy} onClick={() => confirm && perform(confirm.run)}>
            {t('تأیید و اجرا', 'Confirm and run')}
          </Button>
        </DialogContent>
      </Dialog>
    </div>
  )
}
