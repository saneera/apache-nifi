import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// In dev, /api is proxied to the Spring Boot backend.
// `npm run dev:mock` (mode "mock") uses the in-browser mock API instead — no backend needed.
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    // Built straight into the Spring Boot jar's static resources.
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true,
  },
})
