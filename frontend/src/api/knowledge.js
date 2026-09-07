import request from './request'

export const knowledgeApi = {
  /** 文档入库（fileKey来自Phase2合并结果） */
  upload(fileKey, kbName, category) {
    return request.post('/knowledge/upload', { fileKey, kbName, category }).then(r => r.data)
  },

  /** 文档列表 */
  list(vectorStatus, category, sortBy = 'time') {
    return request.get('/knowledge/list', { params: { vectorStatus, category, sortBy } }).then(r => r.data)
  },

  /** 文档详情 */
  detail(id) {
    return request.get(`/knowledge/${id}`).then(r => r.data)
  },

  /** 删除 */
  delete(id) {
    return request.delete(`/knowledge/${id}`).then(r => r.data)
  },

  /** 更新分类 */
  updateCategory(id, category) {
    return request.put(`/knowledge/${id}/category`, { category }).then(r => r.data)
  },

  /** 重新向量化 */
  revectorize(id) {
    return request.post(`/knowledge/${id}/revectorize`).then(r => r.data)
  }
}
