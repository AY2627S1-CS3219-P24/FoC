import { defineConfig } from 'vite'
import { devtools } from '@tanstack/devtools-vite'
import viteReact from '@vitejs/plugin-react'

const config = defineConfig({
  resolve: { tsconfigPaths: true },
  plugins: [devtools(), viteReact()],
  server: {
    proxy: {
      '/api/auth': 'http://localhost:8080',
      '/api/users': 'http://localhost:8080',
      '/api/suppliers': 'http://localhost:8081',
      '/api/orders': 'http://localhost:8082',
      '/api/credits': 'http://localhost:8083',
    },
  },
})

export default config
