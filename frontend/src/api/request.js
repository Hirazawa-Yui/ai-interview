import axios from 'axios'
import { ElMessage } from 'element-plus'

// 请求超时（毫秒），默认值即历史值。
// **断点单步调试**时在 frontend/.env.local 里调大即可（该文件被 .gitignore 的 *.local 覆盖，不会进仓库）：
//   VITE_API_TIMEOUT_MS=1800000
//   VITE_UPLOAD_TIMEOUT_MS=1800000
export const API_TIMEOUT = Number(import.meta.env.VITE_API_TIMEOUT_MS) || 60000
export const UPLOAD_TIMEOUT = Number(import.meta.env.VITE_UPLOAD_TIMEOUT_MS) || 300000

const request = axios.create({
  baseURL: '/api',
  timeout: API_TIMEOUT
})

// 响应拦截：解包 Result<T> { code, message, data }
request.interceptors.response.use(
  (response) => {
    const result = response.data
    if (result && typeof result.code === 'number') {
      if (result.code === 200) {
        response.data = result.data
      } else {
        ElMessage.error(result.message || '请求失败')
        return Promise.reject(new Error(result.message))
      }
    }
    return response
  },
  (error) => {
    ElMessage.error(error.message || '网络异常')
    return Promise.reject(error)
  }
)

export default request

// 分片上传专用（不拦截 Result 结构，UploadPart 不返回标准 Result）
export function uploadRequest(config) {
  return axios({
    ...config,
    baseURL: '/api',
    timeout: UPLOAD_TIMEOUT
  })
}
