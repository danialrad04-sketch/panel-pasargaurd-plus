import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Activity, Cable, CheckCircle2, RefreshCw, ShieldCheck } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useAdmin } from '@/hooks/use-admin'
import { isOwner } from '@/utils/rbac'
import { fetcher } from '@/service/http'

type Profile = { id: string; name: string; host: string }
type Job = { id: string; plan_id: string; status: string; stage: string; updated: number }
type State = { available: boolean; busy: boolean; iran_address?: string; profiles: Profile[]; jobs: Job[] }
type Preview = {
  id: string
  expires_at: number
  summary: { iran: string; foreign: string; transports: string[]; services: { id: string; listen: string; target: string }[]; firewall: Record<string, { tcp: number[]; udp: number[] }> }
}
const carriers = ['frp-wss', 'frp-quic', 'backpack-wss', 'paqet', 'backhaul-wss', 'browser-wss', 'donino-mux', 'donino-continuum']
const panel = 'rounded-xl border bg-card p-5 space-y-4'
const selectClass = 'border-input bg-background h-10 w-full rounded-md border px-3 text-sm'

export default function Tunnels() {
  const { admin } = useAdmin()
  const { i18n } = useTranslation()
  const fa = i18n.language.startsWith('fa')
  const text = (persian: string, english: string) => (fa ? persian : english)
  const owner = isOwner(admin)
  const state = useQuery({ queryKey: ['donino-state'], queryFn: () => fetcher<State>('/api/tunnels/state'), enabled: owner, refetchInterval: 5000, retry: false })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [profile, setProfile] = useState('')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [confirm, setConfirm] = useState(false)
  const [transports, setTransports] = useState(['frp-wss', 'frp-quic'])
  const [experimental, setExperimental] = useState(false)
  const [services, setServices] = useState([{ id: 'vpn', listen_port: 443, target_port: 8443 }])
  const invalidate = () => {
    setPreview(null)
    setConfirm(false)
    setNotice('')
  }
  const perform = async (action: () => Promise<void>) => {
    if (busy) return
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await action()
    } catch {
      setError(
        text(
          'عملیات تکمیل نشد. وضعیت را تازه‌سازی کنید؛ پس از قطع پاسخ، نصب را بدون بررسی دوباره اجرا نکنید.',
          'Request did not complete. Refresh state before retrying; a lost response may still mean installation started.',
        ),
      )
    } finally {
      setBusy(false)
      void state.refetch()
    }
  }
  if (!owner) return <p className="p-6">{text('این بخش فقط برای مالک پنل در دسترس است.', 'Only the panel owner can access this section.')}</p>
  const ready = state.data?.available === true && !state.error
  const locked = busy || !!state.data?.busy || !ready
  return (
    <div className="space-y-5 p-4 lg:p-6" dir={fa ? 'rtl' : 'ltr'}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Cable className="text-primary h-9 w-9" />
          <div>
            <h2 className="text-xl font-semibold">Donino Tunnel</h2>
            <p className="text-muted-foreground text-sm">{text('مدیریت مسیرهای ایران و خارج از داخل پاسارگارد', 'Manage Iran and foreign routes inside PasarGuard')}</p>
          </div>
        </div>
        <Button variant="outline" disabled={state.isFetching} onClick={() => void state.refetch()}>
          <RefreshCw className="me-2 h-4 w-4" />
          {text('تازه‌سازی', 'Refresh')}
        </Button>
      </div>
      <div className="grid gap-3 sm:grid-cols-3">
        {[
          [text('کنترل‌کننده', 'Controller'), state.isPending ? '…' : state.error ? text('در دسترس نیست', 'Unreachable') : ready ? text('متصل', 'Connected') : text('غیرفعال', 'Disabled')],
          [text('سرورهای جفت‌شده', 'Paired servers'), state.data?.profiles.length ?? '—'],
          [text('عملیات نصب', 'Installation'), state.data?.busy ? text('در حال اجرا', 'Running') : text('آماده', 'Idle')],
        ].map(([label, value]) => (
          <div key={label} className={panel}>
            <p className="text-muted-foreground text-sm">{label}</p>
            <p className="text-xl font-semibold">{value}</p>
          </div>
        ))}
      </div>
      {!ready && (
        <div className={panel}>
          <ShieldCheck className="text-primary" />
          <p>
            {text(
              'نقش ایران Donino Tunnel باید روی میزبان پنل آماده باشد. DONINO_TUNNEL_BRIDGE=1 و دسترسی گروه donino-link به socket محلی را تنظیم کنید. در Docker باید socket و گروه میزبان نیز در دسترس باشند.',
              'Prepare the Donino Tunnel Iran role on the panel host, enable DONINO_TUNNEL_BRIDGE=1 and grant the service group access to the local socket. Docker also needs the host socket and group.',
            )}
          </p>
        </div>
      )}
      {error && (
        <p role="alert" className="border-destructive text-destructive rounded-lg border p-4">
          {error}
        </p>
      )}
      {notice && (
        <p role="status" className="border-primary rounded-lg border p-4">
          {notice}
        </p>
      )}
      <div className="grid gap-5 xl:grid-cols-2">
        <section className={panel}>
          <h3 className="flex items-center gap-2 font-semibold">
            <ShieldCheck className="h-4 w-4" />
            {text('۱. جفت‌سازی سرور خارج', '1. Pair foreign server')}
          </h3>
          <form
            autoComplete="off"
            onSubmit={e => {
              e.preventDefault()
              const form = e.currentTarget
              const data = new FormData(form)
              void perform(async () => {
                const result = await fetcher<Profile>('/api/tunnels/pair', {
                  method: 'POST',
                  body: { name: data.get('name'), host: data.get('host'), agent_port: Number(data.get('agent_port')), code: data.get('code') },
                })
                form.reset()
                invalidate()
                setProfile(result.id)
                setNotice(text('سرور با موفقیت جفت شد.', 'Server paired successfully.'))
              })
            }}
          >
            <fieldset disabled={locked} className="space-y-3">
              <label className="block space-y-1">
                <span>{text('نام سرور', 'Server name')}</span>
                <Input name="name" required maxLength={64} />
              </label>
              <label className="block space-y-1">
                <span>{text('دامنه یا IP خارج', 'Foreign hostname or IP')}</span>
                <Input dir="ltr" name="host" required placeholder="exit.example.com" />
              </label>
              <label className="block space-y-1">
                <span>{text('پورت مدیریت', 'Management port')}</span>
                <Input dir="ltr" name="agent_port" type="number" required min={1024} max={65535} defaultValue={9444} />
              </label>
              <label className="block space-y-1">
                <span>{text('کد جفت‌سازی پنل خارج', 'Foreign panel pairing code')}</span>
                <Input dir="ltr" name="code" type="password" required autoComplete="new-password" />
              </label>
              <Button type="submit">{text('جفت‌سازی امن', 'Pair securely')}</Button>
            </fieldset>
          </form>
        </section>
        <section className={panel}>
          <h3 className="font-semibold">{text('۲. نقشه نصب', '2. Installation plan')}</h3>
          <form
            onChange={invalidate}
            onSubmit={e => {
              e.preventDefault()
              const data = new FormData(e.currentTarget)
              invalidate()
              void perform(async () => {
                const result = await fetcher<Preview>('/api/tunnels/preview', {
                  method: 'POST',
                  body: {
                    profile_id: profile,
                    iran: data.get('iran'),
                    services,
                    transports,
                    experimental,
                    base_port: Number(data.get('base_port')),
                    carrier_base: Number(data.get('carrier_base')),
                    health_port: Number(data.get('health_port')),
                    paqet_port: Number(data.get('paqet_port')),
                    browser_port: transports.includes('browser-wss') ? Number(data.get('browser_port')) : null,
                  },
                })
                setPreview(result)
                setConfirm(false)
              })
            }}
          >
            <fieldset disabled={locked} className="space-y-3">
              <label className="block space-y-1">
                <span>{text('سرور خارج', 'Foreign server')}</span>
                <select required className={selectClass} value={profile} onChange={e => setProfile(e.target.value)}>
                  <option value="">{text('انتخاب سرور', 'Select server')}</option>
                  {state.data?.profiles.map(p => (
                    <option key={p.id} value={p.id}>
                      {p.name} · {p.host}
                    </option>
                  ))}
                </select>
              </label>
              <Button
                type="button"
                variant="outline"
                disabled={!profile}
                onClick={() =>
                  void perform(async () => {
                    const c = await fetcher<{ ok: boolean; ready: boolean; runtime_match: boolean }>('/api/tunnels/connection', { method: 'POST', body: { profile_id: profile } })
                    setNotice(
                      c.ok && c.ready && c.runtime_match
                        ? text('کانال مدیریت و نسخه آماده‌اند.', 'Management channel and runtime are ready.')
                        : text('کانال مدیریت هنوز آماده نیست یا نسخه‌ها مطابقت ندارند.', 'Management channel is not ready or runtime versions differ.'),
                    )
                  })
                }
              >
                {text('تست اتصال', 'Test connection')}
              </Button>
              <label className="block space-y-1">
                <span>{text('دامنه یا IP ایران', 'Iran hostname or IP')}</span>
                <Input name="iran" dir="ltr" required placeholder={state.data?.iran_address || 'iran.example.com'} />
              </label>
              <div className="grid gap-2 sm:grid-cols-2">
                {carriers.map(c => (
                  <label key={c} className="flex items-center gap-2 rounded-md border p-2 text-sm">
                    <input type="checkbox" checked={transports.includes(c)} onChange={e => setTransports(e.target.checked ? [...transports, c] : transports.filter(x => x !== c))} />
                    <span dir="ltr">{c}</span>
                    {!c.startsWith('frp-') && <span className="text-muted-foreground text-xs">{text('آزمایشی', 'Experimental')}</span>}
                  </label>
                ))}
              </div>
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={experimental} onChange={e => setExperimental(e.target.checked)} />
                {text('اجازه استفاده از حامل‌های آزمایشی', 'Allow experimental transports')}
              </label>
              <div className="grid grid-cols-2 gap-3">
                {[
                  ['base_port', 30000, text('پورت پایه', 'Base port')],
                  ['carrier_base', 24443, text('پورت حامل', 'Carrier port')],
                  ['health_port', 19090, text('پورت سلامت', 'Health port')],
                  ['paqet_port', 33434, 'Paqet'],
                  ...(transports.includes('browser-wss') ? [['browser_port', 24445, 'BrowserLink']] : []),
                ].map(([name, value, label]) => (
                  <label key={name} className="space-y-1 text-sm">
                    <span>{label}</span>
                    <Input dir="ltr" name={String(name)} type="number" min={1024} max={65535} required defaultValue={value} />
                  </label>
                ))}
              </div>
              <p className="text-muted-foreground text-sm">
                {text(
                  'سرویس مقصد باید روی loopback سرور خارج گوش کند. پورت ورودی پاسارگارد را وارد کنید. تنظیمات TLS و هویت کاربر حفظ می‌شوند.',
                  'The destination service must listen on the foreign server loopback. Enter its PasarGuard inbound port; TLS settings and user credentials remain in place.',
                )}
              </p>
              {services.map((s, index) => (
                <div key={index} className="grid grid-cols-3 gap-2">
                  {(['id', 'listen_port', 'target_port'] as const).map(key => (
                    <label key={key} className="text-xs">
                      {key === 'id' ? text('شناسه', 'ID') : key === 'listen_port' ? text('پورت ایران', 'Iran port') : text('پورت مقصد', 'Target port')}
                      <Input
                        dir="ltr"
                        value={s[key]}
                        type={key === 'id' ? 'text' : 'number'}
                        required
                        min={1}
                        max={65535}
                        pattern={key === 'id' ? '[a-z][a-z0-9-]*' : undefined}
                        maxLength={key === 'id' ? 40 : undefined}
                        onChange={e => setServices(services.map((row, n) => (n === index ? { ...row, [key]: key === 'id' ? e.target.value : Number(e.target.value) } : row)))}
                      />
                    </label>
                  ))}
                </div>
              ))}
              <div className="flex flex-wrap gap-2">
                <Button
                  type="button"
                  variant="outline"
                  disabled={services.length >= 40}
                  onClick={() => {
                    invalidate()
                    setServices([...services, { id: `vpn-${services.length + 1}`, listen_port: 443 + services.length, target_port: 8443 + services.length }])
                  }}
                >
                  {text('افزودن سرویس', 'Add service')}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  disabled={services.length <= 1}
                  onClick={() => {
                    invalidate()
                    setServices(services.slice(0, -1))
                  }}
                >
                  {text('حذف آخرین', 'Remove last')}
                </Button>
                <Button type="submit" disabled={!profile || !transports.length}>
                  {text('پیش‌نمایش نقشه و پورت‌ها', 'Preview plan and ports')}
                </Button>
              </div>
            </fieldset>
          </form>
        </section>
      </div>
      {preview && (
        <section className={panel}>
          <h3 className="flex items-center gap-2 font-semibold">
            <CheckCircle2 className="h-5 w-5" />
            {text('۳. مرور و اجرای نقشه', '3. Review and start')}
          </h3>
          <p dir="ltr">
            {preview.summary.iran} → {preview.summary.foreign} · {preview.summary.transports.join(', ')}
          </p>
          <div className="overflow-auto">
            <table className="w-full text-start text-sm">
              <thead>
                <tr>
                  {['ID', text('ورودی ایران', 'Iran listener'), text('مقصد خارج', 'Foreign target')].map(h => (
                    <th className="p-2 text-start" key={h}>
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {preview.summary.services.map(s => (
                  <tr className="border-t" key={s.id}>
                    <td className="p-2">{s.id}</td>
                    <td dir="ltr">{s.listen}</td>
                    <td dir="ltr">{s.target}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {Object.entries(preview.summary.firewall).map(([role, ports]) => (
            <p key={role} dir="ltr" className="text-sm">
              {role}: TCP {ports.tcp.join(', ') || '—'} · UDP {ports.udp.join(', ') || '—'}
            </p>
          ))}
          <p className="text-muted-foreground text-sm">
            {text(
              'پورت‌های بالا باید در شبکه و فایروال میزبان مجاز باشند. این عملیات نصب قبلی دونینو را جایگزین می‌کند؛ آدرس اشتراک خودکار تغییر نمی‌کند.',
              'Allow the listed ports in your network and host firewall. Installation replaces the previous Donino deployment; subscription addresses are not changed automatically.',
            )}
          </p>
          <label className="flex gap-2">
            <input type="checkbox" checked={confirm} onChange={e => setConfirm(e.target.checked)} />
            {text('نقشه و جایگزینی نصب قبلی را تأیید می‌کنم.', 'I confirm this plan and replacement of the previous installation.')}
          </label>
          <Button
            disabled={locked || !confirm || preview.expires_at * 1000 <= Date.now()}
            onClick={() =>
              void perform(async () => {
                const id = preview.id
                setPreview(null)
                setConfirm(false)
                const job = await fetcher<Job>('/api/tunnels/start', { method: 'POST', body: { plan_id: id, confirm_replace: true } })
                setNotice(text('عملیات ثبت شد: ', 'Operation submitted: ') + job.id)
              })
            }
          >
            {text('شروع نصب دوطرفه', 'Start installation')}
          </Button>
        </section>
      )}
      <section className={panel}>
        <h3 className="flex items-center gap-2 font-semibold">
          <Activity className="h-4 w-4" />
          {text('تاریخچه عملیات', 'Operation history')}
        </h3>
        <p className="text-muted-foreground text-sm">
          {text('موفقیت نصب، سلامت لحظه‌ای تونل یا عبور VPN را اثبات نمی‌کند.', 'Installation success does not establish current tunnel health or VPN reachability.')}
        </p>
        {!state.data?.jobs.length && <p className="text-muted-foreground text-sm">{text('هنوز عملیاتی ثبت نشده است.', 'No operations yet.')}</p>}
        {state.data?.jobs.map(job => (
          <div key={job.id} className="flex flex-wrap justify-between gap-2 border-t pt-3 text-sm">
            <code>{job.id.slice(0, 12)}</code>
            <span>{job.stage}</span>
            <span>{job.status}</span>
            <time>{new Date(job.updated * 1000).toLocaleString(fa ? 'fa-IR' : 'en-US')}</time>
          </div>
        ))}
      </section>
    </div>
  )
}
