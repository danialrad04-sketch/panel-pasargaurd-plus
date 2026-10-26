import { useEffect, useState } from 'react'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { fetcher } from '@/service/http'
import type { TunnelView } from '@/service/api'

type Result = {
  can_apply: boolean
  can_publish: boolean
  checks: { code: string; status: 'pass' | 'warning' | 'fail'; detail: string }[]
}

export default function TunnelPreflight({ tunnel, fa, onClose }: { tunnel: TunnelView; fa: boolean; onClose: () => void }) {
  const [result, setResult] = useState<Result | null>(null)
  const [error, setError] = useState(false)
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setError(false)
    setResult(null)
    fetcher<Result>(`/api/tunnels/${tunnel.id}/preflight`, {
      method: 'POST',
      body: { expected_revision: tunnel.revision },
      signal: controller.signal,
    })
      .then(setResult)
      .catch(() => {
        if (!controller.signal.aborted) setError(true)
      })
    return () => controller.abort()
  }, [tunnel.id, tunnel.revision, retry])
  return (
    <Dialog
      open
      onOpenChange={open => {
        if (!open) onClose()
      }}
    >
      <DialogContent className="max-h-[85dvh] overflow-y-auto sm:max-w-2xl" dir={fa ? 'rtl' : 'ltr'}>
        <DialogHeader>
          <DialogTitle>
            {fa ? 'بررسی آمادگی تونل' : 'Tunnel readiness'} · {tunnel.name}
          </DialogTitle>
          <DialogDescription>
            {fa ? 'بررسی تنظیمات و وضعیت نودها؛ عبور واقعی ترافیک بازی هنوز آزمون نشده است.' : 'Configuration and node checks. Actual game traffic has not been probed.'}
          </DialogDescription>
        </DialogHeader>
        {error ? (
          <p role="alert">{fa ? 'بررسی انجام نشد؛ وضعیت و نسخهٔ مسیر را تازه‌سازی کنید.' : 'Check failed. Refresh the route and its revision.'}</p>
        ) : !result ? (
          <p role="status">{fa ? 'در حال بررسی…' : 'Checking…'}</p>
        ) : (
          <>
            <div className="grid grid-cols-2 gap-3 text-sm">
              <div className="rounded-lg border p-3">
                {fa ? 'آمادگی اعمال' : 'Apply readiness'}: {result.can_apply ? '✓' : '✕'}
              </div>
              <div className="rounded-lg border p-3">
                {fa ? 'آمادگی انتشار هاست' : 'Host publication readiness'}: {result.can_publish ? '✓' : '✕'}
              </div>
            </div>
            <ul className="space-y-2" aria-label={fa ? 'نتیجهٔ بررسی‌ها' : 'Check results'}>
              {result.checks.map(check => (
                <li key={check.code} className="rounded-lg border p-3 text-sm">
                  <div className="flex items-center justify-between gap-2">
                    <code dir="ltr">{check.code}</code>
                    <span>{check.status === 'pass' ? (fa ? 'تأیید' : 'Passed') : check.status === 'warning' ? (fa ? 'نیاز به بررسی' : 'Review') : fa ? 'نیاز به اصلاح' : 'Failed'}</span>
                  </div>
                  <p className="text-muted-foreground mt-2" dir="ltr">
                    {check.detail}
                  </p>
                </li>
              ))}
            </ul>
          </>
        )}
        <Button variant="outline" disabled={!result && !error} onClick={() => setRetry(value => value + 1)}>
          {fa ? 'بررسی مجدد' : 'Check again'}
        </Button>
      </DialogContent>
    </Dialog>
  )
}
