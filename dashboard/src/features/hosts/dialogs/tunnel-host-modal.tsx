import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Cable, Loader2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { fetcher } from '@/service/http'
import { BaseHost, TunnelState, getGetHostsQueryKey } from '@/service/api'

const selectClass = 'border-input bg-background h-10 w-full rounded-md border px-3 text-sm'

export default function TunnelHostModal({ hosts, onPublished }: { hosts: BaseHost[]; onPublished: () => void }) {
  const { i18n } = useTranslation()
  const fa = i18n.language.startsWith('fa')
  const t = (f: string, e: string) => (fa ? f : e)
  const [open, setOpen] = useState(false)
  const [tunnelId, setTunnelId] = useState('')
  const [templateId, setTemplateId] = useState('')
  const [address, setAddress] = useState('')
  const [port, setPort] = useState('')
  const [remark, setRemark] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const cache = useQueryClient()
  const state = useQuery({ queryKey: ['native-tunnel-host-options'], queryFn: () => fetcher<TunnelState>('/api/tunnels'), enabled: open })
  const selected = state.data?.items.find(row => String(row.id) === tunnelId)
  const source = state.data?.nodes.find(node => node.id === selected?.source_node_id)
  const exit = state.data?.nodes.find(node => node.id === selected?.exit_node_id)
  const ready = selected?.enabled && selected.revision === selected.applied_revision && !state.data?.applying
  const templates = hosts.filter(host => !host.native_tunnel_id && selected?.inbound_tags.includes(host.inbound_tag || ''))
  const existing = hosts.find(host => host.native_tunnel_id === selected?.id && host.inbound_tag === hosts.find(h => String(h.id) === templateId)?.inbound_tag)
  useEffect(() => {
    setAddress(source?.address || '')
    setTemplateId('')
    setPort('')
    setRemark(source && exit ? `${source.name} → ${exit.name}` : '')
    setError('')
  }, [selected?.id, source?.address])

  async function publish(event: React.FormEvent) {
    event.preventDefault()
    if (!selected || !ready) return
    setBusy(true)
    setError('')
    try {
      await fetcher(`/api/tunnels/${selected.id}/hosts`, {
        method: 'POST',
        body: {
          expected_revision: selected.revision,
          template_host_id: Number(templateId),
          address: address.trim() || null,
          port: port ? Number(port) : null,
          remark: remark.trim() || null,
        },
      })
      await cache.invalidateQueries({ queryKey: getGetHostsQueryKey() })
      onPublished()
      toast.success(t('هاست ورودی تونل در سابلینک ثبت شد', 'Tunnel ingress host published'))
      setOpen(false)
    } catch (e: any) {
      const detail = e?.data?.detail || e?.response?._data?.detail
      setError(typeof detail === 'string' ? detail : t('ثبت هاست انجام نشد؛ تنظیمات و نسخهٔ تونل را بررسی کنید.', 'Host publication failed; verify settings and tunnel revision.'))
      void state.refetch()
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <Button
        variant="outline"
        onClick={() => {
          setOpen(true)
          setError('')
        }}
      >
        <Cable className="size-4" />
        {t('افزودن ورودی تونل به سابلینک', 'Add tunnel ingress to subscription')}
      </Button>
      <Dialog
        open={open}
        onOpenChange={value => {
          if (!busy) setOpen(value)
        }}
      >
        <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-xl" dir={fa ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>{t('هاست ورودی ایران و خروجی تونل', 'Tunnel ingress subscription host')}</DialogTitle>
            <DialogDescription>
              {t(
                'پنل اصلی و اتصال مدیریتی هر دو نود مستقل می‌مانند. کاربر به ورودی ایران وصل می‌شود و ترافیک منتخب از نود خروجی عبور می‌کند.',
                'The main panel and both node management connections stay independent. Users connect to the ingress; selected traffic crosses the tunnel to the exit.',
              )}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={publish} className="space-y-4">
            <label className="block space-y-2 text-sm">
              <span>{t('مسیر تونل', 'Tunnel path')}</span>
              <select className={selectClass} aria-label={t('مسیر تونل', 'Tunnel path')} value={tunnelId} onChange={e => setTunnelId(e.target.value)} required>
                <option value="">{t('انتخاب مسیر', 'Choose a path')}</option>
                {state.data?.items.map(row => (
                  <option key={row.id} value={row.id} disabled={!row.enabled || row.applied_revision !== row.revision}>
                    {row.name}
                    {!row.enabled || row.applied_revision !== row.revision ? t(' — در انتظار فعال‌سازی و اعمال', ' — enable and apply first') : ''}
                  </option>
                ))}
              </select>
            </label>
            {state.isPending && <p className="text-muted-foreground text-sm">{t('دریافت مسیرها…', 'Loading paths…')}</p>}
            {state.isError && (
              <p role="alert" className="text-destructive text-sm">
                {t('دریافت مسیرها انجام نشد.', 'Could not load paths.')}
              </p>
            )}
            {state.data?.items.length === 0 && (
              <p className="text-muted-foreground text-sm">{t('ابتدا در بخش تونل‌ها مسیر ایران به خروجی را بسازید و اعمال کنید.', 'Create and apply an ingress-to-exit path in Tunnels first.')}</p>
            )}
            {selected && (
              <div className="bg-muted/50 space-y-2 rounded-xl border p-4 text-sm">
                <p>
                  <bdi>{source?.name}</bdi> ← {t('ورودی کاربر', 'User ingress')} · <bdi>{exit?.name}</bdi> ← {t('خروجی اینترنت', 'Internet exit')}
                </p>
                <p className="text-muted-foreground text-xs leading-6">
                  {t(
                    'پورت داخلی Paqet یا API نود وارد سابلینک نمی‌شود. نمایش این هاست به فعال‌بودن و اعمال‌شدن نسخهٔ مسیر وابسته است؛ این وضعیت تضمین سلامت لحظه‌ای شبکه نیست.',
                    'Paqet carrier and node API ports are never published. This host appears only while the path is enabled and its revision is applied; that does not certify live network health.',
                  )}
                </p>
              </div>
            )}
            <label className="block space-y-2 text-sm">
              <span>{t('هاست الگو؛ حفظ تنظیمات TLS و Reality', 'Template host; retain TLS and Reality settings')}</span>
              <select className={selectClass} aria-label={t('هاست الگو', 'Template host')} value={templateId} onChange={e => setTemplateId(e.target.value)} required disabled={!ready}>
                <option value="">{t('انتخاب هاست با ورودی یکسان', 'Choose a host for the same inbound')}</option>
                {templates.map(host => (
                  <option key={host.id} value={host.id!}>
                    {host.remark} · {host.inbound_tag}
                  </option>
                ))}
              </select>
            </label>
            {selected && !templates.length && (
              <p className="text-muted-foreground text-xs">
                {t(
                  'یک هاست عادی برای یکی از ورودی‌های منتخب این تونل بسازید؛ سپس آن را به‌عنوان الگو انتخاب کنید.',
                  'Create an ordinary host for a selected tunnel inbound, then choose it as the template.',
                )}
              </p>
            )}
            <label className="block space-y-2 text-sm">
              <span>{t('IP عمومی ورودی ایران', 'Public ingress IP')}</span>
              <Input aria-label={t('IP عمومی ورودی ایران', 'Public ingress IP')} dir="ltr" value={address} onChange={e => setAddress(e.target.value)} required />
            </label>
            <p className="text-muted-foreground text-xs leading-6">
              {t(
                'از آدرس نود مبدا پر می‌شود؛ اگر IP مدیریتی خصوصی است یا NAT دارید، IP قابل دسترس برای کاربران را جایگزین کنید. SNI باید با گواهی ورودی ایران سازگار باشد.',
                'Filled from the source node. Replace a private management address with the client-reachable IP when needed. SNI must match the ingress certificate.',
              )}
            </p>
            <label className="block space-y-2 text-sm">
              <span>{t('پورت عمومی کاربر؛ خالی = پورت ورودی مبدا', 'Public user port; blank = source inbound port')}</span>
              <Input aria-label={t('پورت عمومی کاربر', 'Public user port')} type="number" min={1} max={65535} value={port} onChange={e => setPort(e.target.value)} />
            </label>
            <label className="block space-y-2 text-sm">
              <span>{t('نام در سابلینک', 'Subscription name')}</span>
              <Input aria-label={t('نام در سابلینک', 'Subscription name')} value={remark} onChange={e => setRemark(e.target.value)} maxLength={256} />
            </label>
            <p className="text-muted-foreground text-xs leading-6">
              {t(
                'فقط کاربران مجاز به این ورودی، هاست را دریافت می‌کنند. ثبت دوباره برای همین ورودی، هاست وابستهٔ قبلی را به‌روزرسانی می‌کند. برای TLS، گواهی معتبر روی نود مبدا لازم است.',
                'Only users assigned to this inbound receive the host. Publishing again updates the existing bound host for this inbound. TLS requires a valid certificate on the source node.',
              )}
            </p>
            {error && (
              <p role="alert" className="text-destructive text-sm break-words">
                {error}
              </p>
            )}
            <Button type="submit" className="w-full" disabled={busy || !ready || !templateId}>
              {busy && <Loader2 className="size-4 animate-spin" />}
              {existing ? t('به‌روزرسانی هاست ورودی', 'Update ingress host') : t('ثبت هاست ورودی در سابلینک', 'Publish ingress host')}
            </Button>
          </form>
        </DialogContent>
      </Dialog>
    </>
  )
}
