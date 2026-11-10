import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Gamepad2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { fetcher } from '@/service/http'

type Profile = { policy_id: number; name: string; routes: number; applied: boolean; accounts: number; address: string }
type UserDns = {
  account: { id: number; enabled: boolean; eligible: boolean; policy_id: number; policy_name: string; relay_byte_limit: number; relay_bytes: number } | null
  leases: { ip: string; expires_at: string }[]
  portal_path: string | null
}

export type GamingDnsDraft = { enabled: boolean; policyId: string; limitGb: string; ips: string }
export const emptyGamingDnsDraft: GamingDnsDraft = { enabled: false, policyId: '', limitGb: '', ips: '' }

const GB = 1024 ** 3
const reason = (e: any) => (typeof e?.data?.detail === 'string' ? e.data.detail : e?.message)

/** Attaches a freshly created user to a gaming DNS profile. Same admin scope as the user itself. */
export async function attachGamingDns(userId: number, draft: GamingDnsDraft) {
  const ips = draft.ips
    .split(/[\s,]+/)
    .map(value => value.trim())
    .filter(Boolean)
  const gb = Number(draft.limitGb)
  return fetcher<{ account_id: number; portal_path: string }>(`/api/smartdns/gaming/${draft.policyId}/attach`, {
    method: 'POST',
    body: { user_id: userId, client_ips: ips, byte_limit: draft.limitGb.trim() && gb > 0 ? Math.round(gb * GB) : 0 },
  })
}

type Props = {
  userId?: number
  draft: GamingDnsDraft
  onDraft: (draft: GamingDnsDraft) => void
}

