/**
 * 极简 SSE 解析器（T20）
 *
 * 为什么需要它：Spring MVC 的 SSE 编码器会把**一个分片内部的换行拆成同一事件里的多个 `data:` 行**
 * （分片 `"标题\n"` → 线上是 `data:标题\ndata:\n\n`），事件之间再以空行分隔。
 * 逐个 `data:` 行独立派发的写法会把换行丢掉，累积出的 markdown 没有行结构，
 * 渲染时只能得到一个扁平段落（刷新后从库里读回带换行的原文才恢复正常）。
 *
 * 按 SSE 规范解析：同一事件内的多个 `data:` 行用 `\n` 拼接，遇空行（事件结束）整体派发一次。
 * 零依赖，便于单独验证（见 T20 验收）。
 */
export function createSseParser(onEvent) {
  let buffer = ''
  let dataLines = []

  function flush() {
    if (dataLines.length === 0) return
    const payload = dataLines.join('\n')
    dataLines = []
    onEvent(payload)
  }

  function handleLine(rawLine) {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (line === '') {
      flush() // 空行 = 事件结束
      return
    }
    if (line.startsWith(':')) return // 注释/心跳行
    if (!line.startsWith('data:')) return // event:/id:/retry: 一律忽略
    let value = line.slice(5)
    if (value.startsWith(' ')) value = value.slice(1) // 规范：冒号后最多去掉一个空格
    dataLines.push(value)
  }

  return {
    /** 喂入解码后的文本片段（可含半行、可跨多个事件） */
    push(text) {
      buffer += text
      let idx
      while ((idx = buffer.indexOf('\n')) >= 0) {
        handleLine(buffer.slice(0, idx))
        buffer = buffer.slice(idx + 1)
      }
    },
    /** 流结束时调用：冲刷残留的未换行内容与未终结的事件 */
    end() {
      if (buffer !== '') {
        handleLine(buffer)
        buffer = ''
      }
      flush()
    }
  }
}
