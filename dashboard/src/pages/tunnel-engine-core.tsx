import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { fetcher } from '@/service/http'

type Engine = { id: string; label: string; version?: string; primary?: boolean; profiles: string[]; reverse?: boolean }
type Capability = { node_id: number; items: { id: string; available: boolean; compatible?: boolean; capability?: { installed?: boolean } | null }[] }
export default function TunnelEngineCore({ nodes, fa }: { nodes: { id: number; name: string }[]; fa: boolean }) {
  const t = (f: string, e: string) => (fa ? f : e)
  const catalog = useQuery({ queryKey: ['native-engine-catalog'], queryFn: () => fetcher<{ primary: string; items: Engine[] }>('/api/tunnels/engines') })
  const [source, setSource] = useState('')
  const [exit, setExit] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<Capability[]>([])
  const [error, setError] = useState(false)
  return (
    <details className="bg-card rounded-2xl border p-5">
      <summary className="cursor-pointer text-sm font-medium">{t('هستهٔ تونل · Paqet و موتورهای مکمل', 'Tunnel core · Paqet and companion engines')}</summary>
      <p className="text-muted-foreground mt-3 max-w-3xl text-xs leading-6">
        {t(
          'Paqet انتخاب اصلی مسیرهای جدید است. موتورهای دیگر از همان نود، کاربر و سابلینک استفاده می‌کنند. قابلیت نصب‌شده و نتیجهٔ سنجش ترافیک دو وضعیت جدا هستند؛ تغییر خودکار مسیر فقط در سیاست failover صریح انجام می‌شود.',
          'Paqet is the default for new paths. Companion engines share nodes, users and subscriptions. Installed capability and measured traffic are separate; automatic switching requires an explicit failover policy.',
        )}
      </p>
      <div className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {catalog.data?.items
          .filter(engine => engine.id !== 'shadowsocks-2022')
          .map(engine => (
            <div key={engine.id} className="bg-background rounded-xl border p-4">
              <div className="flex items-center justify-between gap-2">
                <span className="font-medium" dir="ltr">
                  {engine.label}
                </span>
                {engine.primary && <span className="bg-primary/10 text-primary rounded-md px-2 py-1 text-[10px]">{t('اصلی', 'Primary')}</span>}
              </div>
              <p className="text-muted-foreground mt-2 text-xs" dir="ltr">
                {engine.version}
              </p>
              <p className="mt-3 text-xs" dir="ltr">
                {engine.profiles.join(' · ')}
              </p>
              <p className="text-muted-foreground mt-2 text-xs">{engine.reverse ? t('خروجی به مبدا وصل می‌شود', 'Exit connects to source') : t('اتصال مبدا به خروجی', 'Source connects to exit')}</p>
              {result.length === 2 && (
                <p className="mt-3 text-xs">
                  {result.every(node => node.items.some(item => item.id === engine.id && item.available && item.compatible))
                    ? t('نصب روی هر دو نود تأیید شد', 'Installed on both nodes')
                    : t('ارتقا یا بررسی نود لازم است', 'Node upgrade or inspection required')}
                </p>
              )}
            </div>
          ))}
      </div>
      <div className="mt-4 flex flex-wrap items-end gap-3">
        {[
          { value: source, set: setSource, label: t('مبدا برای بررسی موتور', 'Source capability node') },
          { value: exit, set: setExit, label: t('خروجی برای بررسی موتور', 'Exit capability node') },
        ].map(field => (
          <label key={field.label} className="min-w-40 flex-1 space-y-2 text-xs">
            <span>{field.label}</span>
            <select
              aria-label={field.label}
              className="bg-background h-10 w-full rounded-md border px-3"
              value={field.value}
              onChange={event => {
                field.set(event.target.value)
                setResult([])
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
        ))}
        <Button
          variant="outline"
          disabled={busy || !source || !exit || source === exit}
          onClick={async () => {
            setBusy(true)
            setError(false)
            setResult([])
            try {
              setResult(await Promise.all([source, exit].map(id => fetcher<Capability>(`/api/tunnels/nodes/${id}/engines`))))
            } catch {
              setError(true)
            } finally {
              setBusy(false)
            }
          }}
        >
          {t('بررسی موتورهای دو نود', 'Inspect both node engines')}
        </Button>
      </div>
      {error && (
        <p role="alert" className="text-destructive mt-3 text-xs">
          {t('بررسی انجام نشد؛ ارتباط پنل با نودها را بررسی کنید.', 'Inspection failed. Review panel-to-node connectivity.')}
        </p>
      )}
    </details>
  )
}
