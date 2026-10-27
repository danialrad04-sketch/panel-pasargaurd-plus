import { Input } from '@/components/ui/input'

type Engine = 'backhaul' | 'gost' | 'frp'
const profiles = { backhaul: ['tcp'], gost: ['tls', 'wss', 'tcp', 'ws'], frp: ['tcp', 'websocket', 'kcp', 'quic'] }
const defaults: Record<string, number | boolean | string> = {
  client_port: 11081,
  exit_port: 11082,
  pool: 8,
  keepalive: 30,
  retry: 3,
  dial_timeout: 10,
  no_delay: true,
  mux: true,
  channel_size: 2048,
  heartbeat: 30,
  udp_buffer: 4096,
}
const fields: { key: string; fa: string; en: string; min: number; max: number; engines: Engine[] }[] = [
  { key: 'client_port', fa: 'پورت داخلی مبدا', en: 'Source internal port', min: 1024, max: 65535, engines: ['backhaul', 'gost', 'frp'] },
  { key: 'exit_port', fa: 'پورت داخلی خروجی', en: 'Exit internal port', min: 1024, max: 65535, engines: ['backhaul', 'gost', 'frp'] },
  { key: 'pool', fa: 'اندازهٔ connection pool', en: 'Connection pool', min: 1, max: 64, engines: ['backhaul', 'frp'] },
  { key: 'keepalive', fa: 'فاصلهٔ keepalive؛ ثانیه', en: 'Keepalive seconds', min: 5, max: 300, engines: ['backhaul'] },
  { key: 'retry', fa: 'فاصلهٔ تلاش مجدد؛ ثانیه', en: 'Retry seconds', min: 1, max: 60, engines: ['backhaul'] },
  { key: 'dial_timeout', fa: 'مهلت اتصال؛ ثانیه', en: 'Dial timeout seconds', min: 1, max: 60, engines: ['backhaul', 'frp'] },
  { key: 'channel_size', fa: 'ظرفیت صف اتصال', en: 'Connection queue', min: 16, max: 8192, engines: ['backhaul'] },
  { key: 'heartbeat', fa: 'فاصلهٔ heartbeat؛ ثانیه', en: 'Heartbeat seconds', min: 5, max: 300, engines: ['backhaul', 'frp'] },
  { key: 'udp_buffer', fa: 'حد بستهٔ UDP؛ بایت', en: 'UDP packet bytes', min: 1500, max: 65535, engines: ['frp'] },
]
export default function CarrierEditor({ engine, value, onChange, fa }: { engine: Engine; value: string; onChange: (value: string) => void; fa: boolean }) {
  const t = (f: string, e: string) => (fa ? f : e)
  let data: Record<string, unknown> = {}
  try {
    data = JSON.parse(value)
  } catch {
    /* Parent reports invalid advanced JSON. */
  }
  const set = (key: string, value: unknown) => onChange(JSON.stringify({ ...data, [key]: value }))
  return (
    <section className="bg-muted/20 space-y-4 rounded-2xl border p-4">
      <div>
        <h3 className="font-medium">{engine === 'gost' ? 'GOST' : engine === 'frp' ? 'FRP' : 'Backhaul'} · TCP + UDP</h3>
        <p className="text-muted-foreground mt-2 text-xs leading-6">
          {engine === 'gost'
            ? t('مبدا به خروجی وصل می‌شود. TLS و WSS گواهی همان نود خروجی را بررسی می‌کنند.', 'Source connects to exit. TLS and WSS verify the exit node certificate.')
            : t(
                'نود خروجی اتصال معکوس را به مبدا برقرار می‌کند؛ هر دو همچنان به پنل وصل‌اند. پورت کنترل روی مبدا گوش می‌دهد.',
                'Exit establishes the reverse connection to source. Both retain panel management. Control listens on source.',
              )}
        </p>
      </div>
      <label className="block space-y-2 text-sm">
        <span>{t('پروفایل موتور', 'Engine profile')}</span>
        <select
          aria-label={t('پروفایل موتور', 'Engine profile')}
          className="bg-background h-10 w-full rounded-md border px-3"
          value={String(data.profile || profiles[engine][0])}
          onChange={event => set('profile', event.target.value)}
        >
          {profiles[engine].map(profile => (
            <option key={profile}>{profile}</option>
          ))}
        </select>
      </label>
      {engine === 'backhaul' && (
        <p className="text-muted-foreground text-xs leading-6">
          {t(
            'پروفایل TCP شامل UDP-over-TCP است. حالت‌های دیگر سورس تا پذیرش UDP در این مسیر فعال نمی‌شوند. احراز و رمزگذاری دادهٔ کاربران در SS2022 داخلی حفظ می‌شود.',
            'TCP profile includes UDP-over-TCP. Other upstream modes remain unavailable until UDP acceptance. Inner SS2022 preserves user data authentication and encryption.',
          )}
        </p>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        {fields
          .filter(field => field.engines.includes(engine))
          .map(field => (
            <label key={field.key} className="block space-y-2 text-sm">
              <span>{t(field.fa, field.en)}</span>
              <Input type="number" min={field.min} max={field.max} required value={Number(data[field.key] ?? defaults[field.key])} onChange={event => set(field.key, Number(event.target.value))} />
            </label>
          ))}
      </div>
      <label className="block space-y-2 text-sm">
        <span>{t('آدرس اتصال حامل؛ اختیاری', 'Carrier connect address; optional')}</span>
        <Input
          dir="ltr"
          value={String(data.control_address || '')}
          placeholder={t('پیش‌فرض: آدرس نود کنترل', 'Default: control node address')}
          onChange={event => set('control_address', event.target.value || null)}
        />
      </label>
      {(['backhaul', 'frp'] as Engine[]).includes(engine) && (
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={Boolean(data[engine === 'frp' ? 'mux' : 'no_delay'] ?? true)} onChange={event => set(engine === 'frp' ? 'mux' : 'no_delay', event.target.checked)} />
          {engine === 'frp' ? 'TCP multiplexing' : 'TCP_NODELAY'}
        </label>
      )}
    </section>
  )
}
