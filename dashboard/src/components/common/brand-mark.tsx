import { cn } from '@/lib/utils'

// Khatam: the eight-point star of Persian tilework, with a saffron node at its center.
const STAR = 'M24 3L30.03 9.45L38.85 9.15L38.55 17.97L45 24L38.55 30.03L38.85 38.85L30.03 38.55L24 45L17.97 38.55L9.15 38.85L9.45 30.03L3 24L9.45 17.97L9.15 9.15L17.97 9.45Z'

export function BrandMark({ className, title }: { className?: string; title?: string }) {
  return (
    <svg viewBox="0 0 48 48" role={title ? 'img' : undefined} aria-label={title} aria-hidden={title ? undefined : true} className={cn('shrink-0', className)}>
      <path d={STAR} fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinejoin="round" />
      <circle cx="24" cy="24" r="8.2" fill="none" stroke="currentColor" strokeWidth="1.8" opacity="0.55" />
      <circle cx="24" cy="24" r="3.6" fill="hsl(var(--brand-saffron))" />
    </svg>
  )
}
