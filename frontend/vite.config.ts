import { defineConfig } from 'vite'
import type { ProxyOptions } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const gatewayUrl = process.env.GATEWAY_URL || 'http://localhost:8080'
const gatewayProxy: ProxyOptions = {
  target: gatewayUrl,
  // UI and API share paths: document refreshes must load the SPA, not the API.
  bypass: (req) => req.headers.accept?.includes('text/html') ? '/index.html' : undefined,
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 3000,
    proxy: {
      ...Object.fromEntries(['/organizations', '/question-banks', '/question-sets', '/organization-contributions', '/organization-questions', '/public-questions', '/public-contributions', '/auth', '/questions', '/subjects', '/categories', '/exams', '/sessions', '/grades', '/submissions', '/analytics', '/notifications'].map(path => [path, gatewayProxy])),
      '/ws': { target: gatewayUrl, ws: true },
    },
  },
})
