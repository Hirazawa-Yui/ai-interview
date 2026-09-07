import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '/api',
  timeout: 60000
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
    timeout: 300000
  })
}
