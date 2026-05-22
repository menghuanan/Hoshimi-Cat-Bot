import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// React 产物先隔离到 bundled webui/react，后续等价迁移完成后再切换主 WebUI shell。
export default defineConfig({
  root: 'src',
  base: './',
  plugins: [react(), tailwindcss()],
  build: {
    outDir: '../../src/main/resources/webui/react',
    emptyOutDir: true,
    assetsDir: 'assets',
    rollupOptions: {
      output: {
        entryFileNames: 'assets/app.js',
        chunkFileNames: 'assets/[name].js',
        assetFileNames: (assetInfo) =>
          assetInfo.names.some((name) => name.endsWith('.css'))
            ? 'assets/app.css'
            : 'assets/[name][extname]',
      },
    },
  },
})
