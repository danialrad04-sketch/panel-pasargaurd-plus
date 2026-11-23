import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import express from 'express'
import { createServer as createViteServer } from 'vite'
import { authRouter } from './server/routes/auth'
import { panelRouter } from './server/routes/panel'
import { plusRouter } from './server/routes/plus'

const PORT = Number(process.env.PORT || 3000)
const IS_PROD = process.env.NODE_ENV === 'production'

async function startServer() {
  const app = express()

  app.use(express.json({ limit: '10mb' }))
  app.use(express.urlencoded({ extended: true, limit: '10mb' }))

  // Health & OpenAPI metadata
  app.get('/health', (_req, res) => {
    res.json({ status: 'ok', service: 'pasarguard-plus', version: '5.6.0-plus' })
  })

  app.get('/openapi.json', (_req, res) => {
    res.json({ openapi: '3.1.0', info: { title: 'PasarGuard Plus API', version: '5.6.0-plus' } })
  })

  // Built Android APKs: catalog, 8 MB chunked downloader (bypasses Cloud Run 32 MB cap), direct downloads & landing page
  const downloadsDir = path.resolve(process.cwd(), 'public-downloads')
  const APK_ENTRIES = [
    {
      id: 'arm64',
      filename: 'PasarGuardVPN-v1.3.0-build4-arm64.apk',
      titleFa: 'نسخه پیشنهادی گوشی‌های جدید (arm64-v8a)',
      descFa: 'کم‌حجم (زیر ۲۶ مگابایت) • مناسب ۹۹٪ گوشی‌های اندروید • Xray-core v26 + داشبورد اشتراک، خرید پلن، کارت‌به‌کارت و سفارش‌ها (Stage 2B) + بهینه‌سازی R8',
      recommended: true,
    },
    {
      id: 'armeabi-v7a',
      filename: 'PasarGuardVPN-v1.3.0-build4-armeabi-v7a.apk',
      titleFa: 'نسخه گوشی‌های قدیمی‌تر ۳۲ بیتی (armeabi-v7a)',
      descFa: 'کم‌حجم (زیر ۲۶ مگابایت) • مناسب گوشی‌های اندروید ۳۲ بیتی • Xray-core v26 + امکانات کامل Stage 2B',
      recommended: false,
    },
    {
      id: 'universal',
      filename: 'PasarGuardVPN-v1.3.0-build4-universal.apk',
      titleFa: 'نسخه کامل Universal (همه معماری‌ها)',
      descFa: 'شامل arm64-v8a + armeabi-v7a + x86_64 • بهینه‌سازی R8 با امضای نصب مستقیم',
      recommended: false,
    },
  ]

  const resolveApkByName = (filename: string): string | null => {
    const safe = path.basename(filename)
    if (!APK_ENTRIES.some(e => e.filename === safe)) return null
    const full = path.join(downloadsDir, safe)
    return fs.existsSync(full) ? full : null
  }

  const getApkCatalog = () =>
    APK_ENTRIES.map(entry => {
      const full = path.join(downloadsDir, entry.filename)
      const exists = fs.existsSync(full)
      const sizeBytes = exists ? fs.statSync(full).size : 0
      return {
        ...entry,
        exists,
        sizeBytes,
        sizeMb: (sizeBytes / (1024 * 1024)).toFixed(2),
        directSafe: sizeBytes > 0 && sizeBytes < 31 * 1024 * 1024,
      }
    }).filter(e => e.exists)

  app.get('/api/apk-catalog', (_req, res) => {
    res.json({ version: '1.3.0', build: 4, files: getApkCatalog() })
  })

  // Chunked byte-slice endpoint (max 8 MB per chunk -> 100% immune to Cloud Run 32 MB response cap)
  app.get('/api/apk-chunk/:filename', (req, res) => {
    const full = resolveApkByName(req.params.filename)
    if (!full) {
      res.status(404).json({ detail: 'APK file not found.' })
      return
    }
    const stat = fs.statSync(full)
    const total = stat.size
    const start = Math.max(0, parseInt(String(req.query.start ?? '0'), 10) || 0)
    const maxChunk = 8 * 1024 * 1024
    const requestedEnd = parseInt(String(req.query.end ?? String(start + maxChunk - 1)), 10)
    const end = Math.min(total - 1, start + maxChunk - 1, Number.isFinite(requestedEnd) ? requestedEnd : total - 1)

    if (start >= total || start > end) {
      res.status(416).json({ detail: 'Range not satisfiable', total })
      return
    }

    res.status(200)
    res.setHeader('Content-Type', 'application/octet-stream')
    res.setHeader('Content-Length', String(end - start + 1))
    res.setHeader('X-Total-Size', String(total))
    res.setHeader('Cache-Control', 'no-store')
    fs.createReadStream(full, { start, end }).pipe(res)
  })

  const streamApkDirect = (full: string, downloadName: string, req: express.Request, res: express.Response) => {
    const stat = fs.statSync(full)
    const total = stat.size
    const range = req.headers.range

    res.setHeader('Content-Type', 'application/vnd.android.package-archive')
    res.setHeader('Content-Disposition', `attachment; filename="${downloadName}"`)
    res.setHeader('Accept-Ranges', 'bytes')

    if (range) {
      const match = /bytes=(\d+)-(\d*)/.exec(range)
      if (match) {
        const start = parseInt(match[1], 10)
        const end = match[2] ? Math.min(total - 1, parseInt(match[2], 10)) : total - 1
        if (start >= total || start > end) {
          res.status(416).setHeader('Content-Range', `bytes */${total}`).end()
          return
        }
        res.status(206)
        res.setHeader('Content-Range', `bytes ${start}-${end}/${total}`)
        res.setHeader('Content-Length', String(end - start + 1))
        fs.createReadStream(full, { start, end }).pipe(res)
        return
      }
    }

    res.setHeader('Content-Length', String(total))
    fs.createReadStream(full).pipe(res)
  }

  app.get('/downloads/:filename', (req, res) => {
    const requested = req.params.filename
    if (requested === 'PasarGuardVPN-debug.apk') {
      const preferred =
        resolveApkByName('PasarGuardVPN-v1.3.0-build4-arm64.apk') ||
        resolveApkByName('PasarGuardVPN-v1.3.0-build4-armeabi-v7a.apk') ||
        resolveApkByName('PasarGuardVPN-v1.3.0-build4-universal.apk')
      if (!preferred) {
        res.status(404).json({ detail: 'APK has not been built yet.' })
        return
      }
      streamApkDirect(preferred, path.basename(preferred), req, res)
      return
    }

    const full = resolveApkByName(requested)
    if (!full) {
      res.status(404).json({ detail: 'APK file not found.' })
      return
    }
    streamApkDirect(full, path.basename(full), req, res)
  })

  app.get('/android-download', (req, res) => {
    const catalog = getApkCatalog()
    const proto = (req.headers['x-forwarded-proto'] as string) || req.protocol
    const origin = `${proto}://${req.get('host')}`
    const demoSubUrl = `${origin}/sub/sub_alex_pro_01`
    const catalogJson = JSON.stringify(catalog)

    res.type('text/html').send(`<!doctype html>
<html lang="fa" dir="rtl">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1" />
  <title>دانلود اپلیکیشن اندروید PasarGuard VPN v1.3.0</title>
  <style>
    body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:radial-gradient(circle at top,#111c3a 0%,#07090E 65%);color:#F8FAFC;font-family:system-ui,-apple-system,sans-serif;padding:20px;box-sizing:border-box}
    .card{max-width:520px;width:100%;background:rgba(22,28,45,.86);border:1px solid rgba(255,255,255,.14);border-radius:28px;padding:26px;box-shadow:0 24px 60px rgba(0,0,0,.55)}
    h1{margin:0 0 6px;font-size:21px}
    .subtitle{margin:0 0 18px;color:#94A3B8;font-size:13.5px;line-height:1.7}
    .item{background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.1);border-radius:18px;padding:14px 16px;margin-bottom:12px}
    .item.rec{border-color:rgba(6,182,212,.5);background:rgba(6,182,212,.08)}
    .item-head{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:4px}
    .item-title{font-weight:700;font-size:14.5px}
    .badge{font-size:11px;padding:3px 9px;border-radius:999px;background:rgba(16,185,129,.18);color:#34D399;white-space:nowrap}
    .item-desc{font-size:12.5px;color:#94A3B8;margin-bottom:12px;line-height:1.6}
    .actions{display:flex;gap:8px;flex-wrap:wrap}
    .btn{flex:1;min-width:150px;text-align:center;text-decoration:none;border:0;cursor:pointer;background:linear-gradient(90deg,#1D4ED8,#06B6D4);color:#fff;font-weight:700;font-size:13.5px;padding:11px 14px;border-radius:13px}
    .btn.secondary{background:rgba(255,255,255,.08);color:#E2E8F0;border:1px solid rgba(255,255,255,.14)}
    .btn:disabled{opacity:.6;cursor:wait}
    .progress-wrap{display:none;margin-top:10px}
    .progress-bar-bg{height:8px;border-radius:999px;background:rgba(255,255,255,.1);overflow:hidden}
    .progress-bar{height:100%;width:0%;background:linear-gradient(90deg,#10B981,#06B6D4);transition:width .2s}
    .progress-text{font-size:12px;color:#BAE6FD;margin-top:6px;direction:ltr;text-align:left}
    .sub-box{margin-top:14px;padding-top:14px;border-top:1px solid rgba(255,255,255,.1)}
    .sub{font-family:monospace;font-size:12px;background:rgba(6,182,212,.1);border:1px solid rgba(6,182,212,.3);padding:10px 12px;border-radius:12px;word-break:break-all;direction:ltr;color:#BAE6FD}
  </style>
</head>
<body>
  <div class="card">
    <h1>دانلود PasarGuard VPN (v1.3.0 - Build 4)</h1>
    <p class="subtitle">مرحله ۲B: داشبورد حرفه‌ای اشتراک (کارت پاسپورت، پیش‌بینی مصرف، مدیریت دستگاه‌های HWID و هشدار هوشمند)، صفحه پلن‌ها و مقایسه، فرآیند کامل پرداخت کارت‌به‌کارت با آپلود فیش فشرده‌شده، پیگیری سفارش و فاکتور PNG، همراه با موتور Xray-core v26.</p>
    <div id="list"></div>
    <div class="sub-box">
      <div style="font-size:12.5px;color:#94A3B8;margin-bottom:6px">لینک اشتراک دمو برای تست سریع داخل برنامه:</div>
      <div class="sub">${demoSubUrl}</div>
    </div>
  </div>
  <script>
    const FILES = ${catalogJson};
    const CHUNK_SIZE = 6 * 1024 * 1024;
    const listEl = document.getElementById('list');

    async function downloadChunked(file, btn, progWrap, progBar, progText) {
      btn.disabled = true;
      progWrap.style.display = 'block';
      try {
        const chunks = [];
        let offset = 0;
        const total = file.sizeBytes;
        while (offset < total) {
          const end = Math.min(total - 1, offset + CHUNK_SIZE - 1);
          const pct = Math.round((offset / total) * 100);
          progBar.style.width = pct + '%';
          progText.textContent = 'Downloading ' + (offset / 1048576).toFixed(1) + ' / ' + file.sizeMb + ' MB (' + pct + '%)...';
          const res = await fetch('/api/apk-chunk/' + encodeURIComponent(file.filename) + '?start=' + offset + '&end=' + end, { credentials: 'include' });
          if (!res.ok) throw new Error('HTTP ' + res.status);
          const buf = await res.arrayBuffer();
          chunks.push(buf);
          offset += buf.byteLength;
        }
        progBar.style.width = '100%';
        progText.textContent = 'Ready! Saving ' + file.filename + ' (' + file.sizeMb + ' MB)';
        const blob = new Blob(chunks, { type: 'application/vnd.android.package-archive' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = file.filename;
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 60000);
      } catch (err) {
        progText.textContent = 'Error: ' + err.message;
        progText.style.color = '#FDA4AF';
      } finally {
        btn.disabled = false;
      }
    }

    if (!FILES.length) {
      listEl.innerHTML = '<p style="color:#F43F5E">هنوز فایل APK بیلد نشده است.</p>';
    } else {
      FILES.forEach((f) => {
        const card = document.createElement('div');
        card.className = 'item' + (f.recommended ? ' rec' : '');
        card.innerHTML =
          '<div class="item-head"><span class="item-title">' + f.titleFa + '</span><span class="badge">' + f.sizeMb + ' MB</span></div>' +
          '<div class="item-desc">' + f.descFa + '</div>' +
          '<div class="actions">' +
            '<button class="btn js-dl">دانلود ایمن (' + f.sizeMb + ' MB)</button>' +
            (f.directSafe ? '<a class="btn secondary" href="/downloads/' + encodeURIComponent(f.filename) + '" download>لینک مستقیم</a>' : '') +
          '</div>' +
          '<div class="progress-wrap"><div class="progress-bar-bg"><div class="progress-bar"></div></div><div class="progress-text"></div></div>';
        const btn = card.querySelector('.js-dl');
        const pw = card.querySelector('.progress-wrap');
        const pb = card.querySelector('.progress-bar');
        const pt = card.querySelector('.progress-text');
        btn.addEventListener('click', () => downloadChunked(f, btn, pw, pb, pt));
        listEl.appendChild(card);
      });
    }
  </script>
</body>
</html>`)
  })

  // API & public subscription routers
  app.use(authRouter)
  app.use(panelRouter)
  app.use(plusRouter)

  // Unhandled /api/* endpoints return JSON 404 rather than SPA HTML
  app.all('/api/*', (req, res) => {
    res.status(404).json({ detail: `Endpoint not found: ${req.method} ${req.path}` })
  })

  if (!IS_PROD) {
    const vite = await createViteServer({
      configFile: path.resolve(process.cwd(), 'dashboard', 'vite.config.ts'),
      server: {
        middlewareMode: true,
        hmr: false,
        ws: false,
      },
      appType: 'spa',
    })
    app.use(vite.middlewares)
  } else {
    const buildDir = path.resolve(process.cwd(), 'dashboard', 'build')
    app.use(express.static(buildDir))
    app.get('*', (_req, res) => {
      const indexHtml = path.join(buildDir, 'index.html')
      if (fs.existsSync(indexHtml)) {
        res.sendFile(indexHtml)
      } else {
        res.status(404).send('Build output not found. Run npm run build first.')
      }
    })
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`PasarGuard Plus listening on http://0.0.0.0:${PORT} (${IS_PROD ? 'production' : 'development'})`)
  })
}

startServer().catch(err => {
  console.error('Failed to start PasarGuard Plus server:', err)
  process.exit(1)
})
