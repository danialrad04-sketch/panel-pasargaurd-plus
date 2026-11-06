import { useQuery } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { fetcher } from '@/service/http'

type ObjectValue = Record<string, any>
const selectClass = 'border-input bg-background h-10 w-full rounded-md border px-3 text-sm'
const labels: Record<string, string> = {
  mtu: 'MTU',
  rcvwnd: 'پنجره دریافت',
  sndwnd: 'پنجره ارسال',
  nodelay: 'بدون تأخیر',
  interval: 'فاصله ارسال (ms)',
  resend: 'بازفرستادن سریع',
  nocongestion: 'حذف کنترل ازدحام',
  dshard: 'قطعه داده FEC',
  pshard: 'قطعه افزونگی FEC',
  smuxbuf: 'بافر SMUX (بایت)',
  streambuf: 'بافر جریان (بایت)',
  smuxkalive: 'Keepalive (ثانیه)',
  smuxktimeout: 'مهلت SMUX (ثانیه)',
}

export function editablePaqet(value: ObjectValue | null | undefined) {
  const result = JSON.parse(JSON.stringify(value || {}))
  for (const row of result.socks5 || []) {
    delete row.authenticated
    delete row.password
  }
  return JSON.stringify(result, null, 2)
}

export default function PaqetEditor({ value, onChange, fa }: { value: string; onChange: (v: string) => void; fa: boolean }) {
  const t = (f: string, e: string) => (fa ? f : e)
  const meta = useQuery({ queryKey: ['paqet-schema'], queryFn: () => fetcher<{ engine: string; blocks: string[]; defaults: ObjectValue; schema: ObjectValue }>('/api/tunnels/paqet/schema') })
  let config: ObjectValue = {}
  let invalid = false
  try {
    const parsed = JSON.parse(value)
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') invalid = true
    else config = parsed as ObjectValue
  } catch {
    invalid = true
  }
  const update = (path: string[], next: unknown) => {
    if (invalid) return
    const copy = JSON.parse(JSON.stringify(config))
    let target = copy
    for (const part of path.slice(0, -1)) {
      target[part] ??= {}
      target = target[part]
    }
    if (next === undefined) delete target[path[path.length - 1]]
    else target[path[path.length - 1]] = next
    onChange(JSON.stringify(copy, null, 2))
  }
  const get = (path: string[], fallback: unknown = ''): any => path.reduce<any>((v, k) => v?.[k], config) ?? fallback
  const number = (path: string[], label: string, min: number, max: number, placeholder?: number) => (
    <label key={path.join('.')} className="block space-y-2 text-xs">
      <span>{label}</span>
      <Input
        type="number"
        min={min}
        max={max}
        value={get(path) as string}
        placeholder={placeholder === undefined ? '' : String(placeholder)}
        onChange={e => update(path, e.target.value === '' ? undefined : Number(e.target.value))}
      />
    </label>
  )
  const text = (path: string[], label: string, placeholder = '', secret = false) => (
    <label key={path.join('.')} className="block space-y-2 text-xs">
      <span>{label}</span>
      <Input
        type={secret ? 'password' : 'text'}
        dir="ltr"
        value={get(path) as string}
        placeholder={placeholder}
        onChange={e => update(path, path[path.length - 1] === 'username' ? e.target.value : e.target.value || null)}
        autoComplete={secret ? 'new-password' : 'off'}
      />
    </label>
  )
  const check = (path: string[], label: string, defaultValue = false) => (
    <label className="flex items-center gap-2 text-xs">
      <input type="checkbox" checked={Boolean(get(path, defaultValue))} onChange={e => update(path, e.target.checked)} />
      {label}
    </label>
  )
  return (
    <section className="space-y-4 rounded-xl border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold">{t('تنظیمات کامل Paqet', 'Paqet configuration')}</h3>
        <span className="text-muted-foreground text-xs">{meta.data?.engine || 'alpha.21'} · KCP</span>
      </div>
      <p className="text-muted-foreground text-xs leading-6">
        {t(
          'نود هر دو سمت باید نسخهٔ Paqet همین فورک را اجرا کند. آدرس‌ها و MAC شبکه به‌صورت خودکار روی هر نود کشف می‌شوند؛ در صورت نیاز آن‌ها را دستی تنظیم کنید.',
          'Both nodes need this fork’s Paqet runtime. Each node discovers its network addresses and gateway MAC; override them when needed.',
        )}
      </p>
      {meta.error && (
        <p role="alert" className="text-destructive text-xs">
          {t('دریافت مشخصات تنظیمات انجام نشد.', 'Could not load configuration metadata.')}
        </p>
      )}
      {invalid && (
        <p role="alert" className="text-destructive text-xs">
          {t('JSON معتبر نیست؛ پیش از ذخیره آن را اصلاح کنید.', 'Invalid JSON. Correct it before saving.')}
        </p>
      )}
      <div className="grid gap-3 sm:grid-cols-3">
        {number(['conn'], t('تعداد اتصال', 'Connections'), 1, 256, 1)}
        <label className="block space-y-2 text-xs">
          <span>{t('حالت KCP', 'KCP mode')}</span>
          <select aria-label={t('حالت KCP', 'KCP mode')} className={selectClass} value={get(['kcp', 'mode'], 'normal') as string} onChange={e => update(['kcp', 'mode'], e.target.value)}>
            {['normal', 'fast', 'fast2', 'fast3', 'manual'].map(v => (
              <option key={v}>{v}</option>
            ))}
          </select>
        </label>
        <label className="block space-y-2 text-xs">
          <span>{t('رمزنگاری حامل', 'Carrier cipher')}</span>
          <select
            aria-label={t('رمزنگاری حامل', 'Carrier cipher')}
            className={selectClass}
            value={get(['kcp', 'block'], 'aes-128-gcm') as string}
            onChange={e => update(['kcp', 'block'], e.target.value)}
          >
            {(meta.data?.blocks || ['aes-128-gcm']).map(v => (
              <option key={v}>{v}</option>
            ))}
          </select>
        </label>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        {number(['client_port'], t('پورت داخلی مبدا', 'Source internal port'), 1024, 65535, 11081)}
        {number(['exit_port'], t('پورت داخلی خروجی', 'Exit internal port'), 1024, 65535, 11082)}
        {text(['client_address'], t('IP حامل مبدا؛ خالی = آدرس نود', 'Source carrier IP; blank = node address'))}
        {text(['server_address'], t('IP حامل خروجی؛ خالی = آدرس نود', 'Exit carrier IP; blank = node address'))}
      </div>
      <details className="rounded-lg border p-3">
        <summary className="cursor-pointer text-sm">{t('KCP، FEC و SMUX', 'KCP, FEC and SMUX')}</summary>
        <p className="text-muted-foreground my-3 text-xs leading-6">
          {t(
            'nodelay، interval، resend و nocongestion در حالت manual اعمال می‌شوند. افزایش بافر، پنجره یا FEC مصرف حافظه و سربار را بیشتر می‌کند.',
            'nodelay, interval, resend and nocongestion apply in manual mode. Larger buffers, windows and FEC consume more memory and bandwidth.',
          )}
        </p>
        <div className="grid gap-3 sm:grid-cols-3">
          {Object.entries(meta.data?.schema?.$defs?.KCP?.properties || {})
            .filter(([key, spec]) => !['mode', 'block', 'wdelay', 'acknodelay'].includes(key) && (spec as ObjectValue).type === 'integer')
            .map(([key, spec]) => {
              const field = spec as ObjectValue
              return number(['kcp', key], fa ? labels[key] || key : key, field.minimum, field.maximum, field.default)
            })}
        </div>
        <div className="mt-4 flex flex-wrap gap-4">
          {check(['kcp', 'wdelay'], t('تجمیع نوشتن', 'Write batching'))}
          {check(['kcp', 'acknodelay'], t('ACK بدون تأخیر', 'Immediate ACK'), true)}
        </div>
      </details>
      {(['client', 'server'] as const).map(role => (
        <details key={role} className="rounded-lg border p-3">
          <summary className="cursor-pointer text-sm">{role === 'client' ? t('شبکه مبدا', 'Source network') : t('شبکه خروجی', 'Exit network')}</summary>
          <div className="mt-4 grid gap-3 sm:grid-cols-2">
            {text([`${role}_network`, 'interface'], t('رابط شبکه؛ خالی = کشف خودکار', 'Interface; blank = auto'), 'eth0')}
            {number([`${role}_network`, 'pcap', 'sockbuf'], 'PCAP buffer (bytes)', 1024, 104857600, 4194304)}
            {(['ipv4', 'ipv6'] as const).map(family => (
              <div key={family} className="space-y-3 rounded-lg border p-3">
                {text([`${role}_network`, family, 'addr'], `${family.toUpperCase()} · IP:port`, family === 'ipv6' ? '[2001:db8::1]:0' : '192.0.2.1:0')}
                {text([`${role}_network`, family, 'router_mac'], t('MAC دروازه', 'Gateway MAC'), 'aa:bb:cc:dd:ee:ff')}
              </div>
            ))}
            {['local_flag', 'remote_flag'].map(flag => (
              <label key={flag} className="block space-y-2 text-xs">
                <span>TCP {flag}</span>
                <Input
                  dir="ltr"
                  value={(get([`${role}_network`, 'tcp', flag], ['PA']) as string[]).join(',')}
                  onChange={e =>
                    update(
                      [`${role}_network`, 'tcp', flag],
                      e.target.value
                        .split(',')
                        .map(v => v.trim())
                        .filter(Boolean),
                    )
                  }
                />
              </label>
            ))}
          </div>
        </details>
      ))}
      <details className="rounded-lg border p-3">
        <summary className="cursor-pointer text-sm">{t('فوروارد TCP/UDP و SOCKS5 اضافی', 'Additional TCP/UDP forwards and SOCKS5')}</summary>
        <p className="text-muted-foreground my-3 text-xs leading-6">
          {t(
            'این ورودی‌ها مستقیم‌اند و سهمیهٔ کاربر VPN را محاسبه نمی‌کنند. مقصد فوروارد باید loopback خروجی باشد؛ SOCKS عمومی به رمز نیاز دارد.',
            'These direct listeners bypass VPN user accounting. Forward destinations must use exit loopback; public SOCKS requires authentication.',
          )}
        </p>
        {check(['allow_unmetered'], t('پذیرش مسیرهای اضافی بدون حسابداری کاربر', 'Allow extra paths without user accounting'))}
        {(config.forwards || []).map((row: ObjectValue, i: number) => (
          <div key={i} className="mt-3 grid gap-2 rounded-lg border p-3 sm:grid-cols-2">
            {text(['forwards', String(i), 'listen'], t('ورودی', 'Listen'), '127.0.0.1:9000')}
            {text(['forwards', String(i), 'target'], t('مقصد خروجی', 'Exit target'), '127.0.0.1:8000')}
            <select className={selectClass} value={row.protocol} onChange={e => update(['forwards', String(i), 'protocol'], e.target.value)}>
              <option>tcp</option>
              <option>udp</option>
            </select>
            <Button
              type="button"
              variant="ghost"
              onClick={() =>
                update(
                  ['forwards'],
                  config.forwards.filter((_: unknown, n: number) => n !== i),
                )
              }
            >
              {t('حذف', 'Remove')}
            </Button>
          </div>
        ))}
        <Button
          type="button"
          className="mt-3"
          size="sm"
          variant="outline"
          disabled={(config.forwards?.length || 0) >= 40}
          onClick={() => update(['forwards'], [...(config.forwards || []), { listen: '127.0.0.1:9000', target: '127.0.0.1:8000', protocol: 'tcp' }])}
        >
          {t('افزودن فوروارد', 'Add forward')}
        </Button>
        {(config.socks5 || []).map((_: unknown, i: number) => (
          <div key={i} className="mt-3 grid gap-2 rounded-lg border p-3 sm:grid-cols-2">
            {text(['socks5', String(i), 'listen'], 'SOCKS listen', '127.0.0.1:1080')}
            {text(['socks5', String(i), 'username'], t('نام کاربری', 'Username'))}
            {text(['socks5', String(i), 'password'], t('رمز جدید؛ خالی = حفظ رمز فعلی', 'New password; blank = retain current'), '', true)}
            <Button
              type="button"
              variant="ghost"
              onClick={() =>
                update(
                  ['socks5'],
                  config.socks5.filter((_: unknown, n: number) => n !== i),
                )
              }
            >
              {t('حذف', 'Remove')}
            </Button>
          </div>
        ))}
        <Button
          type="button"
          className="mt-3"
          size="sm"
          variant="outline"
          disabled={(config.socks5?.length || 0) >= 8}
          onClick={() => update(['socks5'], [...(config.socks5 || []), { listen: '127.0.0.1:1080', username: '' }])}
        >
          {t('افزودن SOCKS5', 'Add SOCKS5')}
        </Button>
      </details>
      <div className="flex flex-wrap gap-4">
        {check(['manage_firewall'], t('مدیریت قواعد محدود فایروال Paqet', 'Manage scoped Paqet firewall rules'), true)}
        {check(['allow_insecure'], t('پذیرش none/null بدون رمزنگاری حامل', 'Allow unencrypted none/null carrier'))}
        <label className="flex items-center gap-2 text-xs">
          <span>Log</span>
          <select className={selectClass} value={get(['log_level'], 'warn') as string} onChange={e => update(['log_level'], e.target.value)}>
            {['none', 'debug', 'info', 'warn', 'error', 'fatal'].map(v => (
              <option key={v}>{v}</option>
            ))}
          </select>
        </label>
      </div>
      <details className="rounded-lg border p-3">
        <summary className="cursor-pointer text-sm">{t('JSON کامل تنظیمات', 'Full configuration JSON')}</summary>
        <textarea
          aria-label="Paqet JSON"
          dir="ltr"
          spellCheck={false}
          className="border-input bg-background mt-3 min-h-64 w-full rounded-lg border p-3 font-mono text-xs"
          value={value}
          onChange={e => onChange(e.target.value)}
        />
      </details>
    </section>
  )
}
