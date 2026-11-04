import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Zap } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { fetcher } from '@/service/http'
import type { TunnelNodeOption } from '@/service/api'

type Candidate = { id: string; label: string; compatible: boolean; reason: string | null }
type Plan = {
  ok: boolean
  blockers: string[]
  warnings: string[]
  candidates: Candidate[]
  max_deployments: number
}
type Metrics = { tcp?: boolean; udp?: boolean; tcp_rtt_ms?: number; udp_rtt_ms?: number; udp_loss_percent?: number; sample_mbps?: number }
type Step = { id: string; label: string; state: string; reason: string | null; metrics: Metrics | null; score: number | null }
type Job = {
  id: string
  kind: string
  state: string
  progress: number
  result: { code?: string; steps?: Step[]; winner?: { id: string; label: string; tunnel_id: number; score: number }; cleanup_required?: string[] }
}

const blockers: Record<string, [string, string]> = {
  source_not_connected: ['نود مبدأ متصل نیست.', 'The source node is not connected.'],
  exit_not_connected: ['نود خروجی متصل نیست.', 'The exit node is not connected.'],
  deployment_in_progress: ['یک اعمال تنظیمات در جریان است.', 'A deployment is already in progress.'],
  wizard_already_running: ['یک اجرای تونل سریع فعال است.', 'A quick-tunnel run is already active.'],
  source_has_active_tunnel: ['این مبدأ از قبل یک تونل فعال دارد؛ ابتدا آن را غیرفعال و اعمال کنید.', 'This source already has an active tunnel. Disable and apply it first.'],
  exit_is_active_source: ['نود خروجی خودش مبدأ یک تونل فعال است.', 'The exit node is itself an active tunnel source.'],
  source_is_active_exit: ['نود مبدأ خروجی یک تونل فعال است.', 'The source node is an active tunnel exit.'],
  tunnel_capacity: ['ظرفیت ۱۶ تونل کافی نیست؛ مسیرهای بلااستفاده را حذف کنید.', 'Not enough room under the 16-tunnel limit. Remove unused paths.'],
  source_core_not_xray: ['هستهٔ مبدأ باید Xray باشد.', 'The source core must be Xray.'],
  exit_core_not_xray: ['هستهٔ خروجی باید Xray باشد.', 'The exit core must be Xray.'],
  inbound_unavailable: ['ورودی انتخاب‌شده در هستهٔ مبدأ نیست یا کنار گذاشته شده.', 'The selected inbound is missing from the source core or excluded.'],
  no_free_port: ['پورت آزاد برای حامل پیدا نشد.', 'No free carrier port was found.'],
  no_compatible_carrier: [
    'هیچ حاملی روی هر دو نود نصب و سازگار نیست؛ ابتدا نصب خودکار نود را اجرا کنید.',
    'No carrier is installed and compatible on both nodes. Run the automatic node install first.',
  ],
}
const warnings: Record<string, [string, string]> = {
  firewall_not_managed_source: ['فایروال مبدأ توسط پنل مدیریت نمی‌شود؛ پورت‌ها را دستی باز کنید.', 'The source firewall is not managed by the panel. Open the ports yourself.'],
  firewall_not_managed_exit: ['فایروال خروجی توسط پنل مدیریت نمی‌شود؛ پورت‌ها را دستی باز کنید.', 'The exit firewall is not managed by the panel. Open the ports yourself.'],
}
const stateLabels: Record<string, [string, string]> = {
  pending: ['در انتظار', 'Pending'],
  deploying: ['در حال اعمال', 'Deploying'],
  measuring: ['در حال سنجش', 'Measuring'],
  healthy: ['سالم', 'Healthy'],
  unhealthy: ['ناسالم', 'Unhealthy'],
  apply_failed: ['اعمال ناموفق', 'Apply failed'],
  unconfirmed: ['تأیید نشد', 'Unconfirmed'],
  skipped: ['رد شد', 'Skipped'],
}
const jobLabels: Record<string, [string, string]> = {
  queued: ['در صف اجرا', 'Queued'],
  preparing: ['آماده‌سازی', 'Preparing'],
  testing: ['آزمون حامل‌ها', 'Testing carriers'],
  selecting: ['انتخاب بهترین مسیر', 'Selecting the best path'],
  ready: ['بهترین مسیر فعال شد', 'Best path is active'],
  failed: ['هیچ مسیر سالمی پیدا نشد', 'No healthy path found'],
  cancelled: ['لغو شد', 'Cancelled'],
  interrupted: ['اجرای ناتمام', 'Interrupted'],
}
const finished = ['ready', 'failed', 'cancelled', 'interrupted']

