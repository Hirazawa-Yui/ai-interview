import request from './request'
import { createSseParser } from '../utils/sse'

/**
 * RAG 多轮会话 + SSE 流式问答（T8 起从 KnowledgePage 裸 fetch 收敛至此模块）
 * 会话 CRUD 走 axios 拦截器（自动解包 Result）；流式必须裸 fetch（拦截器假设 Result JSON，axios 会缓冲整个流）
 */
export const ragChatApi = {
  /** 创建会话，返回 { id } */
  createSession(kbIds, title) {
    return request.post('/rag-chat/sessions', { kbIds, title }).then(r => r.data)
  },

  /** 历史会话列表 */
  listSessions() {
    return request.get('/rag-chat/sessions').then(r => r.data)
  },

  /** 会话详情（含 messages 数组） */
  sessionDetail(id) {
    return request.get(`/rag-chat/sessions/${id}`).then(r => r.data)
  },

  /** 删除会话 */
  deleteSession(id) {
    return request.delete(`/rag-chat/sessions/${id}`).then(r => r.data)
  },

  /**
   * SSE 流式问答。
   * @param {Object} p
   * @param {string|number} [p.sessionId] 有值 → /rag-chat/sessions/{id}/messages/stream（多轮）；无值 → /knowledge/query/stream（单次）
   * @param {number[]} p.kbIds            无 sessionId 时必填
   * @param {string} p.question           问题文本
   * @param {(chunk: string) => void} p.onData  每个 data: 行的文本增量
   * @param {AbortSignal} [p.signal]      中断（abort 时 reject AbortError，由调用方静默处理）
   * @returns {Promise<void>} 流正常读完 resolve；HTTP 非 2xx / 读中断 / abort 时 reject(Error)
   */
  streamChat({ sessionId, kbIds, question, onData, signal }) {
    const endpoint = sessionId
      ? `/api/rag-chat/sessions/${sessionId}/messages/stream`
      : '/api/knowledge/query/stream'
    const body = sessionId
      ? JSON.stringify({ question })
      : JSON.stringify({ knowledgeBaseIds: kbIds, question })

    return fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
      signal
    }).then(async (resp) => {
      if (!resp.ok) {
        // 非 2xx：后端错误页 JSON / 代理 502 等，取业务消息兜底
        let msg = ''
        try { const b = await resp.json(); msg = b.message || b.error || '' } catch { /* 非 JSON */ }
        throw new Error(msg || `服务异常(HTTP ${resp.status})`)
      }
      if (!resp.body) throw new Error('无法读取响应流')

      // T20：按 SSE 规范解析（同一事件的多个 data: 行要用 \n 拼接后再派发，
      // 否则分片内换行会丢失 → markdown 渲染成一整段）
      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      const parser = createSseParser(onData)
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        parser.push(decoder.decode(value, { stream: true }))
      }
      parser.push(decoder.decode()) // 冲刷解码器里可能残留的多字节字符
      parser.end()
    })
  }
}
