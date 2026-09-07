import request, { uploadRequest } from './request'

/**
 * 文件分片上传 API
 * 所有方法返回解包后的 data（拦截器已将 Result<T> 解包为 response.data）
 */
export const fileApi = {
  /**
   * 检查上传状态（秒传 / 断点续传 / 新任务）
   */
  async check(md5, fileName, fileSize, totalChunks) {
    const res = await request.post('/file/check', { md5, fileName, fileSize, totalChunks })
    return res.data  // { uploadId, totalChunks, uploadedChunks, completed, url }
  },

  /**
   * 上传单个分片（multipart/form-data）
   */
  uploadChunk(md5, chunkIndex, chunkMd5, chunkBlob) {
    const formData = new FormData()
    formData.append('md5', md5)
    formData.append('chunkIndex', chunkIndex)
    formData.append('chunkMd5', chunkMd5)
    formData.append('file', chunkBlob)
    return uploadRequest({
      method: 'POST',
      url: '/file/chunk',
      data: formData,
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },

  /**
   * 合并所有分片
   */
  async merge(md5, fileName) {
    const res = await request.post('/file/merge', { md5, fileName })
    return res.data  // 返回 URL 字符串
  },

  /**
   * 查询上传进度
   */
  async preview(md5) {
    const res = await request.get(`/file/preview/${md5}`)
    return res.data  // { totalChunks, uploadedChunks }
  }
}
