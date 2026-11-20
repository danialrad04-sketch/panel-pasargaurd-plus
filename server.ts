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
