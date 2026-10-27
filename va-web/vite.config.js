import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 开发期：vite dev (:5173) 代理 /api 到 va-server (:9000)
// 生产：npm run build → dist 由 va-server 直接托管（见 application.yml static-locations）
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:9000',
    },
  },
});
