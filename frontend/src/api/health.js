import request from './request'

export const healthApi = {
  /** 系统健康检查：LLM / Embedding / OSS 三路连通性；force=true 绕过后端 30s 缓存（D2） */
  check(force = false) {
    return request.get('/health' + (force ? '?force=1' : '')).then(r => r.data)
  }
}
