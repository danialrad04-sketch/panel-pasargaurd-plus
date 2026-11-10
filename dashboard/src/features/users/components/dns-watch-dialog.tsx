import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Radar } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import { fetcher } from '@/service/http'

type Row = { name: string; outcome: 'via_gateway' | 'direct' | 'blocked' | 'pending'; count: number; last: number }

const reason = (e: any) => (typeof e?.data?.detail === 'string' ? e.data.detail : e?.message)

/**
 * Recent names one account asked for. Used to find what a game really needs:
 * names answered "direct" can be sent through the gateway with one click.
 * The list lives in memory on the server for an hour and is never stored.
 */
export function DnsWatchDialog({ accountId, policyId }: { accountId: number; policyId: number }) {
  const { i18n } = useTranslation()
  const fa = i18n.language?.startsWith('fa')
  const t = (f: string, e: string) => (fa ? f : e)
  const [open, setOpen] = useState(false)
  const [picked, setPicked] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const watch = useQuery({
    queryKey: ['dns-watch', accountId],
    queryFn: () => fetcher<{ items: Row[] }>(`/api/dns/accounts/${accountId}/watch`),
    enabled: open,
    refetchInterval: open ? 5000 : false,
  })
  const rows = watch.data?.items ?? []
  const outcome = (value: Row['outcome']) =>
    ({
      via_gateway: t('از gateway', 'via gateway'),
      direct: t('مستقیم', 'direct'),
      blocked: t('مسدود', 'blocked'),
      pending: t('آماده نیست', 'not ready'),
    })[value]

  const add = async () => {
    setBusy(true)
    setMessage(null)
    try {
      const result = await fetcher<{ added: string[]; applied: boolean; detail?: string }>(`/api/smartdns/gaming/${policyId}/domains`, { method: 'POST', body: { domains: picked } })
      setPicked([])
      setMessage({
        ok: result.applied,
        text: result.applied
          ? t(`${result.added.length} دامنه اضافه و روی نود اعمال شد. هنوز روی بازی واقعی آزمایش نشده.`, `${result.added.length} domain(s) added and applied. Not yet tested with a real game.`)
          : t('ذخیره شد اما اعمال روی نود موفق نبود؛ از صفحه SmartDNS دوباره اعمال کنید.', 'Saved, but applying to the node failed. Apply again from the SmartDNS page.'),
      })
    } catch (e: any) {
      setMessage({ ok: false, text: reason(e) || t('افزودن انجام نشد.', 'Could not add.') })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button type="button" variant="outline" size="sm" className="gap-1">
          <Radar className="h-4 w-4" />
          {t('پرسش‌های اخیر', 'Recent queries')}
        </Button>
      </DialogTrigger>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>{t('پرسش‌های اخیر DNS', 'Recent DNS queries')}</DialogTitle>
          <DialogDescription>
            {t(
              'بازی را روی دستگاه کاربر اجرا کنید و اینجا ببینید چه دامنه‌هایی پرسیده می‌شود. فقط ۱ ساعت اخیر و فقط در حافظه سرور نگه داشته می‌شود.',
              'Start the game on the user’s device and watch which names it asks for. Kept in server memory for the last hour only.',
            )}
          </DialogDescription>
        </DialogHeader>
        <div className="max-h-[50vh] overflow-y-auto rounded-md border">
          {rows.length === 0 ? (
            <p className="text-muted-foreground p-4 text-center text-sm">
              {watch.isLoading ? '…' : t('هنوز پرسشی ثبت نشده. بازی را باز کنید؛ لیست خودکار تازه می‌شود.', 'No queries yet. Open the game; the list refreshes by itself.')}
            </p>
          ) : (
            <ul className="divide-y text-sm">
              {rows.map(row => (
                <li key={row.name + row.outcome} className="flex items-center gap-3 px-3 py-2">
                  {row.outcome === 'direct' ? (
                    <Checkbox checked={picked.includes(row.name)} onCheckedChange={value => setPicked(value ? [...picked, row.name] : picked.filter(n => n !== row.name))} aria-label={row.name} />
                  ) : (
                    <span className="w-4" />
                  )}
                  <bdi dir="ltr" className="min-w-0 flex-1 truncate font-mono text-xs">
                    {row.name}
                  </bdi>
                  <span className="text-muted-foreground text-xs">×{row.count}</span>
                  <span className={row.outcome === 'via_gateway' ? 'text-xs text-emerald-600' : row.outcome === 'blocked' ? 'text-destructive text-xs' : 'text-muted-foreground text-xs'}>
                    {outcome(row.outcome)}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
        {message && (
          <p role="status" className={message.ok ? 'text-xs text-emerald-600' : 'text-destructive text-xs'}>
            {message.text}
          </p>
        )}
        <div className="flex items-center justify-between gap-2">
          <span className="text-muted-foreground text-xs">{t('دامنه‌های «مستقیم» را انتخاب کنید تا از gateway عبور کنند.', 'Select “direct” names to send them through the gateway.')}</span>
          <Button type="button" size="sm" disabled={busy || picked.length === 0} onClick={add}>
            {t(`افزودن ${picked.length || ''} دامنه`, `Add ${picked.length || ''} domain(s)`)}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
