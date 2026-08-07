import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  server: {
    port: 3000,
    proxy: {
      '/auth': 'http://localhost:8080',
      '/questions': 'http://localhost:8080',
      '/subjects': 'http://localhost:8080',
      '/categories': 'http://localhost:8080',
      '/exams': 'http://localhost:8080',
      '/sessions': 'http://localhost:8080',
      '/analytics': 'http://localhost:8080',
    },
  },
})
