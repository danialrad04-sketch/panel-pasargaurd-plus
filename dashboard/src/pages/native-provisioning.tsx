import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { fetcher } from '@/service/http'

type Job = { id: string; kind: string; target: string; state: string; progress: number; node_id?: number | null; result: { code?: string; scope?: string } }
type State = { items: Job[]; bundles: { architecture: string; engines: string[] }[] }
const labels: Record<string, [string, string]> = {
  queued: ['در صف اجرا', 'Queued'],
  connecting: ['اتصال امن به سرور', 'Connecting over SSH'],
  checking_server: ['بررسی سرور', 'Checking server'],
  transferring_bundle: ['انتقال موتورهای کامل', 'Transferring engines'],
  installing_service: ['نصب و فعال‌سازی سرویس', 'Installing service'],
  verifying_node: ['تأیید نود نصب‌شده', 'Verifying installed node'],
  starting_node: ['ثبت و راه‌اندازی نود', 'Registering and starting node'],
  activating: ['راه‌اندازی خودکار مسیر', 'Activating tunnel'],
  checking_configuration: ['بررسی تنظیمات مسیر', 'Checking configuration'],
  configuring_ingress: ['تنظیم پورت‌های انتخاب‌شده', 'Configuring selected ports'],
  probing_tcp_udp: ['سنجش واقعی TCP/UDP', 'Probing TCP/UDP'],
  ready: ['تأیید شد', 'Verified'],
  failed: ['نیاز به بررسی', 'Needs attention'],
  interrupted: ['اجرای ناتمام', 'Interrupted'],
  cancelled: ['لغو شد', 'Cancelled'],
}
const errors: Record<string, string> = {
  ssh_host_key_changed: 'کلید SSH سرور تغییر کرده؛ هویت سرور را دوباره بررسی کنید.',
  ssh_authentication_failed: 'اطلاعات ورود SSH پذیرفته نشد.',
  ssh_connection_failed: 'اتصال SSH برقرار نشد یا قطع شد.',
  bundle_architecture_unavailable: 'بستهٔ معماری این سرور در تصویر فعلی پنل موجود نیست.',
  unsupported_os: 'نصب خودکار به Debian 12 یا Ubuntu 22.04 و نسخه‌های جدیدتر نیاز دارد.',
  unsupported_python_version: 'نسخهٔ Python مقصد باید 3.10 یا جدیدتر باشد.',
  systemd_required: 'این سرور به systemd فعال نیاز دارد.',
  existing_identity_mismatch: 'گواهی سرور با نود ثبت‌شده تطبیق ندارد؛ هویت قبلی حفظ شد.',
  existing_identity_missing: 'فایل هویت نود فعلی پیدا نشد؛ از ایجاد هویت جایگزین جلوگیری شد.',
  unmanaged_service: 'سرویس هم‌نام متعلق به این نصب نیست و جایگزین نشد.',
  management_port_conflict: 'پورت مدیریت در اختیار سرویس دیگری است.',
  node_core_start_failed: 'موتورها نصب شدند، ولی هستهٔ نود تأیید نشد؛ تنظیمات هسته را بررسی کنید.',
  tunnel_apply_failed: 'اعمال مسیر یا پورت‌ها تأیید نشد؛ نود را به نسخهٔ جدید مجهز کنید.',
  carrier_health_failed: 'سنجش واقعی مسیر موفق نبود؛ مسیر آماده اعلام نشد.',
  tunnel_revision_changed: 'مسیر هنگام انتظار تغییر کرده؛ دوباره راه‌اندازی را انتخاب کنید.',
}
export default function NativeProvisioning({ nodes, fa }: { nodes: { id: number; name: string }[]; fa: boolean }) {
  const t = (f: string, e: string) => (fa ? f : e)
  const client = useQueryClient()
  const state = useQuery({ queryKey: ['native-provision'], queryFn: () => fetcher<State>('/api/provision'), refetchInterval: 3000 })
  const cores = useQuery({ queryKey: ['provision-cores'], queryFn: () => fetcher<{ cores: { id: number; name: string; type?: string }[] }>('/api/cores/simple?limit=100'), enabled: false })
  const [open, setOpen] = useState(false)
  const [existing, setExisting] = useState('')
  const [host, setHost] = useState('')
  const [port, setPort] = useState('22')
  const [method, setMethod] = useState('password')
  const [key, setKey] = useState<{ server_host_key: string; fingerprint: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [settings, setSettings] = useState({ core_config_id: 1, port: 62050, tunnel_api_port: 62052, health_echo_port: 62053 })
  useEffect(() => {
    if (state.data?.items.some(job => job.state === 'ready')) {
      client.invalidateQueries({ queryKey: ['native-tunnels'] })
      client.invalidateQueries({ queryKey: ['nodes'] })
    }
  }, [
    state.data?.items
      .filter(job => job.state === 'ready')
      .map(job => job.id)
      .join(','),
    client,
  ])
  const report = (value: unknown) => {
    const detail = (value as { data?: { detail?: unknown } }).data?.detail
    setError(
      typeof detail === 'string'
        ? detail
        : detail && typeof detail === 'object'
          ? Object.values(detail).map(String).join(' · ')
          : t('درخواست انجام نشد؛ اطلاعات و اتصال را بررسی کنید.', 'Request failed. Review connectivity and input.'),
    )
  }
  return (
    <section className="bg-card space-y-4 rounded-2xl border p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="font-medium">{t('نصب و راه‌اندازی خودکار', 'Automatic installation and setup')}</h2>
          <p className="text-muted-foreground mt-2 max-w-3xl text-xs leading-6">
            {t(
              'دسترسی اولیهٔ SSH را یک‌بار وارد کنید؛ پنل بستهٔ کامل موتور‌ها را منتقل، سرویس را نصب و نود را ثبت می‌کند. انتخاب موتورهای بعدی به اجرای دستور روی سرور نیاز ندارد.',
              'Provide SSH access once. The panel transfers all engines, installs the service and registers the node. Later engine selections require no server commands.',
            )}
          </p>
        </div>
        <Button
          variant="outline"
          onClick={() => {
            setOpen(true)
            setKey(null)
            setError('')
            cores.refetch()
          }}
        >
          {t('نصب خودکار روی سرور', 'Install on server')}
        </Button>
      </div>
      {!open && error && (
        <p role="alert" className="text-destructive text-sm">
          {error}
        </p>
      )}
      {state.isError && (
        <p role="alert" className="text-destructive text-xs">
          {t('وضعیت نصب قابل دریافت نیست.', 'Installation status is unavailable.')}
        </p>
      )}
      {state.data && state.data.bundles.length === 0 && (
        <p className="text-muted-foreground text-xs">
          {t('این اجرای پنل بستهٔ نصب ندارد؛ تصویر جدید پنل بسته را هنگام build آماده می‌کند.', 'This panel runtime has no installation bundle. The current panel image includes it at build time.')}
        </p>
      )}
      <div className="space-y-3" aria-live="polite">
        {state.data?.items.slice(0, 8).map(job => (
          <div key={job.id} className="bg-background rounded-xl border p-4">
            <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
              <span dir="ltr">{job.target}</span>
              <span>{labels[job.state]?.[fa ? 0 : 1] || job.state}</span>
            </div>
            <progress className="accent-primary mt-3 h-1.5 w-full" value={job.progress} max={100} aria-label={t('پیشرفت نصب', 'Installation progress')} />
            {job.result.code && <p className="text-destructive mt-2 text-xs leading-6">{fa ? errors[job.result.code] || `مرحله کامل نشد: ${job.result.code}` : job.result.code}</p>}
            {job.state === 'ready' && (
              <p className="text-muted-foreground mt-2 text-xs">
                {job.kind === 'install'
                  ? t(`نود #${job.node_id} و موتور انتخاب‌شده تأیید شدند. اکنون مسیر را انتخاب کنید.`, `Node #${job.node_id} and the selected engine are verified. Select your tunnel path.`)
                  : t('حامل رمز‌شده TCP/UDP تأیید شد؛ اتصال مشتری و سرویس واقعی را نیز بررسی کنید.', 'Encrypted carrier TCP/UDP verified. Check the actual user connection and service as well.')}
              </p>
            )}
            {job.state === 'queued' && (
              <Button
                size="sm"
                variant="ghost"
                onClick={async () => {
                  try {
                    await fetcher(`/api/provision/jobs/${job.id}/cancel`, { method: 'POST' })
                    await client.invalidateQueries({ queryKey: ['native-provision'] })
                  } catch (error) {
                    report(error)
                  }
                }}
              >
                {t('لغو کار در صف', 'Cancel queued job')}
              </Button>
            )}
          </div>
        ))}
      </div>
      <Dialog
        open={open}
        onOpenChange={value => {
          if (!busy) {
            setOpen(value)
            if (!value) setKey(null)
          }
        }}
      >
        <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-xl" dir={fa ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>{t('نصب خودکار نود و موتور‌ها', 'Install node and engines')}</DialogTitle>
            <DialogDescription>
              {t(
                'برای Debian/Ubuntu با systemd، دسترسی root یا sudo بدون رمز لازم است. کلید میزبان SSH را بررسی کنید؛ با شروع نصب، سرویس و پورت‌های لازم همین نود تنظیم می‌شوند. هویت نود موجود حفظ می‌شود.',
                'Debian/Ubuntu with systemd requires root or passwordless sudo. Review the SSH host key. Installation configures this node service and required ports, preserving an existing node identity.',
              )}
            </DialogDescription>
          </DialogHeader>
          <form
            className="space-y-4"
            onSubmit={async event => {
              event.preventDefault()
              if (!key || busy) return
              const form = new FormData(event.currentTarget)
              setBusy(true)
              setError('')
              try {
                await fetcher('/api/provision/install', {
                  method: 'POST',
                  body: {
                    host,
                    ssh_port: Number(port),
                    server_host_key: key.server_host_key,
                    name: String(form.get('name')),
                    username: String(form.get('username')),
                    node_id: existing ? Number(existing) : null,
                    core_config_id: settings.core_config_id,
                    engine: String(form.get('engine')),
                    node_port: settings.port,
                    tunnel_port: settings.tunnel_api_port,
                    health_port: settings.health_echo_port,
                    ...(method === 'password' ? { password: String(form.get('password')) } : { private_key: String(form.get('private_key')), key_passphrase: String(form.get('passphrase')) || null }),
                  },
                })
                setOpen(false)
                setKey(null)
                await client.invalidateQueries({ queryKey: ['native-provision'] })
              } catch (error) {
                report(error)
              } finally {
                setBusy(false)
              }
            }}
          >
            <label className="block space-y-2 text-sm">
              <span>{t('سرور تازه یا نود موجود', 'New server or existing node')}</span>
              <select
                aria-label={t('سرور تازه یا نود موجود', 'New server or existing node')}
                className="bg-background h-10 w-full rounded-md border px-3"
                value={existing}
                onChange={async event => {
                  const id = event.target.value
                  setExisting(id)
                  setKey(null)
                  if (!id) {
                    setSettings({ core_config_id: 1, port: 62050, tunnel_api_port: 62052, health_echo_port: 62053 })
                    return
                  }
                  try {
                    const value = await fetcher<typeof settings & { address: string }>(`/api/node/${id}`)
                    setSettings({ core_config_id: value.core_config_id, port: value.port, tunnel_api_port: value.tunnel_api_port, health_echo_port: value.health_echo_port })
                    setHost(value.address)
                  } catch (error) {
                    report(error)
                  }
                }}
              >
                <option value="">{t('ثبت سرور تازه', 'Register a new server')}</option>
                {nodes.map(node => (
                  <option key={node.id} value={node.id}>
                    {node.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="block space-y-2 text-sm">
              <span>{t('نام نود نصب‌شده', 'Installed node name')}</span>
              <Input name="name" required maxLength={80} key={existing} defaultValue={nodes.find(node => String(node.id) === existing)?.name || 'Native node'} readOnly={!!existing} />
            </label>
            <div className="grid grid-cols-2 gap-3">
              <label className="space-y-2 text-sm">
                <span>{t('IP سرور SSH', 'SSH server IP')}</span>
                <Input
                  value={host}
                  onChange={event => {
                    setHost(event.target.value)
                    setKey(null)
                  }}
                  required
                  dir="ltr"
                />
              </label>
              <label className="space-y-2 text-sm">
                <span>{t('پورت SSH', 'SSH port')}</span>
                <Input
                  value={port}
                  onChange={event => {
                    setPort(event.target.value)
                    setKey(null)
                  }}
                  required
                  type="number"
                  min={1}
                  max={65535}
                />
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
                <span>{t('رمز SSH؛ فقط برای همین نصب', 'SSH password; this installation only')}</span>
                <Input name="password" type="password" required autoComplete="new-password" />
              </label>
            ) : (
              <>
                <label className="block space-y-2 text-sm">
                  <span>{t('کلید خصوصی SSH', 'SSH private key')}</span>
                  <textarea name="private_key" required autoComplete="off" dir="ltr" className="bg-background min-h-28 w-full rounded-md border p-3 font-mono text-xs" />
                </label>
                <label className="block space-y-2 text-sm">
                  <span>{t('رمز کلید؛ اختیاری', 'Key passphrase; optional')}</span>
                  <Input name="passphrase" type="password" autoComplete="new-password" />
                </label>
              </>
            )}
            <div className="grid grid-cols-2 gap-3">
              <label className="space-y-2 text-sm">
                <span>{t('هستهٔ کاربر', 'User core')}</span>
                <select
                  aria-label={t('هستهٔ کاربر', 'User core')}
                  className="bg-background h-10 w-full rounded-md border px-3"
                  disabled={!!existing}
                  value={settings.core_config_id}
                  onChange={event => setSettings({ ...settings, core_config_id: Number(event.target.value) })}
                >
                  {(cores.data?.cores || [{ id: 1, name: 'Core #1' }])
                    .filter(core => !core.type || core.type === 'xray')
                    .map(core => (
                      <option key={core.id} value={core.id}>
                        {core.name}
                      </option>
                    ))}
                </select>
              </label>
              <label className="space-y-2 text-sm">
                <span>{t('موتور انتخابی', 'Selected engine')}</span>
                <select aria-label={t('موتور انتخابی', 'Selected engine')} name="engine" className="bg-background h-10 w-full rounded-md border px-3">
                  <option value="paqet">Paqet</option>
                  <option value="backhaul">Backhaul</option>
                  <option value="gost">GOST</option>
                  <option value="frp">FRP</option>
                </select>
              </label>
            </div>
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
                } catch (error) {
                  report(error)
                } finally {
                  setBusy(false)
                }
              }}
            >
              {t('بررسی هویت SSH سرور', 'Inspect SSH server identity')}
            </Button>
            {key && (
              <div className="bg-muted/30 rounded-xl border p-3 text-xs leading-6">
                <p>{t('اثر انگشت اولین اتصال؛ با هویت سرور تطبیق دهید:', 'First-connection fingerprint; match it to your server identity:')}</p>
                <p className="font-mono break-all" dir="ltr">
                  {key.fingerprint}
                </p>
                <p>{t('دسترسی‌ها در صف رمزگذاری می‌شوند و پس از پایان کار حذف خواهند شد.', 'Credentials are encrypted while queued and deleted after the job ends.')}</p>
              </div>
            )}
            {error && (
              <p role="alert" className="text-destructive text-sm leading-6">
                {error}
              </p>
            )}
            <Button type="submit" disabled={busy || !key || !state.data?.bundles.length}>
              {t('شروع نصب خودکار روی این سرور', 'Install automatically on this server')}
            </Button>
          </form>
        </DialogContent>
      </Dialog>
    </section>
  )
}
