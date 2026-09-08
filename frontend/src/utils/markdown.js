import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * Markdown → 净化后 HTML（AI 输出渲染 v-html 前必须经此消毒，禁止直接 marked.parse）
 * DOMPurify 默认配置：剥 style/事件属性/script，保留常规元素与表格/代码块（.markdown-body 样式全为元素选择器，无需放宽）
 */
export function renderMarkdown(text) {
  if (!text) return ''
  const raw = marked.parse(text, { gfm: true, async: false })
  return typeof raw === 'string' ? DOMPurify.sanitize(raw) : text
}