const number = (value: number | undefined, digits = 0) => (typeof value === 'number' ? value.toFixed(digits) : '—')

export default function QuickTunnelWizard({ nodes, fa }: { nodes: TunnelNodeOption[]; fa: boolean }) {
  const t = (f: string, e: string) => (fa ? f : e)
  const pick = (table: Record<string, [string, string]>, key: string) => (table[key] ? table[key][fa ? 0 : 1] : key)
  const client = useQueryClient()
  const [open, setOpen] = useState(false)
  const [source, setSource] = useState('')
  const [exit, setExit] = useState('')
  const [tags, setTags] = useState<string[]>([])
  const [mode, setMode] = useState<'fast' | 'thorough'>('fast')
  const [priority, setPriority] = useState<'balanced' | 'latency' | 'throughput'>('balanced')
  const [plan, setPlan] = useState<Plan | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [jobId, setJobId] = useState<string | null>(null)

  const job = useQuery({
    queryKey: ['quick-tunnel-job', jobId],
    queryFn: () => fetcher<Job>(`/api/provision/jobs/${jobId}`),
    enabled: !!jobId,
    refetchInterval: query => (query.state.data && finished.includes(query.state.data.state) ? false : 2000),
  })
  const running = !!jobId && !(job.data && finished.includes(job.data.state))
  const available = nodes.find(node => String(node.id) === source)?.inbound_tags ?? []

  const report = (value: unknown) => {
    const detail = (value as { data?: { detail?: unknown } }).data?.detail
    const list = detail && typeof detail === 'object' && 'blockers' in detail ? (detail as { blockers: string[] }).blockers : null
    setError(
      list
        ? list.map(code => pick(blockers, code)).join(' · ')
        : typeof detail === 'string'
          ? detail
          : t('درخواست انجام نشد؛ اتصال و ورودی‌ها را بررسی کنید.', 'Request failed. Review connectivity and input.'),
    )
  }
  const body = () => ({ source_node_id: Number(source), exit_node_id: Number(exit), inbound_tags: tags, mode, priority })
  const ready = !!source && !!exit && source !== exit && tags.length > 0
  const reset = () => {
    setPlan(null)
    setConfirmed(false)
    setError('')
  }
  const check = async () => {
    setBusy(true)
    reset()
    try {
      setPlan(await fetcher<Plan>('/api/tunnels/wizard/plan', { method: 'POST', body: body() }))
    } catch (value) {
      report(value)
    } finally {
      setBusy(false)
    }
  }
  const start = async () => {
    setBusy(true)
    setError('')
    try {
      const created = await fetcher<Job>('/api/tunnels/wizard', { method: 'POST', body: { ...body(), confirm_restart: true } })
      setJobId(created.id)
      client.invalidateQueries({ queryKey: ['native-provision'] })
    } catch (value) {
      report(value)
    } finally {
      setBusy(false)
    }
  }
  const cancel = async () => {
    if (!jobId) return
    try {
      await fetcher(`/api/provision/jobs/${jobId}/cancel`, { method: 'POST' })
      await job.refetch()
    } catch (value) {
      report(value)
    }
  }
  const close = () => {
    if (running || busy) return
    if (job.data?.state === 'ready') client.invalidateQueries({ queryKey: ['native-tunnels'] })
    setOpen(false)
    setJobId(null)
    reset()
  }
  const steps = job.data?.result.steps ?? []
  const winner = job.data?.result.winner

  return (
    <section className="bg-card space-y-3 rounded-2xl border p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 font-medium">
            <Zap className="size-4" />
            {t('تونل سریع', 'Quick tunnel')}
          </h2>
          <p className="text-muted-foreground mt-2 max-w-3xl text-xs leading-6">
            {t(
              'مبدأ، خروجی و ورودی کاربران را انتخاب کنید؛ پنل حامل‌های سازگار را یکی‌یکی می‌سازد، TCP/UDP واقعی را می‌سنجد و بهترین مسیر را فعال نگه می‌دارد. بقیه حذف می‌شوند.',
              'Pick the source, exit and user inbounds. The panel builds each compatible carrier in turn, measures real TCP/UDP and keeps the best path active. The rest are removed.',
            )}
          </p>
        </div>
        <Button onClick={() => setOpen(true)}>{t('شروع تونل سریع', 'Start quick tunnel')}</Button>
      </div>
      <Dialog open={open} onOpenChange={value => (value ? setOpen(true) : close())}>
        <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-2xl" dir={fa ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>{t('تونل سریع', 'Quick tunnel')}</DialogTitle>
            <DialogDescription>
              {t(
                'موفقیت یعنی عبور واقعی TCP و UDP روی حامل رمز‌شده سنجیده شد. ورودی کاربران، گواهی و بازی موردنظر هنوز آزمون نشده‌اند.',
                'Success means real TCP and UDP passed over the encrypted carrier. User inbounds, certificates and your game have not been probed.',
              )}
            </DialogDescription>
          </DialogHeader>
          {!jobId && (
            <div className="space-y-4">
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="space-y-1 text-sm">
                  <span>{t('نود مبدأ (ایران)', 'Source node (Iran)')}</span>
                  <select
                    className="border-input bg-background h-10 w-full rounded-md border px-3 text-sm"
                    value={source}
                    onChange={event => {
                      setSource(event.target.value)
                      setTags([])
                      reset()
                    }}
                  >
                    <option value="">{t('انتخاب کنید', 'Select')}</option>
                    {nodes.map(node => (
                      <option key={node.id} value={node.id}>
                        {node.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="space-y-1 text-sm">
                  <span>{t('نود خروجی (خارج)', 'Exit node (abroad)')}</span>
                  <select
                    className="border-input bg-background h-10 w-full rounded-md border px-3 text-sm"
                    value={exit}
                    onChange={event => {
                      setExit(event.target.value)
                      reset()
                    }}
                  >
                    <option value="">{t('انتخاب کنید', 'Select')}</option>
                    {nodes
                      .filter(node => String(node.id) !== source)
                      .map(node => (
                        <option key={node.id} value={node.id}>
                          {node.name}
                        </option>
                      ))}
                  </select>
                </label>
              </div>
              <fieldset className="space-y-2">
                <legend className="text-sm">{t('ورودی‌های کاربر روی مبدأ', 'User inbounds on the source')}</legend>
                {available.length === 0 && <p className="text-muted-foreground text-xs">{t('ابتدا نود مبدأ را انتخاب کنید.', 'Select a source node first.')}</p>}
                <div className="flex flex-wrap gap-3">
                  {available.map(tag => (
                    <label key={tag} className="flex items-center gap-2 text-sm">
                      <input
                        type="checkbox"
                        checked={tags.includes(tag)}
                        onChange={event => {
                          setTags(event.target.checked ? [...tags, tag] : tags.filter(item => item !== tag))
                          reset()
                        }}
                      />
                      <bdi>{tag}</bdi>
                    </label>
                  ))}
                </div>
              </fieldset>
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="space-y-1 text-sm">
                  <span>{t('حالت', 'Mode')}</span>
                  <select
                    className="border-input bg-background h-10 w-full rounded-md border px-3 text-sm"
                    value={mode}
                    onChange={event => {
                      setMode(event.target.value as 'fast' | 'thorough')
                      reset()
                    }}
                  >
                    <option value="fast">{t('سریع؛ اولین مسیر سالم', 'Fast: first healthy path')}</option>
                    <option value="thorough">{t('کامل؛ سنجش همه و انتخاب بهترین', 'Thorough: measure all, keep the best')}</option>
                  </select>
                </label>
                <label className="space-y-1 text-sm">
                  <span>{t('اولویت', 'Priority')}</span>
                  <select
                    className="border-input bg-background h-10 w-full rounded-md border px-3 text-sm"
                    value={priority}
                    onChange={event => {
                      setPriority(event.target.value as 'balanced' | 'latency' | 'throughput')
                      reset()
                    }}
                  >
                    <option value="balanced">{t('متعادل', 'Balanced')}</option>
                    <option value="latency">{t('کمترین تأخیر (بازی)', 'Lowest latency (gaming)')}</option>
                    <option value="throughput">{t('بیشترین سرعت', 'Highest throughput')}</option>
                  </select>
                </label>
              </div>
              {plan && (
                <div className="space-y-3 rounded-xl border p-4 text-sm" aria-live="polite">
                  {plan.blockers.map(code => (
                    <p key={code} role="alert" className="text-destructive">
                      {pick(blockers, code)}
                    </p>
                  ))}
                  {plan.warnings.map(code => (
                    <p key={code} className="text-xs text-amber-600">
                      {pick(warnings, code)}
                    </p>
                  ))}
                  <ul className="space-y-1">
                    {plan.candidates.map(item => (
                      <li key={item.id} className="flex flex-wrap justify-between gap-2">
                        <span dir="ltr">{item.label}</span>
                        <span className={item.compatible ? '' : 'text-muted-foreground'}>{item.compatible ? t('آماده', 'Ready') : t('ناسازگار', 'Incompatible')}</span>
                      </li>
                    ))}
                  </ul>
                  {plan.ok && (
                    <label className="flex items-start gap-2 text-xs leading-6">
                      <input type="checkbox" className="mt-1" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />
                      <span>
                        {t(
                          `هر آزمون هستهٔ Xray هر دو نود را ری‌استارت می‌کند و اتصال‌های زندهٔ آن‌ها را قطع می‌کند (حداکثر ${plan.max_deployments} اعمال تنظیمات). تأیید می‌کنم.`,
                          `Each test restarts Xray on both nodes and drops their live connections (at most ${plan.max_deployments} deployments). I confirm.`,
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
                <Button disabled={!plan?.ok || !confirmed || busy} onClick={start}>
                  {t('اجرا', 'Run')}
                </Button>
              </DialogFooter>
            </div>
          )}
          {jobId && (
            <div className="space-y-4" aria-live="polite">
              <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
                <span>{job.data ? pick(jobLabels, job.data.state) : t('در حال بارگذاری…', 'Loading…')}</span>
                {running && (
                  <Button size="sm" variant="ghost" onClick={cancel}>
                    {t('لغو پس از آزمون جاری', 'Stop after the current test')}
                  </Button>
                )}
              </div>
              <progress className="accent-primary h-1.5 w-full" value={job.data?.progress ?? 0} max={100} aria-label={t('پیشرفت', 'Progress')} />
              {steps.length > 0 && (
                <div className="overflow-x-auto">
                  <table className="w-full text-xs" dir="ltr">
                    <thead>
                      <tr className="text-muted-foreground text-left">
                        <th className="py-1 pr-3">{t('حامل', 'Carrier')}</th>
                        <th className="pr-3">{t('وضعیت', 'State')}</th>
                        <th className="pr-3">TCP ms</th>
                        <th className="pr-3">UDP ms</th>
                        <th className="pr-3">{t('افت', 'Loss')} %</th>
                        <th className="pr-3">Mbps</th>
                      </tr>
                    </thead>
                    <tbody>
                      {steps.map(step => (
                        <tr key={step.id} className={winner?.id === step.id ? 'font-medium' : ''}>
                          <td className="py-1 pr-3">
                            {step.label}
                            {winner?.id === step.id ? ' ★' : ''}
                          </td>
                          <td className="pr-3">{pick(stateLabels, step.state)}</td>
                          <td className="pr-3">{number(step.metrics?.tcp_rtt_ms)}</td>
                          <td className="pr-3">{number(step.metrics?.udp_rtt_ms)}</td>
                          <td className="pr-3">{number(step.metrics?.udp_loss_percent)}</td>
                          <td className="pr-3">{number(step.metrics?.sample_mbps, 1)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              {job.data?.state === 'ready' && winner && (
                <p className="text-xs leading-6">
                  {t(
                    `«${winner.label}» فعال ماند و بقیهٔ مسیرهای آزمایشی حذف شدند. برای انتشار IP ایران در اشتراک، از بخش هاست‌ها استفاده کنید؛ اتصال واقعی کاربر و بازی را هم بررسی کنید.`,
                    `“${winner.label}” stays active and the other test paths were removed. Publish the Iran ingress IP from the hosts section, then check a real user connection and your game.`,
                  )}
                </p>
              )}
              {job.data?.state === 'failed' && (
                <p role="alert" className="text-destructive text-xs leading-6">
                  {t(
                    'هیچ حاملی TCP و UDP را سالم عبور نداد و همهٔ مسیرهای آزمایشی حذف شدند. نصب نود، فایروال ارائه‌دهنده و آدرس‌ها را بررسی کنید.',
                    'No carrier passed TCP and UDP and all test paths were removed. Check node installs, the provider firewall and addresses.',
                  )}
                </p>
              )}
              {!!job.data?.result.cleanup_required?.length && (
                <p role="alert" className="text-destructive text-xs leading-6">
                  {t('این مسیرها خودکار حذف نشدند؛ دستی حذف کنید:', 'These paths could not be removed automatically. Remove them manually:')} <bdi>{job.data.result.cleanup_required.join(', ')}</bdi>
                </p>
              )}
              {error && (
                <p role="alert" className="text-destructive text-sm">
                  {error}
                </p>
              )}
              <DialogFooter>
                <Button variant="outline" disabled={running} onClick={close}>
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
