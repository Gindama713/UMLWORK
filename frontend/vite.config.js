import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: process.env.PARKING_GATEWAY_URL || 'http://127.0.0.1:18080',
        changeOrigin: true,
      },
    },
  },
})
