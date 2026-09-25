import { defineConfig } from 'vite'
import { devtools } from '@tanstack/devtools-vite'
import viteReact from '@vitejs/plugin-react'

const config = defineConfig({
  resolve: { tsconfigPaths: true },
  plugins: [devtools(), viteReact()],
  server: {
    proxy: {
      '/auth': 'http://localhost:8080',
      '/users': 'http://localhost:8080',
      '/suppliers': 'http://localhost:8081',
      '/orders': 'http://localhost:8082',
      '/credits': 'http://localhost:8083',
    },
  },
})

export default config
