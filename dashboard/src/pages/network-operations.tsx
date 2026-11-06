import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog'
import { fetcher } from '@/service/http'
import type { TunnelView, TunnelNodeOption } from '@/service/api'

type Health = {
  tunnel_id: number
  revision: number
  checked_at: string | null
  result: { healthy?: boolean; status?: string; tcp_rtt_ms?: number; udp_rtt_ms?: number; udp_loss_percent?: number; sample_mbps?: number }
}
type Policy = {
  source_node_id: number
  candidates: number[]
  revision: number
  enabled: boolean
  suspended: boolean
  failure_threshold: number
  cooldown_seconds: number
  last_result: { state?: string }
}
type Check = { code: string; status: string; detail: string; name?: string; days_remaining?: number; free_percent?: number }

export default function NetworkOperations({ items, nodes, fa }: { items: TunnelView[]; nodes: TunnelNodeOption[]; fa: boolean }) {
  const t = (f: string, e: string) => (fa ? f : e)
  const client = useQueryClient()
  const health = useQuery({ queryKey: ['carrier-health'], queryFn: () => fetcher<Health[]>('/api/tunnels/health/state'), refetchInterval: 15000 })
  const policies = useQuery({ queryKey: ['failover-policies'], queryFn: () => fetcher<Policy[]>('/api/tunnels/failover/state'), refetchInterval: 15000 })
  const operations = useQuery({ queryKey: ['native-operations'], queryFn: () => fetcher<{ checks: Check[]; critical: number; warnings: number }>('/api/operations/readiness'), refetchInterval: 30000 })
  const [editing, setEditing] = useState<Policy | 'new' | null>(null)
  const [source, setSource] = useState('')
  const [members, setMembers] = useState<number[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [recovery, setRecovery] = useState<{
    fingerprint: string
    nodes: { node_id: number; name: string; available: boolean; required: boolean; paqet_links: { id: string; revision: number; orphan: boolean; enabled: boolean; engine?: string }[] }[]
  } | null>(null)
  const [deleting, setDeleting] = useState<Policy | null>(null)
  const execute = async (run: () => Promise<unknown>) => {
    setBusy(true)
    setError('')
    try {
      await run()
      await Promise.all([
        client.invalidateQueries({ queryKey: ['carrier-health'] }),
        client.invalidateQueries({ queryKey: ['failover-policies'] }),
        client.invalidateQueries({ queryKey: ['native-operations'] }),
        client.invalidateQueries({ queryKey: ['native-tunnels'] }),
      ])
    } catch (error) {
      const detail = (error as { data?: { detail?: unknown } }).data?.detail
      setError(typeof detail === 'string' ? detail : t('عملیات انجام نشد؛ تنظیمات و وضعیت نودها را بررسی کنید.', 'Operation failed. Review settings and node state.'))
    } finally {
      setBusy(false)
    }
  }
  const open = (policy: Policy | 'new') => {
    setEditing(policy)
    setSource(policy === 'new' ? '' : String(policy.source_node_id))
    setMembers(policy === 'new' ? [] : policy.candidates)
    setError('')
  }
  const name = (id: number) => nodes.find(node => node.id === id)?.name || `#${id}`
  const labels: Record<string, string> = {
    disk: t('فضای ذخیره‌سازی', 'Storage'),
    node_certificate: t('گواهی نود', 'Node certificate'),
    panel_certificate: t('گواهی پنل', 'Panel certificate'),
    backup: t('پشتیبان و بازیابی', 'Backup and recovery'),
    carrier: t('ترافیک تونل', 'Carrier traffic'),
    failover: t('بازیابی مسیر', 'Failover'),
    smartdns: 'SmartDNS',
  }
  return (
    <details className="bg-card rounded-2xl border p-5">
      <summary className="cursor-pointer text-sm font-semibold">
        {t('پایش مسیر، بازیابی و سلامت سرویس‌ها', 'Traffic monitoring, recovery and service health')}{' '}
        {operations.data && (
          <span className="text-muted-foreground ms-2 text-xs">
            {operations.data.critical} {t('خطا', 'critical')} · {operations.data.warnings} {t('هشدار', 'warnings')}
          </span>
        )}
      </summary>
      <div className="mt-5 space-y-6">
        {(error || health.error || policies.error || operations.error) && (
          <p role="alert" className="text-destructive text-sm">
            {error || t('دریافت وضعیت کامل نشد.', 'Could not load operational status.')}
          </p>
        )}
        <section className="space-y-3">
          <h2 className="font-medium">{t('آزمون واقعی حامل رمز‌شده', 'Measured encrypted carrier')}</h2>
          <p className="text-muted-foreground text-xs leading-6">
            {t(
              'TCP و UDP از داخل حامل تا خروجی آزمون می‌شوند. این سنجش جای تست کانفیگ کاربر و بازی روی دستگاه واقعی را نمی‌گیرد. نرخ انتقال، نمونهٔ کوتاه است.',
              'TCP and UDP are tested through the encrypted carrier. User configuration and real game acceptance remain separate. Transfer rate is a short sample.',
            )}
          </p>
          {items.map(item => {
            const sample = health.data?.find(sample => sample.tunnel_id === item.id && sample.revision === item.revision)
            return (
              <div key={item.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border p-3 text-sm">
                <div>
                  <div className="font-medium">{item.name}</div>
                  <div className="text-muted-foreground mt-1 text-xs">
                    {sample?.checked_at ? new Date(sample.checked_at).toLocaleString(fa ? 'fa-IR' : 'en-US') : t('هنوز سنجیده نشده', 'Not measured yet')} ·{' '}
                    {sample?.result.status === 'measured'
                      ? sample.result.healthy
                        ? t('عبور ترافیک تأیید شد', 'Traffic passed')
                        : t('عبور ترافیک ناموفق', 'Traffic failed')
                      : t('نتیجهٔ معتبر فعلی موجود نیست', 'No current valid sample')}
                  </div>
                  {sample?.result.status === 'measured' && (
                    <div dir="ltr" className="mt-2 text-xs">
                      TCP {sample.result.tcp_rtt_ms?.toFixed(1)} ms · UDP {sample.result.udp_rtt_ms?.toFixed(1)} ms · Loss {sample.result.udp_loss_percent?.toFixed(1)}% ·{' '}
                      {sample.result.sample_mbps?.toFixed(2)} Mbps
                    </div>
                  )}
                </div>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy || !item.enabled || !item.health_enabled || item.revision !== item.applied_revision}
                  onClick={() => execute(() => fetcher(`/api/tunnels/${item.id}/health`, { method: 'POST', body: { expected_revision: item.revision } }))}
                >
                  {t('سنجش TCP/UDP', 'Measure TCP/UDP')}
                </Button>
              </div>
            )
          })}
        </section>
        <section className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className="font-medium">{t('مسیر جایگزین خودکار', 'Automatic fallback path')}</h2>
            <Button size="sm" variant="outline" onClick={() => open('new')}>
              {t('سیاست جدید', 'New policy')}
            </Button>
          </div>
          <p className="text-muted-foreground text-xs leading-6">
            {t(
              'پس از خطاهای پی‌درپی، خروجی جایگزین با همان ورودی و دامنه‌ها اعمال و آزمون می‌شود. شکست جایگزین، بازگشت به تنظیم قبلی و توقف اتوماسیون را به دنبال دارد. بازگشت خودکار به خروجی اصلی انجام نمی‌شود.',
              'After consecutive measured failures, an equivalent standby route is applied and verified. A failed candidate restores prior settings and suspends automation. Failback to the original exit is manual.',
            )}
          </p>
          {policies.data?.map(policy => (
            <div key={policy.source_node_id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border p-3 text-sm">
              <div>
                <span className="font-medium">{name(policy.source_node_id)}</span>
                <p className="text-muted-foreground mt-1 text-xs">
                  {policy.suspended
                    ? t('متوقف؛ نیاز به بررسی و فعال‌سازی مجدد', 'Suspended; review and resume')
                    : policy.enabled
                      ? t('پایش خودکار فعال', 'Automatic monitoring enabled')
                      : t('غیرفعال', 'Disabled')}{' '}
                  · {policy.last_result.state || '—'}
                </p>
              </div>
              <div className="flex gap-2">
                <Button size="sm" variant="outline" onClick={() => open(policy)}>
                  {t('تنظیم / ادامه', 'Configure / resume')}
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setDeleting(policy)}>
                  {t('حذف سیاست', 'Remove policy')}
                </Button>
              </div>
            </div>
          ))}
        </section>
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border p-3 text-sm">
          <div>
            <p className="font-medium">{t('همگام‌سازی پس از بازیابی پشتیبان', 'Reconcile after restoring a backup')}</p>
            <p className="text-muted-foreground mt-1 text-xs">
              {t(
                'نسخه‌های ذخیره‌شده روی نود ممکن است از پشتیبان جلوتر باشند. ابتدا موجودی را بررسی کنید؛ سپس مسیرها را دوباره اعمال کنید.',
                'Node journals may be ahead of the backup. Review runtime inventory before reconciling and reapplying routes.',
              )}
            </p>
          </div>
          <Button variant="outline" disabled={busy} onClick={() => execute(async () => setRecovery(await fetcher('/api/operations/runtime-recovery')))}>
            {t('بررسی موجودی runtime', 'Inspect runtime inventory')}
          </Button>
        </div>
        <section className="space-y-3">
          <h2 className="font-medium">{t('هشدارهای نگهداری', 'Maintenance alerts')}</h2>
          <div className="grid gap-2 sm:grid-cols-2">
            {operations.data?.checks.map((check, index) => (
              <div
                key={index}
                className={`rounded-xl border p-3 text-xs ${check.status === 'critical' ? 'border-destructive/50 text-destructive' : check.status === 'warning' ? 'border-amber-500/40' : ''}`}
              >
                <p className="font-medium">
                  {labels[check.code] || check.code}
                  {check.name && ` · ${check.name}`} ·{' '}
                  {check.status === 'ok'
                    ? t('تأیید', 'OK')
                    : check.status === 'critical'
                      ? t('نیاز به اقدام', 'Action required')
                      : check.status === 'warning'
                        ? t('نیاز به بررسی', 'Review')
                        : t('اطلاع', 'Info')}
                </p>
                <p className="text-muted-foreground mt-2 leading-6" dir="ltr">
                  {check.detail}
                  {check.days_remaining !== undefined && ` · ${check.days_remaining} days`}
                  {check.free_percent !== undefined && ` · ${check.free_percent}% free`}
                </p>
              </div>
            ))}
          </div>
        </section>
      </div>
      <Dialog
        open={!!editing}
        onOpenChange={open => {
          if (!open && !busy) setEditing(null)
        }}
      >
        <DialogContent className="max-h-[85dvh] overflow-auto" dir={fa ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>{t('سیاست جایگزینی خروجی', 'Exit failover policy')}</DialogTitle>
            <DialogDescription>
              {t(
                'نامزدها باید مبدا، ورودی‌ها و دامنه‌های یکسان و پایش فعال داشته باشند. قبل از فعال‌سازی، مسیر فعلی را با موفقیت بسنجید.',
                'Candidates require the same source/inbounds/domains and enabled monitoring. Successfully measure the active path before enabling.',
              )}
            </DialogDescription>
          </DialogHeader>
          <form
            className="space-y-4"
            onSubmit={event => {
              event.preventDefault()
              const form = new FormData(event.currentTarget)
              execute(async () => {
                await fetcher(`/api/tunnels/failover/${source}`, {
                  method: 'PUT',
                  body: {
                    expected_revision: editing && editing !== 'new' ? editing.revision : 0,
                    candidates: members,
                    enabled: form.get('enabled') === 'on',
                    failure_threshold: Number(form.get('threshold')),
                    cooldown_seconds: Number(form.get('cooldown')),
                  },
                })
                setEditing(null)
              })
            }}
          >
            <label className="block space-y-2 text-sm">
              <span>{t('نود مبدا', 'Source node')}</span>
              <select
                aria-label={t('نود مبدا', 'Source node')}
                className="bg-background h-10 w-full rounded-md border px-3"
                value={source}
                required
                disabled={editing !== 'new'}
                onChange={event => {
                  setSource(event.target.value)
                  setMembers([])
                }}
              >
                <option value="">—</option>
                {nodes.map(node => (
                  <option key={node.id} value={node.id}>
                    {node.name}
                  </option>
                ))}
              </select>
            </label>
            <fieldset className="space-y-2">
              <legend className="mb-2 text-sm">{t('مسیرهای نامزد؛ به ترتیب اولویت', 'Candidate paths in priority order')}</legend>
              {items
                .filter(item => item.source_node_id === Number(source) && item.health_enabled)
                .map(item => (
                  <label key={item.id} className="flex gap-2 text-sm">
                    <input type="checkbox" checked={members.includes(item.id)} onChange={event => setMembers(event.target.checked ? [...members, item.id] : members.filter(id => id !== item.id))} />
                    {members.includes(item.id) && `${members.indexOf(item.id) + 1}. `}
                    {item.name}
                  </label>
                ))}
            </fieldset>
            <label className="block space-y-2 text-sm">
              <span>{t('تعداد خطای متوالی', 'Consecutive failures')}</span>
              <Input name="threshold" type="number" min={2} max={10} defaultValue={editing && editing !== 'new' ? editing.failure_threshold : 3} required />
            </label>
            <label className="block space-y-2 text-sm">
              <span>{t('حداقل فاصلهٔ جابه‌جایی؛ ثانیه', 'Minimum switch interval; seconds')}</span>
              <Input name="cooldown" type="number" min={120} max={86400} defaultValue={editing && editing !== 'new' ? editing.cooldown_seconds : 300} required />
            </label>
            <label className="flex gap-2 text-sm">
              <input name="enabled" type="checkbox" defaultChecked={editing && editing !== 'new' ? editing.enabled : false} />
              {t('فعال‌سازی پایش و جایگزینی خودکار', 'Enable monitoring and automatic failover')}
            </label>
            {error && (
              <p role="alert" className="text-destructive text-sm">
                {error}
              </p>
            )}
            <Button type="submit" disabled={busy || members.length < 2}>
              {busy ? t('در حال ذخیره…', 'Saving…') : t('ذخیره سیاست', 'Save policy')}
            </Button>
          </form>
        </DialogContent>
      </Dialog>
      <Dialog
        open={!!recovery}
        onOpenChange={open => {
          if (!open && !busy) setRecovery(null)
        }}
      >
        <DialogContent className="max-h-[85dvh] overflow-auto" dir={fa ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>{t('تطبیق runtime با پشتیبان', 'Reconcile runtime with restored state')}</DialogTitle>
            <DialogDescription>
              {t(
                'سابلینک‌های وابسته تا اعمال مجدد مخفی می‌شوند و اتوماسیون خاموش می‌شود. engine یتیم فقط با تأیید صریح متوقف خواهد شد.',
                'Dependent subscription hosts are hidden until reapply and automation is disabled. Orphan engines stop only with explicit authorization.',
              )}
            </DialogDescription>
          </DialogHeader>
          <form
            className="space-y-4"
            onSubmit={event => {
              event.preventDefault()
              const data = new FormData(event.currentTarget)
              execute(async () => {
                const result = await fetcher<{ ok: boolean }>('/api/operations/runtime-recovery', {
                  method: 'POST',
                  body: { fingerprint: recovery?.fingerprint, disable_orphans: data.get('orphans') === 'on' },
                })
                if (!result.ok) throw new Error('Recovery needs repair')
                setRecovery(null)
              })
            }}
          >
            {recovery?.nodes.map(node => (
              <div className="rounded-xl border p-3 text-xs" key={node.node_id}>
                <p className="font-medium">
                  {node.name} · {node.available ? t('بررسی شد', 'Inspected') : t('در دسترس نیست', 'Unavailable')}
                </p>
                {node.paqet_links
                  .filter(link => link.orphan && link.enabled)
                  .map(link => (
                    <p dir="ltr" className="mt-2 break-all" key={`${link.engine || 'paqet'}-${link.id}`}>
                      {t('engine یتیم', 'Orphan engine')}: {link.engine || 'paqet'} · {link.id} · r{link.revision}
                    </p>
                  ))}
              </div>
            ))}
            <label className="flex gap-2 text-sm">
              <input type="checkbox" name="orphans" />
              {t('توقف engineهای یتیمِ نمایش‌داده‌شده را تأیید می‌کنم.', 'Authorize stopping the displayed orphan engines.')}
            </label>
            {error && (
              <p role="alert" className="text-destructive text-sm">
                {error}
              </p>
            )}
            <Button type="submit" disabled={busy || recovery?.nodes.some(node => node.required && !node.available)}>
              {t('تأیید تطبیق؛ اعمال مجدد لازم است', 'Confirm reconciliation; reapply required')}
            </Button>
          </form>
        </DialogContent>
      </Dialog>
      <Dialog
        open={!!deleting}
        onOpenChange={open => {
          if (!open && !busy) setDeleting(null)
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('حذف سیاست جایگزینی', 'Remove failover policy')}</DialogTitle>
            <DialogDescription>{t('مسیر فعال حفظ می‌شود و جابه‌جایی خودکار این گروه متوقف می‌شود.', 'The active route remains; automatic switching for this group stops.')}</DialogDescription>
          </DialogHeader>
          <Button
            disabled={busy}
            onClick={() =>
              execute(async () => {
                await fetcher(`/api/tunnels/failover/${deleting?.source_node_id}`, { method: 'DELETE', body: { expected_revision: deleting?.revision } })
                setDeleting(null)
              })
            }
          >
            {t('تأیید حذف سیاست', 'Confirm policy removal')}
          </Button>
        </DialogContent>
      </Dialog>
    </details>
  )
}
