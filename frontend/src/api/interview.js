import request from './request'

export const interviewApi = {
  /** 创建面试会话 + AI出题 */
  create(resumeId, jdText, direction, questionCount = 8) {
    return request.post('/interviews/sessions', {
      resumeId, jdText, direction, questionCount
    }).then(r => r.data)
  },

  /** 面试历史列表 */
  list() {
    return request.get('/interviews/sessions').then(r => r.data)
  },

  /** 会话详情（含Q&A） */
  detail(id) {
    return request.get(`/interviews/sessions/${id}`).then(r => r.data)
  },

  /** 删除会话 */
  delete(id) {
    return request.delete(`/interviews/sessions/${id}`).then(r => r.data)
  },

  /** 提交回答，返回下一题或allDone */
  submitAnswer(sessionId, questionNumber, answerText) {
    return request.post(`/interviews/sessions/${sessionId}/answers`, {
      questionNumber, answerText
    }).then(r => r.data)
  },

  /** 触发异步评估 */
  evaluate(sessionId) {
    return request.post(`/interviews/sessions/${sessionId}/evaluate`).then(r => r.data)
  },

  /** 获取评估结果（轮询） */
  getEvaluation(sessionId) {
    return request.get(`/interviews/sessions/${sessionId}/evaluation`).then(r => r.data)
  }
}
