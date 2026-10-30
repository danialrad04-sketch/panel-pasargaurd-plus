import { useState } from 'react'
import CarrierEditor from './carrier-editor'
import NativeProvisioning from './native-provisioning'
import TunnelEngineCore from './tunnel-engine-core'
import PaqetEditor, { editablePaqet } from './paqet-editor'
import TunnelPreflight from './tunnel-preflight'
import NetworkOperations from './network-operations'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Activity, ArrowLeftRight, ArrowRight, Check, Globe2, KeyRound, Layers3, Plus, RefreshCw, ShieldCheck, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useAdmin } from '@/hooks/use-admin'
import { isOwner } from '@/utils/rbac'
import { fetcher } from '@/service/http'
import type { TunnelAuditView, TunnelState, TunnelView } from '@/service/api'

const panel = 'rounded-2xl border bg-card p-5'
const selectClass = 'border-input bg-background h-10 w-full rounded-md border px-3 text-sm'
type CarrierTransport = 'shadowsocks-2022' | 'paqet' | 'backhaul' | 'gost' | 'frp'
const companion = (value: string | undefined): value is 'backhaul' | 'gost' | 'frp' => ['backhaul', 'gost', 'frp'].includes(value || '')
type Catalog = { services: { key: string; label: string; groups: { key: string; label: string; domains: string[] }[] }[] }

