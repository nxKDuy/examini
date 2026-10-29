import { defineConfig } from 'vite'
import type { Plugin, ProxyOptions } from 'vite'
import { copyFileSync, createReadStream, existsSync, mkdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const gatewayUrl = process.env.GATEWAY_URL || 'http://localhost:8080'
const gatewayProxy: ProxyOptions = {
  target: gatewayUrl,
  // UI and API share paths: document refreshes must load the SPA, not the API.
  bypass: (req) => req.headers.accept?.includes('text/html') ? '/index.html' : undefined,
}

// Face models for camera proctoring come from the pinned @vladmandic/face-api package (not committed to git):
// served at /models/face in dev and copied into the build output.
const FACE_MODELS = ['tiny_face_detector_model', 'face_landmark_68_tiny_model', 'face_recognition_model']
  .flatMap(m => [`${m}-weights_manifest.json`, `${m}.bin`])
function faceModels(): Plugin {
  const dir = fileURLToPath(new URL('./node_modules/@vladmandic/face-api/model', import.meta.url))
  let outDir = 'dist'
  return {
    name: 'examini-face-models',
    configResolved(c) { outDir = resolve(c.root, c.build.outDir) },
    configureServer(server) {
      server.middlewares.use('/models/face', (req, res, next) => {
        const name = (req.url ?? '').replace(/^\//, '').split('?')[0]
        if (!FACE_MODELS.includes(name) || !existsSync(join(dir, name))) return next()
        res.setHeader('Content-Type', name.endsWith('.json') ? 'application/json' : 'application/octet-stream')
        res.setHeader('Cache-Control', 'public, max-age=86400')
        createReadStream(join(dir, name)).pipe(res)
      })
    },
    closeBundle() {
      const target = join(outDir, 'models/face')
      mkdirSync(target, { recursive: true })
      for (const f of FACE_MODELS) copyFileSync(join(dir, f), join(target, f))
    },
  }
}

export default defineConfig({
  plugins: [react(), tailwindcss(), faceModels()],
  server: {
    port: 3000,
    proxy: {
      ...Object.fromEntries(['/organizations', '/question-banks', '/question-sets', '/organization-contributions', '/organization-questions', '/public-questions', '/public-contributions', '/auth', '/questions', '/question-imports', '/subjects', '/categories', '/exams', '/sessions', '/grades', '/submissions', '/analytics', '/notifications'].map(path => [path, gatewayProxy])),
      '/ws': { target: gatewayUrl, ws: true },
    },
  },
})
