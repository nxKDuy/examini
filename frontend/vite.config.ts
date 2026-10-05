import { defineConfig } from 'vite'
import type { ProxyOptions } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const gatewayProxy: ProxyOptions = {
  target: 'http://localhost:8080',
  // UI and API share paths: document refreshes must load the SPA, not the API.
  bypass: (req) => req.headers.accept?.includes('text/html') ? '/index.html' : undefined,
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 3000,
    proxy: {
      ...Object.fromEntries(['/auth', '/questions', '/subjects', '/categories', '/exams', '/sessions', '/grades', '/submissions', '/analytics'].map(path => [path, gatewayProxy])),
      '/ws': { target: 'http://localhost:8080', ws: true },
    },
  },
})
