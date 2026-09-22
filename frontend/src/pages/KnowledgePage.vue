<template>
  <div>
    <h1 style="margin-bottom: 24px">📚 知识库</h1>

    <el-tabs v-model="activeTab" type="border-card" @tab-change="onTabChange">
      <!-- ========== Tab1: 上传文档 ========== -->
      <el-tab-pane label="上传文档" name="upload">
        <ChunkUploader
          accept=".pdf,.doc,.docx,.txt,.md"
          tip-text="支持 PDF、Word、TXT、Markdown 格式，单文件最大 200MB"
          @upload-success="handleFileUploaded"
        />
        <el-card v-if="uploadedFileKey" style="margin-top: 16px">
          <template #header><strong>文档入库</strong></template>
          <el-form :model="kbForm" label-width="120px">
            <el-form-item label="文档名称">
              <el-input v-model="kbForm.kbName" placeholder="如：Java面试题库" />
            </el-form-item>
            <el-form-item label="分类标签">
              <el-input v-model="kbForm.category" placeholder="如：Java" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="doKbUpload" :loading="kbUploading">确认入库</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-tab-pane>

      <!-- ========== Tab2: 文档列表 ========== -->
      <el-tab-pane label="文档列表" name="list">
        <div style="margin-bottom: 12px; display: flex; gap: 8px">
          <el-select v-model="listFilter.vectorStatus" placeholder="状态筛选" clearable style="width: 140px" @change="loadList">
            <el-option label="全部" value="" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="处理中" value="PROCESSING" />
            <el-option label="排队中" value="PENDING" />
            <el-option label="失败" value="FAILED" />
          </el-select>
          <el-button @click="loadList" :loading="listLoading">刷新</el-button>
        </div>
        <el-table :data="docList" stripe v-loading="listLoading" @row-click="showDetail" highlight-current-row>
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="kbName" label="名称" min-width="150" show-overflow-tooltip />
          <el-table-column prop="category" label="分类" width="100" />
          <el-table-column prop="fileSize" label="大小" width="90">
            <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
          </el-table-column>
          <el-table-column prop="vectorStatus" label="向量化" width="90">
            <template #default="{ row }">
              <el-tag :type="statusType(row.vectorStatus)" size="small">{{ statusLabel(row.vectorStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="chunkCount" label="分块数" width="70" />
          <el-table-column prop="questionCount" label="问答" width="60" />
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click.stop="showDetail(row)">详情</el-button>
              <el-button size="small" text type="danger" @click.stop="doDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ========== Tab3: RAG问答 ========== -->
      <el-tab-pane label="RAG问答" name="chat">
        <div style="display: flex; gap: 12px; margin-bottom: 12px; align-items: center">
          <el-select v-model="chatKbIds" placeholder="选择知识库" multiple style="flex: 1">
            <el-option v-for="d in completedDocs" :key="d.id" :label="d.kbName" :value="d.id" />
          </el-select>
          <el-button type="success" @click="createChatSession" :disabled="chatKbIds.length===0">
            {{ currentSessionId ? '会话#' + currentSessionId : '创建会话' }}
          </el-button>
          <el-button v-if="currentSessionId" @click="resetChat">重置</el-button>
        </div>

        <div v-if="chatKbIds.length === 0" style="color: var(--nt-muted); text-align: center; padding: 40px">
          请先选择至少一个已完成向量化的知识库
        </div>

        <div v-else style="border: 1px solid var(--nt-hairline); border-radius: 8px; height: 500px; display: flex; flex-direction: column">
          <div ref="chatBox" style="flex: 1; overflow-y: auto; padding: 16px">
            <div v-for="(msg, i) in chatMessages" :key="i" :style="{ marginBottom: '12px', textAlign: msg.role === 'user' ? 'right' : 'left' }">
              <div :style="{ display: 'inline-block', maxWidth: '80%', padding: '10px 14px', borderRadius: '8px',
                background: msg.role === 'user' ? 'var(--nt-primary)' : 'var(--nt-canvas)',
                border: msg.role === 'user' ? 'none' : '1px solid var(--nt-hairline)',
                color: msg.role === 'user' ? '#fff' : 'var(--nt-charcoal)' }">
                <!-- 用户消息纯文本，AI消息渲染Markdown -->
                <template v-if="msg.role === 'user'">{{ msg.content }}</template>
                <!-- T20：渲染节流后的 HTML（见 scheduleRender），不再每次重渲染都重解析整段 -->
                <div v-else class="markdown-body" v-html="msg.html" />
                <span v-if="msg.streaming" style="color: var(--nt-primary); font-size: 12px">▌</span>
              </div>
            </div>
          </div>
          <div style="border-top: 1px solid var(--nt-hairline); padding: 12px; display: flex; gap: 8px">
            <el-input v-model="chatInput" placeholder="输入问题..." @keyup.enter="doChat" :disabled="chatLoading" />
            <el-button type="primary" @click="doChat" :loading="chatLoading">发送</el-button>
          </div>
        </div>
      </el-tab-pane>

      <!-- ========== Tab4: 历史会话 ========== -->
      <el-tab-pane label="历史会话" name="sessions">
        <el-table :data="sessionList" stripe @row-click="openSession" highlight-current-row>
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="sessionTitle" label="标题" min-width="200" show-overflow-tooltip />
          <el-table-column prop="kbIds" label="知识库" width="120">
            <template #default="{ row }">{{ row.kbIds?.join(',') }}</template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="170">
            <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click.stop="doRenameSession(row)">重命名</el-button>
              <el-button size="small" text type="danger" @click.stop="doDeleteSession(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- ========== 详情弹窗 ========== -->
    <el-dialog v-model="detailVisible" title="文档详情" width="700px">
      <template v-if="detailData">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="ID">{{ detailData.id }}</el-descriptions-item>
          <el-descriptions-item label="名称">{{ detailData.kbName }}</el-descriptions-item>
          <el-descriptions-item label="分类">{{ detailData.category || '-' }}</el-descriptions-item>
          <el-descriptions-item label="大小">{{ formatSize(detailData.fileSize) }}</el-descriptions-item>
          <el-descriptions-item label="向量化">
            <el-tag :type="statusType(detailData.vectorStatus)">{{ statusLabel(detailData.vectorStatus) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="分块数">{{ detailData.chunkCount || 0 }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ChunkUploader from '../components/ChunkUploader.vue'
import { knowledgeApi } from '../api/knowledge'
import { ragChatApi } from '../api/rag-chat'
import { renderMarkdown as markdownToHtml } from '../utils/markdown'

// T17 埋点：渲染函数加计数器（行为不变），用于统计一次问答触发了多少次全量重解析
let renderCount = 0
function renderMarkdown(text) { renderCount++; return markdownToHtml(text) }

// T20：流式期间的内容节流渲染。
// 一个回答会被切成 100+ 个 SSE 分片，若每片都重解析一遍整段 markdown（O(n²)），
// 除了费 CPU，还会让未闭合的 ** / 代码围栏先按字面闪一下。
// 做法：content 实时累积，html 最多每 80ms 重算一次，回答结束时立即补算一次。
const RENDER_INTERVAL = 80
let renderTimer = null
function scheduleRender(msg) {
  if (renderTimer) return
  renderTimer = setTimeout(() => { renderTimer = null; msg.html = renderMarkdown(msg.content) }, RENDER_INTERVAL)
}
function flushRender(msg) {
  if (renderTimer) { clearTimeout(renderTimer); renderTimer = null }
  msg.html = renderMarkdown(msg.content)
}

const activeTab = ref('upload')

// 切换Tab时自动加载数据
function onTabChange(tab) {
  if (tab === 'list') loadList()
  if (tab === 'chat') loadCompletedDocs()
  if (tab === 'sessions') loadSessions()
}
onMounted(() => loadList())

// ========== 上传 & 入库 ==========
const uploadedFileKey = ref('')
const kbUploading = ref(false)
const kbForm = reactive({ fileKey: '', kbName: '', category: '' })

function handleFileUploaded({ md5, url, fileName }) {
  kbForm.fileKey = extractFileKey(url)
  kbForm.kbName = fileName.replace(/\.[^.]+$/, '')
  uploadedFileKey.value = kbForm.fileKey
  ElMessage.success('文件已上传到OSS，请填写名称后确认入库')
}
function extractFileKey(url) {
  try { return new URL(url).pathname.substring(1) } catch { return url }
}
async function doKbUpload() {
  if (!kbForm.fileKey) return
  kbUploading.value = true
  try {
    await knowledgeApi.upload(kbForm.fileKey, kbForm.kbName, kbForm.category)
    ElMessage.success('文档已入库，后台正在向量化...')
    kbForm.fileKey = ''; kbForm.kbName = ''; kbForm.category = ''; uploadedFileKey.value = ''
    activeTab.value = 'list'; loadList()
  } catch (e) { ElMessage.error('入库失败: ' + (e.message || '')) }
  finally { kbUploading.value = false }
}

// ========== 列表 ==========
const docList = ref([])
const listLoading = ref(false)
const listFilter = reactive({ vectorStatus: '' })

async function loadList() {
  listLoading.value = true
  try { docList.value = await knowledgeApi.list(listFilter.vectorStatus || undefined, undefined, 'time') }
  catch { ElMessage.error('加载列表失败') }
  finally { listLoading.value = false }
}

// ========== 详情 & 删除 ==========
const detailVisible = ref(false)
const detailData = ref(null)
async function showDetail(row) {
  try { detailData.value = await knowledgeApi.detail(row.id); detailVisible.value = true }
  catch { ElMessage.error('获取详情失败') }
}
async function doDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.kbName}」吗？`, '确认删除', { type: 'warning' })
    await knowledgeApi.delete(row.id); ElMessage.success('已删除'); loadList()
  } catch (e) { if (e !== 'cancel') ElMessage.error('删除失败') }
}

// ========== RAG问答 ==========
const chatKbIds = ref([])
const chatInput = ref('')
const chatLoading = ref(false)
const chatMessages = ref([])
const completedDocs = ref([])
const chatBox = ref(null)
const currentSessionId = ref(null)
const chatAbortCtrl = ref(null) // 流式问答中断句柄（重置/卸载时 abort，防切页后仍在写气泡）

async function loadCompletedDocs() {
  try { completedDocs.value = await knowledgeApi.list('COMPLETED', undefined, 'time') }
  catch { completedDocs.value = docList.value.filter(d => d.vectorStatus === 'COMPLETED') }
}

async function doChat() {
  if (!chatInput.value.trim() || chatKbIds.value.length === 0) return
  const question = chatInput.value.trim()
  chatInput.value = ''
  chatMessages.value.push({ role: 'user', content: question, streaming: false })
  chatMessages.value.push({ role: 'assistant', content: '', html: '', streaming: true })
  // T20：必须取回数组里的**响应式代理**再改。直接改 push 进去的裸对象会绕过 reactive 的 set 陷阱，
  // 流式期间不触发重渲染（旧写法就是这个毛病：回答最后一整坨才出现）。
  const aiMsg = chatMessages.value[chatMessages.value.length - 1]
  chatLoading.value = true
  chatAbortCtrl.value?.abort()
  chatAbortCtrl.value = new AbortController()

  // T17 埋点：首字延迟 / 总时长 / chunk 数 / 重渲染次数（仅 console，不影响行为）
  const t0 = performance.now()
  let tFirst = 0, chunks = 0
  renderCount = 0
  try {
    await ragChatApi.streamChat({
      sessionId: currentSessionId.value,
      kbIds: chatKbIds.value,
      question,
      signal: chatAbortCtrl.value.signal,
      onData: (chunk) => {
        if (!chunks) tFirst = performance.now() - t0
        chunks++
        aiMsg.content += chunk
        scheduleRender(aiMsg)
        scrollChat()
      }
    })
  } catch (e) {
    if (e.name !== 'AbortError') aiMsg.content += '【查询失败：' + (e.message || '网络异常') + '】'
  }
  finally {
    flushRender(aiMsg) // 结束时补算最后一次，确保内容完整
    console.log(`[RAG前端] 首字=${Math.round(tFirst)}ms 总=${Math.round(performance.now() - t0)}ms chunks=${chunks} 重渲染=${renderCount}次`)
    aiMsg.streaming = false; chatLoading.value = false; loadCompletedDocs()
  }
}

async function createChatSession() {
  if (chatKbIds.value.length === 0) { ElMessage.warning('请先选择知识库'); return }
  const defaultTitle = chatKbIds.value.length + '个知识库对话'
  // T21：先让用户命名；留空或取消则用默认标题
  let title = defaultTitle
  try {
    const { value } = await ElMessageBox.prompt('给这个会话起个名字（留空用默认）', '新建会话', {
      inputValue: defaultTitle,
      inputValidator: (v) => (!v || v.trim().length <= 50 ? true : '不能超过 50 个字符'),
      confirmButtonText: '创建',
      cancelButtonText: '取消'
    })
    if (value && value.trim()) title = value.trim()
  } catch { return } // 取消 = 不创建
  try {
    const data = await ragChatApi.createSession(chatKbIds.value, title)
    currentSessionId.value = data.id
    loadSessions() // 新建的会话立刻出现在「历史会话」里，不用切 Tab 才刷新
    ElMessage.success('会话已创建')
  } catch { ElMessage.error('创建会话失败') }
}
function resetChat() {
  chatAbortCtrl.value?.abort()
  if (renderTimer) { clearTimeout(renderTimer); renderTimer = null }
  chatMessages.value = []; currentSessionId.value = null
}

function scrollChat() {
  nextTick(() => { if (chatBox.value) chatBox.value.scrollTop = chatBox.value.scrollHeight })
}

// ========== 历史会话 ==========
const sessionList = ref([])

async function loadSessions() {
  try { sessionList.value = await ragChatApi.listSessions() }
  catch { sessionList.value = [] }
}

async function openSession(row) {
  try {
    const data = await ragChatApi.sessionDetail(row.id)

    // 加载知识库选择
    chatKbIds.value = data.kbIds || []
    currentSessionId.value = data.id
    // 恢复历史消息（html 一次算好，模板直接渲染，不必每次重渲染都重解析）
    chatMessages.value = (data.messages || []).map(m => ({
      role: m.role, content: m.content, html: m.role === 'user' ? '' : renderMarkdown(m.content), streaming: false
    }))
    activeTab.value = 'chat'
    ElMessage.success('已恢复会话 #' + row.id)
  } catch { ElMessage.error('加载会话失败') }
}

// T21：重命名。成功后就地改 row.sessionTitle，不重拉列表——重命名不刷 updatedAt，
// 列表顺序本就不该变，重拉只会带来不必要的跳动。
async function doRenameSession(row) {
  let value
  try {
    ({ value } = await ElMessageBox.prompt('输入新的会话名称', '重命名会话', {
      inputValue: row.sessionTitle || '',
      inputValidator: (v) => (v && v.trim() ? (v.trim().length <= 50 ? true : '不能超过 50 个字符') : '名称不能为空'),
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    }))
  } catch { return } // 取消
  try {
    await ragChatApi.renameSession(row.id, value.trim())
    row.sessionTitle = value.trim()
    ElMessage.success('已重命名')
  } catch { ElMessage.error('重命名失败') }
}

async function doDeleteSession(row) {
  try {
    await ElMessageBox.confirm(`确定删除会话「${row.sessionTitle}」吗？`, '确认删除', { type: 'warning' })
    await ragChatApi.deleteSession(row.id)
    ElMessage.success('已删除'); loadSessions()
  } catch (e) { if (e !== 'cancel') ElMessage.error('删除失败') }
}

onBeforeUnmount(() => {
  chatAbortCtrl.value?.abort()
  if (renderTimer) { clearTimeout(renderTimer); renderTimer = null } // T20：清掉节流渲染定时器
})

// ========== 工具 ==========
function formatSize(bytes) {
  if (!bytes) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}
function formatTime(t) { return t ? t.replace('T', ' ').substring(0, 19) : '' }
function statusType(s) { return { PENDING: 'info', PROCESSING: 'warning', COMPLETED: 'success', FAILED: 'danger' }[s] || 'info' }
function statusLabel(s) { return { PENDING: '排队中', PROCESSING: '处理中', COMPLETED: '已完成', FAILED: '失败' }[s] || s }
</script>

<style scoped>
.markdown-body :deep(h2) { font-size: 16px; margin: 8px 0 4px; }
.markdown-body :deep(h3) { font-size: 14px; margin: 6px 0 3px; }
.markdown-body :deep(p) { margin: 4px 0; }
.markdown-body :deep(ul), .markdown-body :deep(ol) { padding-left: 18px; margin: 4px 0; }
.markdown-body :deep(li) { margin: 2px 0; }
.markdown-body :deep(code) { background: var(--nt-surface); padding: 1px 4px; border-radius: 3px; font-size: 13px; }
.markdown-body :deep(pre) { background: #1f2937; color: #f3f4f6; padding: 10px; border-radius: 6px; overflow-x: auto; } /* 深色代码块保留为阅读强调（Notion 浅底为 spec 偏差取舍） */
.markdown-body :deep(pre code) { background: none; padding: 0; }
.markdown-body :deep(blockquote) { border-left: 3px solid var(--nt-hairline-strong); padding-left: 12px; color: var(--nt-slate); margin: 6px 0; }
.markdown-body :deep(table) { border-collapse: collapse; margin: 6px 0; }
.markdown-body :deep(th), .markdown-body :deep(td) { border: 1px solid var(--nt-hairline-strong); padding: 4px 8px; font-size: 13px; }
</style>
