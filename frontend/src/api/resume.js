import request from './request'

export const resumeApi = {
  upload(file) {
    const formData = new FormData()
    formData.append('file', file)
    return request.post('/resumes/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000
    }).then(res => res.data)
  },

  list() {
    return request.get('/resumes').then(res => res.data)
  },

  detail(id) {
    return request.get(`/resumes/${id}/detail`).then(res => res.data)
  },

  delete(id) {
    return request.delete(`/resumes/${id}`).then(res => res.data)
  },

  reanalyze(id) {
    return request.post(`/resumes/${id}/reanalyze`).then(res => res.data)
  }
}
