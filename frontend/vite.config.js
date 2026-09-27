import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 转发前摘掉浏览器的 Origin 头（T26）。
        // 浏览器和 Vite 本来就是同源，这个头对后端没有意义；原样转发会让后端 CorsFilter
        // 把「手机用局域网 IP 访问」误判成跨域并回 403 Invalid CORS request —— 且只影响
        // POST/PUT/DELETE（GET 同源不带 Origin，所以看页面一切正常，一上传就 403）。
        // 摘掉后与 IP/端口无关：换热点、换端口都不用再改配置。
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('origin'))
        }
      }
    }
  }
})
