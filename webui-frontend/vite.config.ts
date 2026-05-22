import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// React 产物固定输出到 bundled webui/react，Ktor 静态路由以该目录作为运行时入口。
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
