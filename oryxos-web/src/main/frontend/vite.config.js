import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// The console is served by Spring Boot from the same JAR under /admin/, so every asset URL must
// start with that prefix. The build goes straight into the web module's static resources.
export default defineConfig({
  base: '/admin/',
  plugins: [vue()],
  build: {
    outDir: fileURLToPath(new URL('../resources/static/admin', import.meta.url)),
    emptyOutDir: true,
    sourcemap: false
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    // `npm run dev` talks to a locally running OryxOS; nothing leaves this machine.
    proxy: { '/api': 'http://127.0.0.1:8080' }
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.js']
  }
})
