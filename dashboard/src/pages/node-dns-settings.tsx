import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { fetcher } from '@/service/http'

type NodeState = { node_id: number; revision: number; dns?: { enabled?: boolean; bind_address?: string; port?: number } }
export default function NodeDNSSettings({ nodes, states, fa, onSaved }: { nodes: { id: number; name: string }[]; states: NodeState[]; fa: boolean; onSaved: () => Promise<unknown> }) {
  const t = (f: string, e: string) => (fa ? f : e)
  const [open, setOpen] = useState(false)
  const [node, setNode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const state = states.find(state => state.node_id === Number(node))
  return (
    <>
      <div className="bg-card flex flex-wrap items-center justify-between gap-3 rounded-2xl border p-4">
        <div>
          <h2 className="text-sm font-medium">{t('DNS روی نود ایران یا خروجی', 'DNS on an Iran or exit node')}</h2>
          <p className="text-muted-foreground mt-2 max-w-3xl text-xs leading-6">
            {t(
              'شنوندهٔ UDP/TCP را روی خود نود مدیریت کنید. هر پرس‌وجو با IP واقعی کاربر در پنل احراز و از بودجهٔ DNS کسر می‌شود؛ نود به دیتابیس دسترسی مستقیم ندارد.',
              'Manage UDP/TCP listeners on the node. Each query uses the actual client IP and native DNS budget at the panel; nodes have no direct database access.',
            )}
          </p>
        </div>
        <Button
          variant="outline"
          onClick={() => {
            setNode(String(nodes[0]?.id || ''))
            setOpen(true)
            setError('')
          }}
        >
          {t('تنظیم DNS نود', 'Configure node DNS')}
        </Button>
      </div>
      <Dialog
        open={open}
        onOpenChange={value => {
          if (!busy) setOpen(value)
        }}
      >
        <DialogContent dir={fa ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>{t('DNS بومی روی نود', 'Native node DNS')}</DialogTitle>
            <DialogDescription>
              {t(
                'پورت ۵۳ را با resolver موجود روی سرور مشترک نکنید؛ می‌توانید IP محلی مشخص یا پورت دیگری تعیین کنید. پس از ذخیره، تنظیمات نود را اعمال کنید.',
                'Avoid a port 53 conflict with the existing system resolver. Select a specific local IP or another port. Apply the node configuration after saving.',
              )}
            </DialogDescription>
          </DialogHeader>
          <form
            key={node}
            className="space-y-4"
            onSubmit={async event => {
              event.preventDefault()
              const form = new FormData(event.currentTarget)
              setBusy(true)
              setError('')
              try {
                await fetcher(`/api/smartdns/nodes/${node}/dns`, {
                  method: 'PUT',
                  body: { expected_revision: state?.revision || 0, enabled: form.get('enabled') === 'on', bind_address: String(form.get('bind')), port: Number(form.get('port')) },
                })
                await onSaved()
                setOpen(false)
              } catch (error) {
                const detail = (error as { data?: { detail?: unknown } }).data?.detail
                setError(typeof detail === 'string' ? detail : t('ذخیره انجام نشد؛ پورت و نسخهٔ تنظیمات را بررسی کنید.', 'Save failed. Review listener conflicts and generation.'))
              } finally {
                setBusy(false)
              }
            }}
          >
            <label className="block space-y-2 text-sm">
              <span>{t('نود DNS', 'DNS node')}</span>
              <select aria-label={t('نود DNS', 'DNS node')} className="bg-background h-10 w-full rounded-md border px-3" required value={node} onChange={event => setNode(event.target.value)}>
                <option value="">—</option>
                {nodes.map(node => (
                  <option key={node.id} value={node.id}>
                    {node.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex gap-2 text-sm">
              <input name="enabled" type="checkbox" defaultChecked={state?.dns?.enabled || false} />
              {t('فعال‌سازی DNS روی این نود', 'Enable DNS on this node')}
            </label>
            <label className="block space-y-2 text-sm">
              <span>{t('IP محلی شنوندهٔ DNS', 'DNS listener local IP')}</span>
              <Input name="bind" required dir="ltr" defaultValue={state?.dns?.bind_address || '0.0.0.0'} />
            </label>
            <label className="block space-y-2 text-sm">
              <span>{t('پورت DNS؛ UDP و TCP', 'DNS port; UDP and TCP')}</span>
              <Input name="port" required type="number" min={1} max={65535} defaultValue={state?.dns?.port || 53} />
            </label>
            {error && (
              <p role="alert" className="text-destructive text-sm">
                {error}
              </p>
            )}
            <Button type="submit" disabled={busy || !node}>
              {t('ذخیره DNS نود', 'Save node DNS')}
            </Button>
          </form>
        </DialogContent>
      </Dialog>
    </>
  )
}