export default function NativeTunnels() {
  const { admin } = useAdmin()
  const { i18n } = useTranslation()
  const fa = i18n.language.startsWith('fa')
  const t = (f: string, e: string) => (fa ? f : e)
  const owner = isOwner(admin)
  const client = useQueryClient()
  const state = useQuery({ queryKey: ['native-tunnels'], queryFn: () => fetcher<TunnelState>('/api/tunnels'), enabled: owner, refetchInterval: 10000 })
  const [auditOffset, setAuditOffset] = useState(0)
  const audit = useQuery({ queryKey: ['native-tunnels', 'audit', auditOffset], queryFn: () => fetcher<TunnelAuditView[]>(`/api/tunnels/audit?offset=${auditOffset}&limit=10`), enabled: owner })
  const catalog = useQuery({ queryKey: ['native-tunnel-catalog'], queryFn: () => fetcher<Catalog>('/api/dns/catalog'), enabled: owner })
  const [editing, setEditing] = useState<TunnelView | 'new' | null>(null)
  const [checking, setChecking] = useState<TunnelView | null>(null)
  const [transport, setTransport] = useState<CarrierTransport>('paqet')
  const [paqetText, setPaqetText] = useState('{}')
  const [carrierText, setCarrierText] = useState('{}')
  const [output, setOutput] = useState<{ title: string; text: string } | null>(null)
  const [source, setSource] = useState('')
  const [exit, setExit] = useState('')
  const [tags, setTags] = useState<string[]>([])
  const [domains, setDomains] = useState('')
  const [search, setSearch] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [confirm, setConfirm] = useState<{ title: string; detail: string; run: () => Promise<unknown> } | null>(null)
  const nodes = state.data?.nodes || []
  const items = state.data?.items || []
  const applying = busy || state.data?.applying
  const pending = items.filter(item => item.revision !== item.applied_revision).length
  const nodeName = (id: number) => nodes.find(node => node.id === id)?.name || `#${id}`
  const refresh = () => client.invalidateQueries({ queryKey: ['native-tunnels'] })
  const mutate = async (fn: () => Promise<unknown>) => {
    if (busy) return
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await fn()
      await refresh()
      setConfirm(null)
    } catch (e) {
      const detail = (e as { data?: { detail?: unknown } }).data?.detail
      setError(
        typeof detail === 'string'
          ? detail
          : Array.isArray(detail)
            ? detail
                .slice(0, 5)
                .map((error: { loc?: (string | number)[]; msg?: string }) => `${error.loc?.join('.')}: ${error.msg}`)
                .join(' · ')
            : t('درخواست انجام نشد. وضعیت نودها و ورودی‌ها را بررسی کنید.', 'Request failed. Check node state and your inputs.'),
      )
    } finally {
      setBusy(false)
    }
  }
  const open = (item: TunnelView | 'new') => {
    setError('')
    setEditing(item)
    setNotice('')
    setTransport(item === 'new' ? 'paqet' : item.transport || 'shadowsocks-2022')
    setPaqetText(editablePaqet(item === 'new' ? {} : item.paqet_options))
    setCarrierText(JSON.stringify(item === 'new' ? {} : item.carrier_options || {}))
    setSource(item === 'new' ? '' : String(item.source_node_id))
    setExit(item === 'new' ? '' : String(item.exit_node_id))
    setTags(item === 'new' ? [] : item.inbound_tags)
    setDomains(item === 'new' ? '' : (item.domains || []).join('\n'))
  }
  const apply = () =>
    setConfirm({
      title: t('اعمال مسیرها روی نودها', 'Apply routes to nodes'),
      detail: t(
        'هستهٔ نودهای مرتبط مجدداً راه‌اندازی می‌شود و اتصال‌های فعال ممکن است قطع شوند. ابتدا خروجی‌ها و سپس مبداهای فعال تنظیم می‌شوند. شکست هر مرحله نیاز به بررسی و تلاش مجدد دارد.',
        'Affected node cores restart and active connections may disconnect. Active exits deploy before sources. A partial failure requires review and retry.',
      ),
      run: async () => {
        const result = await fetcher<{ ok: boolean }>('/api/tunnels/apply', { method: 'POST' })
        setNotice(
          result.ok
            ? t('نودها اعمال تنظیمات را تأیید کردند؛ مسیر بازی هنوز آزمون نشده است.', 'Nodes acknowledged configuration; game traffic has not been probed.')
            : t('اعمال ناقص بود. نتیجهٔ هر نود را بررسی و دوباره تلاش کنید.', 'Deployment was partial. Review node results and retry.'),
        )
      },
    })
  if (!owner) return <div className={panel}>{t('مدیریت زیرساخت تونل فقط برای مالک پنل در دسترس است.', 'Tunnel infrastructure is available to the panel owner.')}</div>
  return (
    <div className="mx-auto flex w-full max-w-7xl flex-col gap-6 pb-8" dir={fa ? 'rtl' : 'ltr'}>
      {checking && <TunnelPreflight tunnel={checking} fa={fa} onClose={() => setChecking(null)} />}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <div className="text-muted-foreground mb-2 flex items-center gap-2 text-xs">
            <Layers3 className="size-4" />
            PASARGUARD PLUS · NETWORK
          </div>
          <h1 className="text-2xl font-semibold">{t('تونل‌های بومی نودها', 'Native node tunnels')}</h1>
          <p className="text-muted-foreground mt-2 max-w-2xl text-sm leading-7">
            {t(
              'مسیر رمز‌شده بین نودهای همین پنل؛ با ورودی‌های کاربران، سهمیه‌ها و هستهٔ فعلی پاسارگارد.',
              'Encrypted paths between this panel’s nodes using existing PasarGuard user inbounds, quotas and cores.',
            )}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" disabled={!!applying} onClick={() => refresh()}>
            <RefreshCw className="size-4" />
            {t('تازه‌سازی', 'Refresh')}
          </Button>
          <Button variant="outline" disabled={!!applying || !items.length} onClick={apply}>
            <Check className="size-4" />
            {t('اعمال روی نودها', 'Apply to nodes')}
            {pending > 0 && <span className="bg-primary/10 rounded-full px-2">{pending}</span>}
          </Button>
          <Button disabled={!!applying} onClick={() => open('new')}>
            <Plus className="size-4" />
            {t('تونل جدید', 'New tunnel')}
          </Button>
        </div>
      </div>
      {(error || state.error) && (
        <div role="alert" className="border-destructive/30 bg-destructive/5 text-destructive rounded-xl border p-4 text-sm">
          {error || t('دریافت تنظیمات انجام نشد.', 'Could not load tunnel state.')}
        </div>
      )}
      {notice && (
        <div role="status" className="bg-primary/5 rounded-xl border p-4 text-sm">
          {notice}
        </div>
      )}
      <div className="grid gap-3 sm:grid-cols-3">
        {[
          { icon: ArrowLeftRight, label: t('مسیرهای فعال در تنظیمات', 'Enabled routes'), value: items.filter(item => item.enabled).length },
          { icon: Globe2, label: t('نودهای ثبت‌شده', 'Registered nodes'), value: nodes.length },
          { icon: Activity, label: t('تغییرات در انتظار اعمال', 'Pending changes'), value: pending },
        ].map(({ icon: Icon, label, value }) => (
          <div key={label} className={panel}>
            <div className="text-muted-foreground flex items-center justify-between gap-3 text-xs">
              {label}
              <Icon className="size-4" />
            </div>
            <div className="mt-3 text-3xl font-semibold tabular-nums">{value}</div>
          </div>
        ))}
      </div>
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_300px]">
        <section className="min-w-0 space-y-4" aria-label={t('مسیرها', 'Routes')}>
          {state.isLoading && <div className={panel}>{t('در حال دریافت…', 'Loading…')}</div>}
          {!state.isLoading && !items.length && (
            <div className={`${panel} py-14 text-center`}>
              <ArrowLeftRight className="text-muted-foreground mx-auto mb-4 size-8" />
              <h2 className="font-medium">{t('اولین مسیر بین نودها را بسازید', 'Create your first node path')}</h2>
              <p className="text-muted-foreground mx-auto mt-3 max-w-sm text-sm leading-7">
                {t(
                  'نود ایران می‌تواند مبدا باشد و نود خارج نقش خروجی را داشته باشد. هر دو باید هستهٔ Xray داشته باشند.',
                  'An Iran node can be the source and an overseas node the exit. Both need an Xray core.',
                )}
              </p>
            </div>
          )}
          {items.map(item => (
            <article key={item.id} className={panel}>
              <Button variant="outline" size="sm" className="mb-3" onClick={() => setChecking(item)}>
                {t('بررسی آمادگی', 'Check readiness')}
              </Button>
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <h2 className="font-semibold">{item.name}</h2>
                  <div className="text-muted-foreground mt-1 text-xs" dir="ltr">
                    {{ paqet: 'Paqet · KCP', backhaul: 'Backhaul', gost: 'GOST', frp: 'FRP', 'shadowsocks-2022': 'SS2022' }[item.transport || 'shadowsocks-2022']} · TCP + UDP · :{item.port}
                  </div>
                </div>
                <div className="flex gap-2 text-xs">
                  <span className={`rounded-full px-3 py-1 ${item.enabled ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground'}`}>
                    {item.enabled ? t('فعال در تنظیمات', 'Enabled') : t('غیرفعال در تنظیمات', 'Disabled')}
                  </span>
                  <span className="rounded-full border px-3 py-1">
                    {item.applied_revision === item.revision ? t('آخرین اعمال تأیید شده', 'Last apply acknowledged') : t('در انتظار اعمال', 'Pending apply')}
                  </span>
                </div>
              </div>
              <div className="bg-muted/40 my-5 grid grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] items-center gap-3 rounded-xl p-4">
                <div className="min-w-0">
                  <p className="text-muted-foreground text-xs">{t('مبدا', 'Source')}</p>
                  <p className="mt-1 truncate text-sm font-medium">{nodeName(item.source_node_id)}</p>
                  <p className="text-muted-foreground mt-1 text-xs" dir="ltr">
                    {nodes.find(n => n.id === item.source_node_id)?.status}
                  </p>
                </div>
                <ArrowRight className="text-muted-foreground size-4 rtl:rotate-180" />
                <div className="min-w-0">
                  <p className="text-muted-foreground text-xs">{t('خروجی', 'Exit')}</p>
                  <p className="mt-1 truncate text-sm font-medium">{nodeName(item.exit_node_id)}</p>
                  <p className="text-muted-foreground mt-1 text-xs" dir="ltr">
                    {nodes.find(n => n.id === item.exit_node_id)?.status}
                  </p>
                </div>
              </div>
              <div className="text-muted-foreground flex flex-wrap gap-x-5 gap-y-2 text-xs">
                <span>
                  {t('ورودی‌های کاربر', 'User inbounds')}: <bdi>{item.inbound_tags.join(', ')}</bdi>
                </span>
                <span>
                  {(item.domains || []).length
                    ? `${(item.domains || []).length} ${t('دامنه و زیردامنه', 'domain suffixes')}`
                    : t('تمام مقصدهای ورودی‌های منتخب', 'All destinations on selected inbounds')}
                </span>
                <span dir="ltr">
                  rev {item.revision} / applied {item.applied_revision}
                </span>
              </div>
              <div className="mt-5 flex flex-wrap gap-2 border-t pt-4">
                <Button size="sm" variant="outline" disabled={!!applying} onClick={() => open(item)}>
                  {t('ویرایش مسیر', 'Edit route')}
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={!!applying}
                  onClick={() =>
                    setConfirm({
                      title: t('تعویض کلید تونل', 'Rotate tunnel key'),
                      detail: t(
                        'کلید قبلی پس از اعمال روی نودها از اعتبار می‌افتد. تعویض کلید در حال اعمال می‌تواند ارتباط را موقتاً قطع کند.',
                        'The old key is revoked after deployment. Rotation can temporarily interrupt the link during deployment.',
                      ),
                      run: () => fetcher(`/api/tunnels/${item.id}/rotate`, { method: 'POST', body: JSON.stringify({ expected_revision: item.revision }) }),
                    })
                  }
                >
                  <KeyRound className="size-4" />
                  {t('تعویض کلید', 'Rotate key')}
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={!!applying || item.enabled || item.revision !== item.applied_revision}
                  onClick={() =>
                    setConfirm({
                      title: t('حذف مسیر', 'Delete route'),
                      detail: t('این مسیر غیرفعال از پنل حذف می‌شود.', 'Remove this disabled path from the panel.'),
                      run: () => fetcher(`/api/tunnels/${item.id}`, { method: 'DELETE', body: JSON.stringify({ expected_revision: item.revision }) }),
                    })
                  }
                >
                  <Trash2 className="size-4" />
                  {t('حذف', 'Delete')}
                </Button>
              </div>
              {companion(item.transport) && (
                <details className="mt-4 rounded-xl border p-3">
                  <summary className="cursor-pointer text-sm">{t('وضعیت و کنترل موتور', 'Engine runtime and control')}</summary>
                  <div className="mt-3 flex flex-wrap gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={!!applying}
                      onClick={() => mutate(async () => setOutput({ title: item.transport || 'SS2022', text: JSON.stringify(await fetcher(`/api/tunnels/${item.id}/engine/status`), null, 2) }))}
                    >
                      {t('وضعیت موتور', 'Engine status')}
                    </Button>
                    {(['source', 'exit'] as const).map(side =>
                      ['logs', 'restart'].map(action => (
                        <Button
                          key={side + action}
                          size="sm"
                          variant="ghost"
                          disabled={!!applying}
                          onClick={() => {
                            const run = async () =>
                              setOutput({
                                title: `${item.transport} ${side} · ${action}`,
                                text: JSON.stringify(
                                  await fetcher(`/api/tunnels/${item.id}/engine/action?action=${action}&side=${side}`, { method: 'POST', body: { expected_revision: item.revision } }),
                                  null,
                                  2,
                                ),
                              })
                            if (action === 'restart')
                              setConfirm({
                                title: t('راه‌اندازی مجدد حامل', 'Restart carrier'),
                                detail: t('ارتباط‌های فعال این حامل موقتاً قطع می‌شوند.', 'Active carrier connections will be interrupted.'),
                                run,
                              })
                            else mutate(run)
                          }}
                        >{`${side} · ${action}`}</Button>
                      )),
                    )}
                  </div>
                </details>
              )}
              {item.transport === 'paqet' && (
                <details className="mt-4 rounded-lg border p-3">
                  <summary className="cursor-pointer text-xs">{t('وضعیت، ابزارها و لاگ Paqet', 'Paqet state, tools and logs')}</summary>
                  <Button
                    className="mt-3"
                    size="sm"
                    variant="outline"
                    disabled={busy}
                    onClick={() =>
                      mutate(async () => setOutput({ title: t('وضعیت اجرای Paqet', 'Paqet runtime state'), text: JSON.stringify(await fetcher(`/api/tunnels/${item.id}/paqet/status`), null, 2) }))
                    }
                  >
                    {t('دریافت وضعیت دو نود', 'Read both node states')}
                  </Button>
                  <div className="mt-3 grid gap-4 sm:grid-cols-2">
                    {(['source', 'exit'] as const).map(side => (
                      <div key={side}>
                        <h3 className="mb-2 text-xs font-medium">{side === 'source' ? t('مبدا', 'Source') : t('خروجی', 'Exit')}</h3>
                        <div className="flex flex-wrap gap-2">
                          {['version', 'logs', side === 'source' ? 'ping' : 'dump', 'restart'].map(action => (
                            <Button
                              key={action}
                              size="sm"
                              variant="ghost"
                              disabled={!!applying}
                              onClick={() => {
                                const run = async () =>
                                  setOutput({
                                    title: `Paqet ${side} · ${action}`,
                                    text: JSON.stringify(
                                      await fetcher(`/api/tunnels/${item.id}/paqet/action?action=${action}&side=${side}`, {
                                        method: 'POST',
                                        body: JSON.stringify({ expected_revision: item.revision }),
                                      }),
                                      null,
                                      2,
                                    ),
                                  })
                                if (action === 'restart' || action === 'dump')
                                  setConfirm({
                                    title: action === 'restart' ? t('راه‌اندازی مجدد Paqet', 'Restart Paqet') : t('ضبط محدود بسته‌ها', 'Bounded packet capture'),
                                    detail:
                                      action === 'restart'
                                        ? t('ارتباط‌های فعال این حامل موقتاً قطع می‌شوند.', 'Active carrier connections will be interrupted.')
                                        : t('سه ثانیه از داده‌های تشخیصی شبکه نود ضبط می‌شود.', 'Capture up to three seconds of node network diagnostics.'),
                                    run,
                                  })
                                else mutate(run)
                              }}
                            >
                              {action}
                            </Button>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                </details>
              )}
            </article>
          ))}
        </section>
        <aside className="space-y-4">
          <div className={panel}>
            <ShieldCheck className="text-primary mb-3 size-5" />
            <h2 className="text-sm font-semibold">{t('مسیر داده، داخل پاسارگارد', 'Data path inside PasarGuard')}</h2>
            <p className="text-muted-foreground mt-3 text-xs leading-7">
              {t(
                'کاربر ← ورودی فعلی نود مبدا ← تونل رمز‌شده ← نود خروجی. احراز هویت و مصرف کاربر در مبدا باقی می‌ماند. برای بازی‌هایی با مقصد IP، حالت تمام مقصدها مناسب‌تر است.',
                'Client → existing source inbound → encrypted hop → exit node. User authentication and accounting remain at the source. IP-based games generally need all-destination routing.',
              )}
            </p>
          </div>
          <div className={panel}>
            <h2 className="text-sm font-semibold">{t('DNS و ترافیک بازی', 'DNS and game traffic')}</h2>
            <p className="text-muted-foreground mt-3 text-xs leading-7">
              {t(
                'تغییر DNS به‌تنهایی ترافیک بازی را تونل نمی‌کند. این مسیر TCP/UDP برای کاربران متصل به VPN است؛ انتخاب دامنه نیازمند ارسال نام دامنه توسط کلاینت یا sniffing متناسب در ورودی است. تطبیق دامنه برای همهٔ بازی‌ها تضمین نمی‌شود.',
                'DNS changes alone do not tunnel a game. This TCP/UDP path serves connected VPN clients. Domain matching requires client-supplied domains or suitable inbound sniffing; it cannot cover every game.',
              )}
            </p>
            <a className="text-primary mt-3 inline-block text-xs underline" href="#/dns">
              {t('مدیریت DNS و کاتالوگ سرویس‌ها', 'DNS and service catalog')}
            </a>
          </div>
          <div className={panel}>
            <h2 className="text-sm font-semibold">{t('نتیجهٔ آخرین اعمال', 'Last deployment')}</h2>
            {state.data?.applying ? (
              <p role="status" className="mt-3 text-sm">
                {t('اعمال روی نودها در حال انجام است…', 'Applying to nodes…')}
              </p>
            ) : (
              <>
                <p className="text-muted-foreground mt-3 text-xs">{String(state.data?.last_result?.state || t('هنوز اجرا نشده', 'Not run yet'))}</p>
                {((state.data?.last_result?.nodes || []) as { node_id: number; ok: boolean }[]).map(n => (
                  <div key={n.node_id} className="mt-3 flex items-center justify-between gap-2 text-xs">
                    <span>{nodeName(n.node_id)}</span>
                    <span className={n.ok ? 'text-primary' : 'text-destructive'}>{n.ok ? t('تأیید شد', 'Acknowledged') : t('ناموفق', 'Failed')}</span>
                  </div>
                ))}
              </>
            )}
            <p className="text-muted-foreground mt-4 border-t pt-3 text-xs leading-6">
              {t(
                'وضعیت نود، تأیید اعمال و سلامت مسیر بازی سه سنجهٔ جدا هستند. آزمون واقعی بازی هنوز گزارش نشده است.',
                'Node connectivity, deployment acknowledgement and game reachability are separate signals. Real game acceptance has not been reported.',
              )}
            </p>
          </div>
        </aside>
      </div>
      <section className={panel}>
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-sm font-semibold">{t('رویدادهای مدیریت تونل', 'Tunnel management events')}</h2>
          <div className="flex gap-2">
            <Button size="sm" variant="outline" disabled={!auditOffset} onClick={() => setAuditOffset(Math.max(0, auditOffset - 10))}>
              {t('قبلی', 'Previous')}
            </Button>
            <Button size="sm" variant="outline" disabled={(audit.data?.length || 0) < 10} onClick={() => setAuditOffset(auditOffset + 10)}>
              {t('بعدی', 'Next')}
            </Button>
          </div>
        </div>
        {audit.error && (
          <p role="alert" className="text-destructive text-sm">
            {t('دریافت رویدادها انجام نشد.', 'Could not load events.')}
          </p>
        )}
        <div className="max-h-64 space-y-3 overflow-auto">
          {audit.data?.map(event => (
            <div key={event.id} className="flex flex-wrap items-center justify-between gap-2 border-b pb-3 text-xs">
              <div className="flex flex-wrap gap-3">
                <bdi>{event.actor}</bdi>
                <bdi className="text-muted-foreground">{event.action}</bdi>
                <span>#{event.target_id}</span>
              </div>
              <time className="text-muted-foreground tabular-nums" dateTime={event.created_at}>
                {new Date(event.created_at).toLocaleString(fa ? 'fa-IR' : 'en-GB')}
              </time>
            </div>
          ))}
          {audit.data?.length === 0 && <p className="text-muted-foreground text-xs">{t('رویدادی ثبت نشده است.', 'No events yet.')}</p>}
        </div>
      </section>
      <Dialog
        open={output !== null}
        onOpenChange={v => {
          if (!v) setOutput(null)
        }}
      >
        <DialogContent className="max-h-[85dvh] overflow-auto sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{output?.title}</DialogTitle>
            <DialogDescription>
              {t('وضعیت فرایند، تأیید سلامت بازی نیست. Ping فقط ارسال بسته را گزارش می‌کند.', 'Process state does not confirm game reachability. Ping only reports packet sending.')}
            </DialogDescription>
          </DialogHeader>
          <pre dir="ltr" className="bg-muted max-h-96 overflow-auto rounded-lg p-3 text-xs break-all whitespace-pre-wrap">
            {output?.text}
          </pre>
        </DialogContent>
      </Dialog>
      <NativeProvisioning nodes={nodes} fa={fa} />
      <TunnelEngineCore nodes={nodes} fa={fa} />
      <NetworkOperations items={items} nodes={nodes} fa={fa} />
      <Dialog
        open={editing !== null}
        onOpenChange={value => {
          if (!busy && !value) {
            setEditing(null)
            setError('')
          }
        }}
      >
        <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-2xl" dir={fa ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>{editing === 'new' ? t('مسیر جدید بین نودها', 'New node path') : t('ویرایش مسیر', 'Edit path')}</DialogTitle>
            <DialogDescription>{t('تنظیمات ابتدا ذخیره می‌شوند. برای فعال‌شدن، آن‌ها را روی نودها اعمال کنید.', 'Save desired configuration, then apply it to nodes.')}</DialogDescription>
          </DialogHeader>
          {error && (
            <p role="alert" className="text-destructive text-sm">
              {error}
            </p>
          )}
          <form
            className="space-y-4"
            onSubmit={event => {
              event.preventDefault()
              const form = new FormData(event.currentTarget)
              const automatic = ((event.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null)?.value === 'automatic'
              mutate(async () => {
                const body = {
                  name: String(form.get('name')),
                  inbound_tags: tags,
                  domains: domains.split(/[\s,]+/).filter(Boolean),
                  port: Number(form.get('port')),
                  enabled: automatic || form.get('enabled') === 'on',
                  health_enabled: automatic || form.get('health_enabled') === 'on',
                  transport,
                  paqet_options: transport === 'paqet' ? JSON.parse(paqetText) : null,
                  carrier_options: companion(transport) ? JSON.parse(carrierText) : null,
                  ...(form.get('carrier_key') ? { carrier_key: String(form.get('carrier_key')) } : {}),
                  ...(form.get('paqet_key') ? { paqet_key: String(form.get('paqet_key')) } : {}),
                }
                const saved = await fetcher<TunnelView>(editing === 'new' ? '/api/tunnels' : `/api/tunnels/${editing?.id}`, {
                  method: editing === 'new' ? 'POST' : 'PUT',
                  body: JSON.stringify(editing === 'new' ? { ...body, source_node_id: Number(source), exit_node_id: Number(exit) } : { ...body, expected_revision: editing?.revision }),
                })
                if (automatic) {
                  await fetcher(`/api/provision/activate/${saved.id}`, { method: 'POST', body: { expected_revision: saved.revision } })
                  await client.invalidateQueries({ queryKey: ['native-provision'] })
                }
                setEditing(null)
                setNotice(
                  automatic
                    ? t('راه‌اندازی خودکار در صف قرار گرفت؛ پیشرفت را در بخش نصب دنبال کنید.', 'Automatic activation queued. Follow its progress in setup.')
                    : t('مسیر ذخیره شد؛ اعمال روی نودها را انجام دهید.', 'Path saved. Apply it to nodes.'),
                )
              })
            }}
          >
            <label className="block space-y-2 text-sm">
              <span>{t('حامل بین نودها', 'Inter-node carrier')}</span>
              <select
                name="transport"
                className={selectClass}
                disabled={editing !== 'new'}
                value={transport}
                onChange={e => {
                  setTransport(e.target.value as CarrierTransport)
                  setCarrierText('{}')
                }}
              >
                <option value="shadowsocks-2022">Shadowsocks 2022</option>
                <option value="paqet">Paqet · KCP over raw TCP</option>
                <option value="backhaul">Backhaul · reverse TCP + UDP</option>
                <option value="gost">GOST · TCP / WS / TLS / WSS</option>
                <option value="frp">FRP · TCP / WebSocket / KCP / QUIC</option>
              </select>
            </label>
            <label className="block space-y-2 text-sm">
              <span>{t('نام مسیر', 'Path name')}</span>
              <Input name="name" required maxLength={80} defaultValue={editing && editing !== 'new' ? editing.name : ''} />
            </label>
            <div className="grid gap-4 sm:grid-cols-2">
              <label className="block space-y-2 text-sm">
                <span>{t('نود مبدا', 'Source node')}</span>
                <select
                  name="source"
                  required
                  className={selectClass}
                  disabled={editing !== 'new'}
                  value={source}
                  onChange={e => {
                    setSource(e.target.value)
                    setTags([])
                  }}
                >
                  <option value="">{t('انتخاب نود', 'Select node')}</option>
                  {nodes
                    .filter(n => n.core_type === 'xray')
                    .map(n => (
                      <option key={n.id} value={n.id}>
                        {n.name}
                      </option>
                    ))}
                </select>
              </label>
              <label className="block space-y-2 text-sm">
                <span>{t('نود خروجی', 'Exit node')}</span>
                <select name="exit" required className={selectClass} disabled={editing !== 'new'} value={exit} onChange={e => setExit(e.target.value)}>
                  <option value="">{t('انتخاب نود', 'Select node')}</option>
                  {nodes
                    .filter(n => n.core_type === 'xray' && String(n.id) !== source)
                    .map(n => (
                      <option key={n.id} value={n.id}>
                        {n.name}
                      </option>
                    ))}
                </select>
              </label>
            </div>
            <fieldset className="rounded-xl border p-3">
              <legend className="px-1 text-sm">{t('ورودی‌های کاربران روی مبدا', 'Source user inbounds')}</legend>
              <div className="flex flex-wrap gap-3">
                {(nodes.find(n => String(n.id) === source)?.inbound_tags || []).map(tag => (
                  <label key={tag} className="flex items-center gap-2 text-xs">
                    <input type="checkbox" checked={tags.includes(tag)} onChange={e => setTags(e.target.checked ? [...tags, tag] : tags.filter(v => v !== tag))} />
                    <bdi>{tag}</bdi>
                  </label>
                ))}
              </div>
              {!tags.length && <p className="text-muted-foreground mt-2 text-xs">{t('حداقل یک ورودی انتخاب کنید.', 'Select at least one inbound.')}</p>}
            </fieldset>
            <div className="grid gap-4 sm:grid-cols-2">
              <label className="block space-y-2 text-sm">
                <span>
                  {companion(transport)
                    ? t('پورت کنترل حامل', 'Carrier control port')
                    : transport === 'paqet'
                      ? t('پورت raw TCP خروجی', 'Exit raw TCP port')
                      : t('پورت TCP/UDP خروجی', 'Exit TCP/UDP port')}
                </span>
                <Input name="port" type="number" min={1024} max={65535} required defaultValue={editing && editing !== 'new' ? editing.port : 40900} />
                <span className="text-muted-foreground block text-xs leading-6">
                  {t('فایروال خروجی باید هر دو پروتکل را از مبدا بپذیرد.', 'Exit firewall must accept both protocols from the source.')}
                </span>
              </label>
              <label className="flex items-center gap-2 text-sm">
                <input name="enabled" type="checkbox" defaultChecked={editing && editing !== 'new' ? editing.enabled : false} />
                {t('فعال در تنظیمات', 'Enabled in desired state')}
              </label>
            </div>
            <label className="flex items-start gap-2 rounded-xl border p-3 text-sm">
              <input name="health_enabled" type="checkbox" defaultChecked={editing && editing !== 'new' ? editing.health_enabled : false} />
              <span>
                {t('پایش واقعی حامل TCP/UDP؛ نیازمند نود همین فورک روی هر دو سمت و اعمال مجدد مسیر', 'Measure encrypted TCP/UDP carrier; requires fork nodes on both sides and a fresh apply')}
              </span>
            </label>
            <label className="block space-y-2 text-sm">
              <span>{t('دامنه‌ها؛ خالی یعنی تمام مقصدها', 'Domain suffixes; empty means all destinations')}</span>
              <textarea
                name="domains"
                className="border-input bg-background min-h-28 w-full rounded-md border p-3 text-xs"
                dir="ltr"
                value={domains}
                onChange={e => setDomains(e.target.value)}
                placeholder="example.com"
              />
              <span className="text-muted-foreground text-xs">{t('هر خط یک دامنه؛ زیردامنه‌ها نیز شامل می‌شوند.', 'One domain per line; subdomains are included.')}</span>
            </label>
            <details className="rounded-xl border p-3">
              <summary className="cursor-pointer text-sm">{t('افزودن از کاتالوگ بازی و سرویس', 'Add from game and service catalog')}</summary>
              <Input className="my-3" value={search} onChange={e => setSearch(e.target.value)} placeholder={t('جستجوی سرویس', 'Search services')} />
              <div className="max-h-48 space-y-2 overflow-auto">
                {catalog.data?.services
                  .filter(s => `${s.key} ${s.label}`.toLowerCase().includes(search.toLowerCase()))
                  .map(service => (
                    <div key={service.key} className="flex flex-wrap items-center justify-between gap-2 border-b py-2 text-xs">
                      <span>{service.label}</span>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={() => setDomains([...new Set([...domains.split(/[\s,]+/).filter(Boolean), ...service.groups.flatMap(g => g.domains)])].join('\n'))}
                      >
                        {t('افزودن دامنه‌ها', 'Add domains')}
                      </Button>
                    </div>
                  ))}
              </div>
            </details>
            {companion(transport) && (
              <>
                <CarrierEditor engine={transport} value={carrierText} onChange={setCarrierText} fa={fa} />
                <label className="block space-y-2 text-sm">
                  <span>{t('کلید حامل؛ خالی = تولید یا حفظ', 'Carrier key; blank = generate or retain')}</span>
                  <Input name="carrier_key" type="password" autoComplete="new-password" minLength={32} maxLength={256} />
                </label>
              </>
            )}
            {transport === 'paqet' && (
              <>
                <PaqetEditor value={paqetText} onChange={setPaqetText} fa={fa} />
                <label className="block space-y-2 text-sm">
                  <span>{t('کلید جدید Paqet؛ خالی = تولید یا حفظ کلید', 'New Paqet key; blank = generate or retain')}</span>
                  <Input name="paqet_key" type="password" autoComplete="new-password" />
                </label>
                <div className="flex flex-wrap gap-2">
                  {[
                    [source, t('بررسی نود مبدا', 'Check source node')],
                    [exit, t('بررسی نود خروجی', 'Check exit node')],
                  ].map(([id, label]) => (
                    <Button
                      key={label}
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={!id || busy}
                      onClick={() =>
                        mutate(async () => {
                          const result = await fetcher<{ engine: string; installed: boolean }>(`/api/tunnels/nodes/${id}/paqet`)
                          setNotice(`${label}: ${result.engine} · ${result.installed ? t('نصب شده', 'Installed') : t('نصب نشده', 'Not installed')}`)
                        })
                      }
                    >
                      {label}
                    </Button>
                  ))}
                </div>
                {notice && (
                  <p role="status" className="text-sm">
                    {notice}
                  </p>
                )}
              </>
            )}
            <DialogFooter>
              <Button type="submit" name="action" value="automatic" disabled={busy || !tags.length || !source || !exit || source === exit}>
                {t('ذخیره و راه‌اندازی خودکار', 'Save and activate automatically')}
              </Button>
              <Button type="submit" variant="outline" disabled={busy || !tags.length || !source || !exit || source === exit}>
                {t('ذخیره مسیر', 'Save path')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
      <Dialog
        open={confirm !== null}
        onOpenChange={value => {
          if (!busy && !value) {
            setConfirm(null)
            setError('')
          }
        }}
      >
        <DialogContent dir={fa ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>{confirm?.title}</DialogTitle>
            <DialogDescription className="leading-7">{confirm?.detail}</DialogDescription>
          </DialogHeader>
          {error && (
            <p role="alert" className="text-destructive text-sm">
              {error}
            </p>
          )}
          <DialogFooter>
            <Button variant="outline" disabled={busy} onClick={() => setConfirm(null)}>
              {t('انصراف', 'Cancel')}
            </Button>
            <Button disabled={busy} onClick={() => confirm && mutate(confirm.run)}>
              {busy ? t('در حال انجام…', 'Working…') : t('تأیید و اجرا', 'Confirm and run')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