export function GamingDnsSection({ userId, draft, onDraft }: Props) {
  const { i18n } = useTranslation()
  const fa = i18n.language?.startsWith('fa')
  const t = (f: string, e: string) => (fa ? f : e)
  const client = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [copied, setCopied] = useState(false)
  const profiles = useQuery({ queryKey: ['gaming-profiles'], queryFn: () => fetcher<{ items: Profile[] }>('/api/smartdns/gaming/profiles'), retry: false })
  const current = useQuery({ queryKey: ['user-dns', userId], queryFn: () => fetcher<UserDns>(`/api/dns/users/${userId}`), enabled: !!userId, retry: false })
  const items = profiles.data?.items ?? []
  const account = current.data?.account ?? null

  useEffect(() => {
    if (!draft.policyId && items.length === 1) onDraft({ ...draft, policyId: String(items[0].policy_id) })
  }, [items, draft, onDraft])

  // No gaming profile exists yet: say where to make one instead of showing a dead form.
  if (profiles.isSuccess && items.length === 0 && !account) {
    return (
      <div className="gaming-dns-section rounded-lg border border-dashed p-3 text-sm">
        <div className="flex items-center gap-2 font-medium">
          <Gamepad2 className="text-primary h-4 w-4" />
          {t('DNS گیمینگ', 'Gaming DNS')}
        </div>
        <p className="text-muted-foreground mt-1 leading-6">
          {t(
            'هنوز پروفایل DNS گیمینگ ساخته نشده. از صفحه SmartDNS یکی بسازید تا اینجا برای کاربر فعال شود.',
            'No gaming DNS profile yet. Create one on the SmartDNS page, then enable it per user here.',
          )}
        </p>
      </div>
    )
  }

  const portal = current.data?.portal_path ? window.location.origin + current.data.portal_path : ''
  const refresh = () => client.invalidateQueries({ queryKey: ['user-dns', userId] })

  const attachNow = async () => {
    if (!userId) return
    setBusy(true)
    setError('')
    try {
      await attachGamingDns(userId, draft)
      onDraft({ ...draft, ips: '' })
      await refresh()
    } catch (e: any) {
      setError(reason(e) || t('اتصال انجام نشد.', 'Could not attach.'))
    } finally {
      setBusy(false)
    }
  }

  const setEnabled = async (enabled: boolean) => {
    if (!account) return
    setBusy(true)
    setError('')
    try {
      await fetcher(`/api/dns/accounts/${account.id}`, {
        method: 'PUT',
        body: { policy_id: account.policy_id, enabled, query_limit: 0, qps: 20, relay_enabled: enabled, relay_byte_limit: account.relay_byte_limit, relay_rps: 200 },
      })
      await refresh()
    } catch (e: any) {
      setError(reason(e) || t('تغییر انجام نشد.', 'Could not update.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="gaming-dns-section space-y-3 rounded-lg border p-3 text-sm">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2 font-medium">
          <Gamepad2 className="text-primary h-4 w-4" />
          {t('DNS گیمینگ', 'Gaming DNS')}
        </div>
        {account ? (
          <label className="flex items-center gap-2 text-xs">
            <span>{account.enabled ? t('فعال', 'On') : t('خاموش', 'Off')}</span>
            <Switch checked={account.enabled} disabled={busy} onCheckedChange={value => void setEnabled(value)} aria-label={t('فعال بودن DNS', 'DNS enabled')} />
          </label>
        ) : (
          <label className="flex items-center gap-2 text-xs">
            <span>{t('فعال‌سازی برای این کاربر', 'Enable for this user')}</span>
            <Switch checked={draft.enabled} onCheckedChange={value => onDraft({ ...draft, enabled: value })} aria-label={t('فعال‌سازی DNS گیمینگ', 'Enable gaming DNS')} />
          </label>
        )}
      </div>
      <p className="text-muted-foreground text-xs leading-6">
        {t(
          'انقضا، حجم و وضعیت همین کاربر روی DNS هم اعمال می‌شود. کاربر IP خودش را از لینک DNS اختصاصی ثبت می‌کند.',
          'The user’s expiry, data limit and status also govern DNS. The user registers their own IP on their DNS link.',
        )}
      </p>

      {account && (
        <div className="space-y-2">
          <div className="text-xs">
            {t('پروفایل:', 'Profile:')} <bdi>{account.policy_name}</bdi>
            {' · '}
            {account.eligible ? t('مجاز', 'eligible') : t('غیرمجاز (وضعیت کاربر)', 'not eligible (user status)')}
          </div>
          <div className="text-xs">
            {t('حجم DNS:', 'DNS traffic:')} <bdi dir="ltr">{(account.relay_bytes / GB).toFixed(2)} GB</bdi>
            {account.relay_byte_limit ? <bdi dir="ltr"> / {(account.relay_byte_limit / GB).toFixed(1)} GB</bdi> : ` / ${t('نامحدود', 'unlimited')}`}
          </div>
          <div className="text-xs">
            {t('IPهای ثبت‌شده:', 'Registered IPs:')}{' '}
            {current.data?.leases.length
              ? current.data.leases.map(lease => (
                  <bdi key={lease.ip} dir="ltr" className="me-2 inline-block rounded border px-1.5">
                    {lease.ip}
                  </bdi>
                ))
              : t('هنوز ثبت نشده', 'none yet')}
          </div>
          {portal && (
            <div className="flex gap-2">
              <Input dir="ltr" readOnly value={portal} aria-label="DNS link" className="min-w-0 flex-1 text-xs" />
              <Button
                type="button"
                variant="outline"
                onClick={() => {
                  void navigator.clipboard.writeText(portal)
                  setCopied(true)
                  setTimeout(() => setCopied(false), 1500)
                }}
              >
                {copied ? t('کپی شد', 'Copied') : t('کپی لینک', 'Copy link')}
              </Button>
            </div>
          )}
        </div>
      )}

      {!account && draft.enabled ? (
        <div className="grid gap-2 sm:grid-cols-2">
          <label className="space-y-1 text-xs sm:col-span-2">
            <span>{t('پروفایل گیمینگ', 'Gaming profile')}</span>
            <select className="border-input bg-background h-9 w-full rounded-md border px-2 text-sm" value={draft.policyId} onChange={event => onDraft({ ...draft, policyId: event.target.value })}>
              <option value="">{t('انتخاب کنید', 'Select')}</option>
              {items.map(item => (
                <option key={item.policy_id} value={item.policy_id}>
                  {item.name} — {item.address}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-xs">
            <span>{t('سقف حجم DNS (GB؛ خالی = نامحدود)', 'DNS cap (GB; empty = unlimited)')}</span>
            <Input dir="ltr" inputMode="decimal" value={draft.limitGb} onChange={event => onDraft({ ...draft, limitGb: event.target.value })} />
          </label>
          <label className="space-y-1 text-xs">
            <span>{t('IP بازیکن (اختیاری، حداکثر ۳)', 'Player IPs (optional, up to 3)')}</span>
            <Input dir="ltr" placeholder="198.51.100.7" value={draft.ips} onChange={event => onDraft({ ...draft, ips: event.target.value })} />
          </label>
        </div>
      ) : null}

      {!account && userId && draft.enabled && (
        <Button type="button" size="sm" disabled={busy || !draft.policyId} onClick={attachNow}>
          {t('فعال‌سازی DNS برای این کاربر', 'Enable DNS for this user')}
        </Button>
      )}
      {error && (
        <p role="alert" className="text-destructive text-xs">
          {error}
        </p>
      )}
    </div>
  )
}
