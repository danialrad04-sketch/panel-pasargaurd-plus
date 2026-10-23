import { useDeferredValue, useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Activity, ArrowLeftRight, Copy, Globe2, KeyRound, Plus, RefreshCw, Search, ShieldCheck, SlidersHorizontal, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useAdmin } from '@/hooks/use-admin'
import { hasPermission, isOwner } from '@/utils/rbac'
import { fetcher } from '@/service/http'
import type { DNSAccountView, DNSPolicyView, DNSPeriodView, DNSLeaseView, DNSAuditView, DNSRuntime } from '@/service/api'

type Rule = { domain: string; action: 'allow' | 'block' | 'rewrite'; addresses: string[] }
type Policy = Omit<DNSPolicyView, 'rules'> & { rules: Rule[] }
type CatalogGroup = { key: string; name?: string; label?: string; domains: string[] }
type Catalog = { services: { key: string; name?: string; label?: string; groups: CatalogGroup[] }[]; counts: { services: number; groups: number; domains: number } }
const panel = 'rounded-2xl border bg-card p-5'
const selectClass = 'border-input bg-background h-10 w-full rounded-md border px-3 text-sm'

export default function NativeDNS() {
  const { admin } = useAdmin()
  const { i18n } = useTranslation()
  const fa = i18n.language.startsWith('fa')
  const t = (f: string, en: string) => (fa ? f : en)
  const owner = isOwner(admin)
  const can = (action: string) => hasPermission(admin, 'dns', action)
  const queryClient = useQueryClient()
  const [tab, setTab] = useState('accounts')
  const [search, setSearch] = useState('')
  const deferredSearch = useDeferredValue(search)
  const [offset, setOffset] = useState(0)
  const [selected, setSelected] = useState<number | null>(null)
  const [accountModal, setAccountModal] = useState<DNSAccountView | 'new' | null>(null)
  const [policyModal, setPolicyModal] = useState<Policy | 'new' | null>(null)
  const [rules, setRules] = useState<Rule[]>([])
  const [confirm, setConfirm] = useState<{ title: string; action: () => Promise<unknown> } | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [token, setToken] = useState<string | null>(null)
  const [userSearch, setUserSearch] = useState('')
  const [catalogSelected, setCatalogSelected] = useState<string[]>([])
  const [catalogSearch, setCatalogSearch] = useState('')
  const [historyOffset, setHistoryOffset] = useState(0)
  const [auditOffset, setAuditOffset] = useState(0)
  const read = can('read')
  const accounts = useQuery({
    queryKey: ['native-dns', 'accounts', deferredSearch, offset],
    queryFn: () => fetcher<{ items: DNSAccountView[]; total: number }>(`/api/dns/accounts?offset=${offset}&limit=20&search=${encodeURIComponent(deferredSearch)}`),
    enabled: read,
    refetchInterval: 15000,
  })
  const policies = useQuery({ queryKey: ['native-dns', 'policies'], queryFn: () => fetcher<Policy[]>('/api/dns/policies'), enabled: read })
  const runtime = useQuery({ queryKey: ['native-dns', 'runtime'], queryFn: () => fetcher<DNSRuntime>('/api/dns/runtime'), enabled: read })
  const catalog = useQuery({ queryKey: ['native-dns', 'catalog'], queryFn: () => fetcher<Catalog>('/api/dns/catalog'), enabled: read && tab === 'catalog' })
  const periods = useQuery({
    queryKey: ['native-dns', 'periods', selected, historyOffset],
    queryFn: () => fetcher<DNSPeriodView[]>(`/api/dns/accounts/${selected}/periods?offset=${historyOffset}&limit=10`),
    enabled: read && selected !== null,
  })
  const leases = useQuery({ queryKey: ['native-dns', 'leases', selected], queryFn: () => fetcher<DNSLeaseView[]>(`/api/dns/accounts/${selected}/leases`), enabled: read && selected !== null })
  const audit = useQuery({ queryKey: ['native-dns', 'audit', auditOffset], queryFn: () => fetcher<DNSAuditView[]>(`/api/dns/audit?offset=${auditOffset}&limit=30`), enabled: owner && tab === 'audit' })
  const users = useQuery({
    queryKey: ['native-dns', 'users', userSearch],
    queryFn: () => fetcher<{ users: { id: number; username: string }[] }>(`/api/users/simple?limit=30&search=${encodeURIComponent(userSearch)}`),
    enabled: accountModal === 'new' && hasPermission(admin, 'users', 'read_simple'),
  })
  const account = accounts.data?.items.find(a => a.id === selected)
  useEffect(() => {
    setToken(null)
    setHistoryOffset(0)
  }, [selected])
  useEffect(() => {
    setOffset(0)
    setSelected(null)
  }, [deferredSearch])
  useEffect(() => {
    setError('')
  }, [accountModal, policyModal, confirm])
  const mutate = async (action: () => Promise<unknown>) => {
    if (busy) return
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await action()
      await queryClient.invalidateQueries({ queryKey: ['native-dns'] })
      setNotice(t('تغییرات ذخیره شد.', 'Changes saved.'))
    } catch (e) {
      const detail = (e as { data?: { detail?: unknown } }).data?.detail
      setError(typeof detail === 'string' ? detail : t('درخواست انجام نشد. ورودی‌ها و دسترسی خود را بررسی کنید.', 'Request failed. Check your inputs and permissions.'))
    } finally {
      setBusy(false)
    }
  }
  const copy = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value)
      setNotice(t('در حافظه کپی شد.', 'Copied to clipboard.'))
    } catch {
      setError(t('کپی خودکار ممکن نیست؛ آدرس را دستی انتخاب کنید.', 'Select and copy the address manually.'))
    }
  }
  const endpoint = runtime.data?.doh || '/dns-query'
  const tokenURL = token
    ? new URL(
        endpoint.startsWith('https://') ? `${endpoint}/${token}` : `${(import.meta.env.VITE_BASE_API || window.location.origin).replace(/\/$/, '')}/${endpoint.replace(/^\//, '')}/${token}`,
        window.location.origin,
      ).href
    : ''

  const hasBudget = (a: DNSAccountView) => !a.query_limit || a.queries < a.query_limit
  const number = (value: number) => value.toLocaleString(fa ? 'fa-IR' : 'en-US')
  const date = (value: string) => new Date(value).toLocaleString(fa ? 'fa-IR' : 'en-US')
  if (!read) return <p className="p-6">{t('دسترسی مدیریت DNS برای این حساب فعال نیست.', 'DNS access is not enabled for this administrator.')}</p>
  const hasQueryError = accounts.isError || policies.isError || runtime.isError || periods.isError || leases.isError || audit.isError || catalog.isError
  return (
    <div className="flex flex-col gap-5 p-4 lg:p-6" dir={fa ? 'rtl' : 'ltr'}>
      <header className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <span className="bg-primary/10 text-primary rounded-2xl p-3">
            <Globe2 className="h-7 w-7" />
          </span>
          <div>
            <p className="text-muted-foreground text-xs tracking-widest">PASARGUARD PLUS</p>
            <h1 className="text-2xl font-semibold">{t('DNS و سیاست‌های دامنه', 'DNS & domain policies')}</h1>
            <p className="text-muted-foreground text-sm">{t('مدیریت DNS در کنار کاربران و سرویس‌های شما', 'DNS management alongside your users and services')}</p>
          </div>
        </div>
        <Button variant="outline" onClick={() => void queryClient.invalidateQueries({ queryKey: ['native-dns'] })}>
          <RefreshCw className="me-2 h-4 w-4" />
          {t('تازه‌سازی', 'Refresh')}
        </Button>
      </header>
      <div className="grid gap-3 sm:grid-cols-3">
        {[
          [t('حساب‌های DNS', 'DNS accounts'), accounts.data ? number(accounts.data.total) : '—', ShieldCheck],
          [t('سیاست‌های دامنه', 'Domain policies'), policies.data ? number(policies.data.length) : '—', SlidersHorizontal],
          [t('وضعیت تنظیمات سرویس', 'Service configuration'), runtime.data ? (runtime.data.enabled ? t('فعال در تنظیمات', 'Enabled in configuration') : t('غیرفعال', 'Disabled')) : '—', Activity],
        ].map(([label, value, Icon]) => {
          const Symbol = Icon as typeof Globe2
          return (
            <div className={panel} key={String(label)}>
              <div className="text-muted-foreground flex items-center justify-between gap-2 text-xs">
                <span>{String(label)}</span>
                <Symbol className="h-4 w-4" />
              </div>
              <p className="mt-3 text-xl font-semibold">{String(value)}</p>
            </div>
          )
        })}
      </div>
      {runtime.data && !runtime.data.enabled && (
        <div className="bg-muted/40 rounded-xl border p-4 text-sm">
          {t(
            'برای پاسخ‌گویی DNS، DNS_ENABLED را در تنظیمات سرویس فعال کنید. حساب‌ها و سیاست‌ها را می‌توانید از همین حالا آماده کنید.',
            'Enable DNS_ENABLED in the service configuration to serve DNS. Accounts and policies can be prepared now.',
          )}
        </div>
      )}
      {error && !accountModal && !policyModal && !confirm && (
        <p role="alert" className="text-destructive border-destructive rounded-xl border p-4 text-sm">
          {error}
        </p>
      )}
      {hasQueryError && (
        <p role="alert" className="text-destructive text-sm">
          {t('دریافت بخشی از اطلاعات ناموفق بود. دوباره تازه‌سازی کنید.', 'Some information could not be loaded. Refresh to try again.')}
        </p>
      )}
      {notice && (
        <p role="status" className="bg-primary/10 text-primary rounded-xl p-3 text-sm">
          {notice}
        </p>
      )}
      <nav className="flex gap-2 overflow-auto border-b" aria-label={t('بخش‌های DNS', 'DNS sections')}>
        {[
          ['accounts', t('حساب‌ها', 'Accounts')],
          ['policies', t('سیاست‌ها', 'Policies')],
          ['catalog', t('کاتالوگ سرویس‌ها', 'Service catalog')],
          ...(owner ? [['audit', t('رویدادها', 'Audit log')]] : []),
        ].map(([key, label]) => (
          <button
            className={`border-b-2 px-4 py-3 text-sm whitespace-nowrap ${tab === key ? 'border-primary text-primary' : 'text-muted-foreground border-transparent'}`}
            key={key}
            aria-current={tab === key ? 'page' : undefined}
            onClick={() => setTab(key)}
          >
            {label}
          </button>
        ))}
      </nav>
      {tab === 'accounts' && (
        <>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="relative w-full sm:max-w-sm">
              <Search className="text-muted-foreground absolute start-3 top-3 h-4 w-4" />
              <Input
                className="ps-9"
                aria-label={t('جستجوی نام کاربری', 'Search username')}
                placeholder={t('جستجوی نام کاربری…', 'Search username…')}
                value={search}
                onChange={e => setSearch(e.target.value)}
              />
            </div>
            {can('create') && (
              <Button
                disabled={!policies.data?.length}
                onClick={() => {
                  setAccountModal('new')
                  setUserSearch('')
                }}
              >
                <Plus className="me-2 h-4 w-4" />
                {t('حساب DNS جدید', 'New DNS account')}
              </Button>
            )}
          </div>
          {!policies.isLoading && policies.data?.length === 0 && <p className="text-muted-foreground text-sm">{t('ابتدا در بخش سیاست‌ها یک سیاست دامنه بسازید.', 'Create a domain policy first.')}</p>}
          {accounts.isPending ? (
            <p className="text-muted-foreground p-6">{t('در حال دریافت حساب‌ها…', 'Loading accounts…')}</p>
          ) : accounts.data?.items.length === 0 ? (
            <div className={`${panel} text-center`}>
              <Globe2 className="text-primary mx-auto mb-3 h-8 w-8" />
              <h2 className="font-medium">{t('حساب DNS پیدا نشد', 'No DNS accounts found')}</h2>
              <p className="text-muted-foreground mt-1 text-sm">{t('برای یکی از کاربران فعلی پاسارگارد، دسترسی DNS تعریف کنید.', 'Create DNS access for an existing PasarGuard user.')}</p>
            </div>
          ) : (
            <div className="grid gap-3 lg:grid-cols-2">
              {accounts.data?.items.map(a => (
                <button
                  key={a.id}
                  onClick={() => {
                    setSelected(a.id)
                    setError('')
                  }}
                  className={`${panel} ${selected === a.id ? 'border-primary ring-primary/15 ring-2' : ''} text-start`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <div>
                      <h2 className="font-semibold">
                        <bdi>{a.username}</bdi>
                      </h2>
                      <p className="text-muted-foreground text-xs">
                        {a.policy_name} · {t('دوره', 'Period')} {number(a.period)}
                      </p>
                    </div>
                    <span className={`rounded-full px-3 py-1 text-xs ${a.enabled && a.eligible && hasBudget(a) ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground'}`}>
                      {!a.enabled || !a.eligible ? t('متوقف', 'Stopped') : hasBudget(a) ? t('مجاز', 'Eligible') : t('پایان سهمیه', 'Budget exhausted')}
                    </span>
                  </div>
                  <div className="mt-4 flex justify-between gap-2 text-sm">
                    <span>
                      {number(a.queries)} {t('درخواست', 'queries')}
                    </span>
                    <span className="text-muted-foreground">{a.query_limit ? `${t('سقف', 'Limit')} ${number(a.query_limit)}` : t('بدون سقف درخواست', 'Unlimited queries')}</span>
                  </div>
                  <div className="bg-muted mt-3 h-1.5 overflow-hidden rounded-full">
                    <div className="bg-primary h-full rounded-full" style={{ width: `${a.query_limit ? Math.min(100, (a.queries / a.query_limit) * 100) : 0}%` }} />
                  </div>
                </button>
              ))}
            </div>
          )}
          <div className="flex justify-between gap-3">
            <Button
              variant="outline"
              disabled={offset === 0}
              onClick={() => {
                setSelected(null)
                setOffset(Math.max(0, offset - 20))
              }}
            >
              {t('صفحه قبل', 'Previous')}
            </Button>
            <span className="text-muted-foreground text-sm">
              {accounts.data ? number(accounts.data.total) : '—'} {t('حساب', 'accounts')}
            </span>
            <Button
              variant="outline"
              disabled={offset + 20 >= (accounts.data?.total ?? 0)}
              onClick={() => {
                setSelected(null)
                setOffset(offset + 20)
              }}
            >
              {t('صفحه بعد', 'Next')}
            </Button>
          </div>
          {account && (
            <section className={`${panel} space-y-5`}>
              <div className="flex flex-wrap justify-between gap-3">
                <div>
                  <h2 className="text-lg font-semibold">
                    {t('جزئیات حساب', 'Account details')} · <bdi>{account.username}</bdi>
                  </h2>
                  <p className="text-muted-foreground text-xs">
                    {t('این سهمیه با حجم VPN جدا محاسبه می‌شود و وضعیت کاربر پاسارگارد را رعایت می‌کند.', 'Query accounting is separate from VPN traffic and follows the PasarGuard user status.')}
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  {can('update') && (
                    <Button variant="outline" onClick={() => setAccountModal(account)}>
                      {t('ویرایش', 'Edit')}
                    </Button>
                  )}
                  {can('reset_usage') && (
                    <Button
                      variant="outline"
                      onClick={() =>
                        setConfirm({
                          title: t('دوره فعلی بسته و یک دوره تازه بدون مصرف ساخته شود؟ تاریخچه حفظ می‌شود.', 'Close the current period and create a fresh period? History will be retained.'),
                          action: () => fetcher(`/api/dns/accounts/${account.id}/reset`, { method: 'POST' }),
                        })
                      }
                    >
                      <ArrowLeftRight className="me-2 h-4 w-4" />
                      {t('دوره جدید', 'New period')}
                    </Button>
                  )}
                  {can('delete') && (
                    <Button
                      variant="outline"
                      onClick={() =>
                        setConfirm({
                          title: t('حساب DNS و تاریخچه مصرف آن حذف شوند؟ کاربر VPN باقی می‌ماند.', 'Delete the DNS account and its history? The VPN user will remain.'),
                          action: async () => {
                            await fetcher(`/api/dns/accounts/${account.id}`, { method: 'DELETE' })
                            setSelected(null)
                          },
                        })
                      }
                    >
                      <Trash2 className="text-destructive h-4 w-4" />
                    </Button>
                  )}
                </div>
              </div>
              <div className="grid gap-3 sm:grid-cols-4">
                {[
                  [t('پاسخ مسدود', 'Blocked'), account.blocked],
                  [t('خطا', 'Errors'), account.errors],
                  [t('بایت دریافت‌شده', 'Request bytes'), account.request_bytes],
                  [t('بایت پاسخ', 'Response bytes'), account.response_bytes],
                ].map(([label, value]) => (
                  <div key={label} className="bg-muted/40 rounded-xl p-3">
                    <p className="text-muted-foreground text-xs">{label}</p>
                    <strong>{number(Number(value))}</strong>
                  </div>
                ))}
              </div>
              {can('rotate_token') && (
                <div className="space-y-3 border-t pt-4">
                  <div className="flex flex-wrap gap-2">
                    <Button
                      variant="outline"
                      disabled={busy}
                      onClick={() =>
                        void mutate(async () => {
                          const result = await fetcher<{ token: string }>(`/api/dns/accounts/${account.id}/credentials`, { method: 'POST' })
                          setToken(result.token)
                        })
                      }
                    >
                      <KeyRound className="me-2 h-4 w-4" />
                      {t('نمایش آدرس خصوصی DoH', 'Reveal private DoH URL')}
                    </Button>
                    <Button
                      variant="outline"
                      onClick={() =>
                        setConfirm({
                          title: t(
                            'اعتبارنامه DNS عوض و دسترسی IPهای ثبت‌شده لغو شود؟ دستگاه‌ها باید آدرس تازه بگیرند.',
                            'Rotate the DNS credential and revoke all source IP leases? Devices will need the new URL.',
                          ),
                          action: async () => {
                            const result = await fetcher<{ token: string }>(`/api/dns/accounts/${account.id}/rotate`, { method: 'POST' })
                            setToken(result.token)
                          },
                        })
                      }
                    >
                      {t('تعویض اعتبارنامه', 'Rotate credential')}
                    </Button>
                  </div>
                  {token && (
                    <div className="flex flex-wrap gap-2">
                      <Input dir="ltr" readOnly aria-label="DoH URL" value={tokenURL} className="min-w-0 flex-1" />
                      <Button variant="outline" onClick={() => void copy(tokenURL)} aria-label={t('کپی آدرس DNS', 'Copy DNS URL')}>
                        <Copy className="h-4 w-4" />
                      </Button>
                    </div>
                  )}
                </div>
              )}
              <div className="grid gap-5 xl:grid-cols-2">
                <div>
                  <h3 className="mb-3 font-semibold">{t('دسترسی IP برای UDP/TCP/DoT', 'Source IP access for UDP/TCP/DoT')}</h3>
                  <p className="text-muted-foreground mb-3 text-xs">
                    {t('حداکثر سه IP هم‌زمان، با اعتبار محدود. یک IP مشترک بین دو حساب قابل تفکیک نیست.', 'Up to three leased IPs. A shared source IP cannot identify two accounts.')}
                  </p>
                  {can('update') && (
                    <form
                      className="mb-3 flex flex-wrap gap-2"
                      onSubmit={e => {
                        e.preventDefault()
                        const form = e.currentTarget
                        const data = new FormData(form)
                        void mutate(async () => {
                          await fetcher(`/api/dns/accounts/${account.id}/leases`, { method: 'POST', body: { ip: data.get('ip'), ttl: Number(data.get('ttl')) } })
                          form.reset()
                        })
                      }}
                    >
                      <Input className="min-w-0 flex-1" name="ip" dir="ltr" required placeholder="203.0.113.10" aria-label="Client IP" />
                      <Input className="w-28" name="ttl" type="number" min={60} max={86400} required defaultValue={3600} aria-label={t('اعتبار بر حسب ثانیه', 'Lease seconds')} />
                      <Button type="submit" variant="outline" disabled={busy}>
                        {t('ثبت IP', 'Lease IP')}
                      </Button>
                    </form>
                  )}
                  {leases.data?.length === 0 && <p className="text-muted-foreground text-sm">{t('IP فعالی ثبت نشده است.', 'No active source IP leases.')}</p>}
                  {leases.data?.map(lease => (
                    <div key={lease.ip} className="flex flex-wrap items-center justify-between gap-2 border-t py-2 text-xs">
                      <code dir="ltr">{lease.ip}</code>
                      <span>{date(lease.expires_at)}</span>
                      {can('update') && (
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={busy}
                          onClick={() => void mutate(() => fetcher(`/api/dns/accounts/${account.id}/leases/${encodeURIComponent(lease.ip)}`, { method: 'DELETE' }))}
                        >
                          {t('لغو', 'Revoke')}
                        </Button>
                      )}
                    </div>
                  ))}
                </div>
                <div>
                  <h3 className="mb-3 font-semibold">{t('تاریخچه دوره‌ها', 'Period history')}</h3>
                  <div className="overflow-x-auto">
                    <table className="w-full text-start text-xs">
                      <thead>
                        <tr>
                          {[t('دوره', 'Period'), t('درخواست', 'Queries'), t('وضعیت', 'Status')].map(label => (
                            <th className="p-2 text-start" key={label}>
                              {label}
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {periods.data?.map(period => (
                          <tr className="border-t" key={period.period}>
                            <td className="p-2">{number(period.period)}</td>
                            <td>{number(period.queries)}</td>
                            <td>{period.ended_at ? t('بسته‌شده', 'Closed') : t('جاری', 'Current')}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <div className="mt-3 flex gap-2">
                    <Button variant="outline" size="sm" disabled={historyOffset === 0} onClick={() => setHistoryOffset(Math.max(0, historyOffset - 10))}>
                      {t('قبلی', 'Previous')}
                    </Button>
                    <Button variant="outline" size="sm" disabled={(periods.data?.length ?? 0) < 10} onClick={() => setHistoryOffset(historyOffset + 10)}>
                      {t('بعدی', 'Next')}
                    </Button>
                  </div>
                </div>
              </div>
            </section>
          )}
        </>
      )}
      {tab === 'policies' && (
        <div className="space-y-4">
          <div className="flex flex-wrap justify-between gap-3">
            <p className="text-muted-foreground text-sm">
              {t('قانون دقیق‌تر اولویت دارد؛ تغییر سیاست برای درخواست‌های تازه اعمال می‌شود.', 'The most specific suffix wins. Changes apply to new requests.')}
            </p>
            {owner && (
              <Button
                onClick={() => {
                  setPolicyModal('new')
                  setRules([])
                }}
              >
                <Plus className="me-2 h-4 w-4" />
                {t('سیاست جدید', 'New policy')}
              </Button>
            )}
          </div>
          <div className="grid gap-4 lg:grid-cols-2">
            {policies.data?.map(policy => (
              <article key={policy.id} className={`${panel} space-y-3`}>
                <div className="flex justify-between gap-2">
                  <h2 className="font-semibold">{policy.name}</h2>
                  <small className="text-muted-foreground">v{policy.revision}</small>
                </div>
                <p className="text-muted-foreground text-sm">
                  {number(policy.rules.length)} {t('قانون دامنه', 'domain rules')}
                </p>
                <div className="flex flex-wrap gap-1.5">
                  {policy.rules.slice(0, 5).map(rule => (
                    <code className="bg-muted rounded-md px-2 py-1 text-xs" key={rule.domain}>
                      {rule.domain}
                    </code>
                  ))}
                </div>
                {owner && (
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        setPolicyModal(policy)
                        setRules(policy.rules.map(rule => ({ ...rule })))
                      }}
                    >
                      {t('ویرایش سیاست', 'Edit policy')}
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() =>
                        setConfirm({
                          title: t('این سیاست حذف شود؟ سیاست‌های متصل به حساب قابل حذف نیستند.', 'Delete this policy? Assigned policies cannot be deleted.'),
                          action: () => fetcher(`/api/dns/policies/${policy.id}`, { method: 'DELETE' }),
                        })
                      }
                    >
                      {t('حذف', 'Delete')}
                    </Button>
                  </div>
                )}
              </article>
            ))}
          </div>
        </div>
      )}
      {tab === 'catalog' && (
        <section className="space-y-4">
          <div className={panel}>
            <h2 className="font-semibold">{t('کاتالوگ دامنه‌های سرویس‌ها', 'Service domain catalog')}</h2>
            <p className="text-muted-foreground mt-2 text-sm">
              {t(
                'دامنه‌های منتخب را برای بازنویسی DNS به آدرس relay خود انتخاب کنید. انتخاب دامنه به‌تنهایی relay نصب نمی‌کند و عبور ترافیک یا بازی را تأیید نمی‌کند.',
                'Choose service domains to rewrite DNS to your relay addresses. Selecting domains does not install a relay or verify application traffic.',
              )}
            </p>
            {catalog.data && (
              <p className="text-primary mt-3 text-sm">
                {number(catalog.data.counts.services)} {t('سرویس', 'services')} · {number(catalog.data.counts.domains)} {t('دامنه', 'domains')}
              </p>
            )}
          </div>
          <Input aria-label={t('جستجوی سرویس', 'Search services')} placeholder={t('جستجوی سرویس…', 'Search services…')} value={catalogSearch} onChange={e => setCatalogSearch(e.target.value)} />
          <div className="grid max-h-96 gap-3 overflow-y-auto sm:grid-cols-2 xl:grid-cols-3">
            {catalog.data?.services
              .filter(service => `${service.key} ${service.name} ${service.label}`.toLowerCase().includes(catalogSearch.toLowerCase()))
              .map(service => (
                <article className={panel} key={service.key}>
                  <h3 className="mb-2 font-medium">{service.name || service.label || service.key}</h3>
                  {service.groups.map(group => {
                    const key = `${service.key}/${group.key}`
                    return (
                      <label key={key} className="my-2 flex items-center gap-2 text-sm">
                        <input
                          type="checkbox"
                          disabled={!owner}
                          checked={catalogSelected.includes(key)}
                          onChange={e => setCatalogSelected(e.target.checked ? [...catalogSelected, key] : catalogSelected.filter(item => item !== key))}
                        />
                        <span>
                          {group.name || (fa ? group.label : undefined) || group.key} <small className="text-muted-foreground">({number(group.domains.length)})</small>
                        </span>
                      </label>
                    )
                  })}
                </article>
              ))}
          </div>
          {owner && (
            <form
              className={`${panel} space-y-3`}
              onSubmit={e => {
                e.preventDefault()
                const form = e.currentTarget
                const data = new FormData(form)
                void mutate(async () => {
                  await fetcher('/api/dns/policies/from-catalog', {
                    method: 'POST',
                    body: {
                      name: data.get('name'),
                      groups: catalogSelected.map(value => {
                        const [service, group] = value.split('/')
                        return { service, group }
                      }),
                      relay_addresses: String(data.get('addresses'))
                        .split(/[\s,]+/)
                        .filter(Boolean),
                    },
                  })
                  form.reset()
                  setCatalogSelected([])
                  setTab('policies')
                })
              }}
            >
              <h3 className="font-medium">{t('ساخت سیاست از انتخاب‌ها', 'Create a policy from selected groups')}</h3>
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="space-y-1 text-sm">
                  <span>{t('نام سیاست', 'Policy name')}</span>
                  <Input required name="name" maxLength={80} />
                </label>
                <label className="space-y-1 text-sm">
                  <span>{t('IPهای relay خارجی شما', 'Your external relay IP addresses')}</span>
                  <Input required name="addresses" dir="ltr" placeholder="203.0.113.10, 2001:db8::10" />
                </label>
              </div>
              <Button disabled={busy || !catalogSelected.length} type="submit">
                {t('ساخت سیاست', 'Create policy')} ({number(catalogSelected.length)})
              </Button>
            </form>
          )}
        </section>
      )}
      {tab === 'audit' && owner && (
        <section className={panel}>
          <h2 className="mb-4 font-semibold">{t('رویدادهای مدیریتی DNS', 'DNS administrative audit')}</h2>
          <p className="text-muted-foreground mb-4 text-xs">
            {t('نام دامنه‌های درخواست‌شده و اعتبارنامه‌ها در این گزارش ذخیره نمی‌شوند.', 'Queried domain names and credentials are not stored in this log.')}
          </p>
          {audit.data?.map(row => (
            <div key={row.id} className="flex flex-wrap justify-between gap-3 border-t py-3 text-sm">
              <code>{row.action}</code>
              <span>{row.actor}</span>
              <span>#{row.target_id}</span>
              <time className="text-muted-foreground text-xs">{date(row.created_at)}</time>
            </div>
          ))}
          <div className="mt-3 flex gap-2">
            <Button variant="outline" disabled={auditOffset === 0} onClick={() => setAuditOffset(Math.max(0, auditOffset - 30))}>
              {t('قبلی', 'Previous')}
            </Button>
            <Button variant="outline" disabled={(audit.data?.length ?? 0) < 30} onClick={() => setAuditOffset(auditOffset + 30)}>
              {t('بعدی', 'Next')}
            </Button>
          </div>
        </section>
      )}
      <Dialog
        open={accountModal !== null}
        onOpenChange={open => {
          if (!open && !busy) setAccountModal(null)
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{accountModal === 'new' ? t('حساب DNS جدید', 'New DNS account') : t('ویرایش حساب DNS', 'Edit DNS account')}</DialogTitle>
            <DialogDescription>{t('دسترسی DNS به یک کاربر موجود پاسارگارد متصل می‌شود.', 'DNS access is attached to an existing PasarGuard user.')}</DialogDescription>
          </DialogHeader>
          {error && (
            <p role="alert" className="text-destructive text-sm">
              {error}
            </p>
          )}
          {accountModal && (
            <form
              key={accountModal === 'new' ? 'new' : accountModal.id}
              onSubmit={e => {
                e.preventDefault()
                const data = new FormData(e.currentTarget)
                const id = accountModal === 'new' ? null : accountModal.id
                void mutate(async () => {
                  await fetcher(id ? `/api/dns/accounts/${id}` : '/api/dns/accounts', {
                    method: id ? 'PUT' : 'POST',
                    body: {
                      ...(id ? {} : { user_id: Number(data.get('user_id')) }),
                      policy_id: Number(data.get('policy_id')),
                      query_limit: Number(data.get('query_limit')),
                      qps: Number(data.get('qps')),
                      enabled: data.get('enabled') === 'on',
                    },
                  })
                  setAccountModal(null)
                })
              }}
              className="space-y-4"
            >
              <fieldset disabled={busy} className="space-y-3">
                {accountModal === 'new' && (
                  <>
                    {hasPermission(admin, 'users', 'read_simple') ? (
                      <label className="block space-y-1 text-sm">
                        <span>{t('کاربر پاسارگارد', 'PasarGuard user')}</span>
                        <Input
                          aria-label={t('جستجوی کاربر موجود', 'Search existing users')}
                          placeholder={t('جستجوی کاربر…', 'Find user…')}
                          value={userSearch}
                          onChange={e => setUserSearch(e.target.value)}
                        />
                        <select name="user_id" required className={selectClass} defaultValue="">
                          <option value="">{t('انتخاب کاربر', 'Select user')}</option>
                          {users.data?.users.map(user => (
                            <option key={user.id} value={user.id}>
                              {user.username}
                            </option>
                          ))}
                        </select>
                      </label>
                    ) : (
                      <label className="block text-sm">
                        {t('شناسه کاربر پاسارگارد', 'PasarGuard user ID')}
                        <Input name="user_id" type="number" required min={1} />
                      </label>
                    )}
                  </>
                )}
                <label className="block space-y-1 text-sm">
                  <span>{t('سیاست دامنه', 'Domain policy')}</span>
                  <select name="policy_id" className={selectClass} required defaultValue={accountModal === 'new' ? '' : accountModal.policy_id}>
                    <option value="">{t('انتخاب سیاست', 'Select policy')}</option>
                    {policies.data?.map(p => (
                      <option key={p.id} value={p.id}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                </label>
                <div className="grid grid-cols-2 gap-3">
                  <label className="text-sm">
                    {t('سقف درخواست؛ صفر نامحدود', 'Query limit; zero is unlimited')}
                    <Input name="query_limit" type="number" min={0} max={9000000000000000} required defaultValue={accountModal === 'new' ? 0 : accountModal.query_limit} />
                  </label>
                  <label className="text-sm">
                    {t('حداکثر درخواست در ثانیه', 'Queries per second')}
                    <Input name="qps" type="number" min={1} max={100} required defaultValue={accountModal === 'new' ? 20 : accountModal.qps} />
                  </label>
                </div>
                <label className="flex items-center gap-2 text-sm">
                  <input name="enabled" type="checkbox" defaultChecked={accountModal === 'new' || accountModal.enabled} />
                  {t('دسترسی DNS فعال باشد', 'Enable DNS access')}
                </label>
              </fieldset>
              <DialogFooter>
                <Button type="submit" disabled={busy}>
                  {t('ذخیره حساب', 'Save account')}
                </Button>
              </DialogFooter>
            </form>
          )}
        </DialogContent>
      </Dialog>
      <Dialog
        open={policyModal !== null}
        onOpenChange={open => {
          if (!open && !busy) setPolicyModal(null)
        }}
      >
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{t('ویرایشگر سیاست دامنه', 'Domain policy editor')}</DialogTitle>
            <DialogDescription>
              {t('بدون قانون، درخواست‌ها به resolver محلی می‌روند. دامنه زیرشاخه‌ها را هم شامل می‌شود.', 'Unmatched requests use the local resolver. Suffix rules include subdomains.')}
            </DialogDescription>
          </DialogHeader>
          {error && (
            <p role="alert" className="text-destructive text-sm">
              {error}
            </p>
          )}
          {policyModal && (
            <form
              className="space-y-4"
              onSubmit={e => {
                e.preventDefault()
                const name = new FormData(e.currentTarget).get('name')
                const editing = policyModal !== 'new'
                void mutate(async () => {
                  await fetcher(editing ? `/api/dns/policies/${policyModal.id}` : '/api/dns/policies', {
                    method: editing ? 'PUT' : 'POST',
                    body: { name, rules, ...(editing ? { expected_revision: policyModal.revision } : {}) },
                  })
                  setPolicyModal(null)
                })
              }}
            >
              <fieldset disabled={busy} className="space-y-3">
                <label className="block space-y-1 text-sm">
                  <span>{t('نام سیاست', 'Policy name')}</span>
                  <Input key={policyModal === 'new' ? 'new' : policyModal.id} name="name" required maxLength={80} defaultValue={policyModal === 'new' ? '' : policyModal.name} />
                </label>
                {rules.map((rule, i) => (
                  <div className="rounded-xl border p-3" key={i}>
                    <div className="grid gap-2 sm:grid-cols-[1fr_140px_auto]">
                      <Input
                        dir="ltr"
                        aria-label={`${t('دامنه', 'Domain')} ${i + 1}`}
                        required
                        value={rule.domain}
                        placeholder="example.com"
                        onChange={e => setRules(rules.map((row, n) => (n === i ? { ...row, domain: e.target.value } : row)))}
                      />
                      <select
                        aria-label={`${t('عملکرد قانون', 'Rule action')} ${i + 1}`}
                        className={selectClass}
                        value={rule.action}
                        onChange={e => setRules(rules.map((row, n) => (n === i ? { ...row, action: e.target.value as Rule['action'], addresses: [] } : row)))}
                      >
                        <option value="allow">{t('عبور عادی', 'Allow')}</option>
                        <option value="block">{t('مسدودسازی', 'Block')}</option>
                        <option value="rewrite">{t('بازنویسی IP', 'Rewrite IP')}</option>
                      </select>
                      <Button type="button" variant="ghost" aria-label={t('حذف قانون', 'Remove rule')} onClick={() => setRules(rules.filter((_, n) => n !== i))}>
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </div>
                    {rule.action === 'rewrite' && (
                      <Input
                        className="mt-2"
                        dir="ltr"
                        required
                        aria-label={t('آدرس‌های بازنویسی', 'Rewrite addresses')}
                        placeholder="203.0.113.10, 2001:db8::10"
                        value={rule.addresses.join(', ')}
                        onChange={e => setRules(rules.map((row, n) => (n === i ? { ...row, addresses: e.target.value.split(',').map(value => value.trim()) } : row)))}
                      />
                    )}
                  </div>
                ))}
                <Button type="button" variant="outline" disabled={rules.length >= 2000} onClick={() => setRules([...rules, { domain: '', action: 'block', addresses: [] }])}>
                  <Plus className="me-2 h-4 w-4" />
                  {t('افزودن قانون', 'Add rule')}
                </Button>
              </fieldset>
              <DialogFooter>
                <Button type="submit" disabled={busy}>
                  {t('ذخیره سیاست', 'Save policy')}
                </Button>
              </DialogFooter>
            </form>
          )}
        </DialogContent>
      </Dialog>
      <Dialog
        open={confirm !== null}
        onOpenChange={open => {
          if (!open && !busy) setConfirm(null)
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('تأیید تغییر', 'Confirm change')}</DialogTitle>
            <DialogDescription>{confirm?.title}</DialogDescription>
          </DialogHeader>
          {error && (
            <p role="alert" className="text-destructive text-sm">
              {error}
            </p>
          )}
          <DialogFooter>
            <Button variant="outline" disabled={busy} onClick={() => setConfirm(null)}>
              {t('انصراف', 'Cancel')}
            </Button>
            <Button
              disabled={busy}
              onClick={() => {
                const action = confirm?.action
                if (action)
                  void mutate(async () => {
                    await action()
                    setConfirm(null)
                  })
              }}
            >
              {t('تأیید و اجرا', 'Confirm')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
