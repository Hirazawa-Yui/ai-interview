<template>
  <div>
    <h1 style="margin-bottom: 24px">📄 简历管理</h1>

    <!-- ========== 上传区 ========== -->
    <el-collapse v-model="activePanels" style="margin-bottom: 20px">
      <el-collapse-item title="上传新简历" name="upload">
        <el-upload
          ref="uploadRef"
          :auto-upload="false"
          :limit="1"
          accept=".pdf,.doc,.docx,.txt"
          :on-change="handleFileSelect"
          drag
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">
            拖拽简历文件到此处 或 <em>点击选择</em>
          </div>
          <template #tip>
            <div class="el-upload__tip">
              支持 PDF、Word、TXT 格式，单个文件不超过 10MB
            </div>
          </template>
        </el-upload>
        <div v-if="selectedFile" style="margin-top: 12px; text-align: center">
          <el-tag type="info" style="margin-right: 8px">{{ selectedFile.name }}</el-tag>
          <el-tag>{{ formatSize(selectedFile.size) }}</el-tag>
          <el-button type="primary" @click="doUpload" :loading="uploading" style="margin-left: 12px">
            {{ uploading ? '提交中...' : '提交分析' }}
          </el-button>
          <el-button @click="selectedFile = null">取消</el-button>
        </div>
      </el-collapse-item>
    </el-collapse>

    <!-- ========== 简历列表 ========== -->
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span><strong>简历列表</strong>（共 {{ resumeList.length }} 份）</span>
          <el-button size="small" @click="loadList" :loading="listLoading">刷新</el-button>
        </div>
      </template>

      <el-table
        :data="resumeList"
        style="width: 100%"
        @row-click="showDetail"
        highlight-current-row
        stripe
        v-loading="listLoading"
      >
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="fileName" label="文件名" min-width="180" show-overflow-tooltip />
        <el-table-column prop="fileSize" label="大小" width="90">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column prop="latestScore" label="评分" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.latestScore != null" :type="scoreType(row.latestScore)" effect="dark">
              {{ row.latestScore }}
            </el-tag>
            <span v-else style="color: var(--nt-muted)">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="analyzeStatus" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.analyzeStatus)" size="small">
              {{ statusLabel(row.analyzeStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="上传时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click.stop="showDetail(row)">详情</el-button>
            <el-button size="small" text type="danger" @click.stop="doDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ========== 详情弹窗 ========== -->
    <el-dialog v-model="detailVisible" title="简历详情" width="800px" destroy-on-close>
      <template v-if="detailData">
        <!-- 基本信息 -->
        <el-descriptions :column="2" border size="small" style="margin-bottom: 20px">
          <el-descriptions-item label="ID">{{ detailData.id }}</el-descriptions-item>
          <el-descriptions-item label="文件名">{{ detailData.fileName }}</el-descriptions-item>
          <el-descriptions-item label="文件大小">{{ formatSize(detailData.fileSize) }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ detailData.contentType }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(detailData.analyzeStatus)" size="small">
              {{ statusLabel(detailData.analyzeStatus) }}
            </el-tag>
            <span v-if="detailData.analyzeError" style="color: var(--nt-error); margin-left: 8px; font-size: 12px">
              {{ detailData.analyzeError }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="上传时间">{{ formatTime(detailData.createdAt) }}</el-descriptions-item>
        </el-descriptions>

        <!-- 分析中 loading -->
        <div v-if="isAnalyzing(detailData.analyzeStatus)" style="text-align: center; padding: 20px">
          <el-progress :percentage="100" :indeterminate="true" :duration="2" />
          <p style="color: var(--nt-slate); margin-top: 8px">AI 正在分析中，请稍候...</p>
          <el-button @click="refreshDetail" :loading="polling">刷新</el-button>
        </div>

        <!-- 分析结果 -->
        <div v-if="detailData.analyses && detailData.analyses.length > 0">
          <h3 style="margin: 0 0 16px">📊 AI 分析结果</h3>
          <div v-for="(a, idx) in detailData.analyses" :key="a.id" style="margin-bottom: 20px">
            <el-card v-if="idx === 0" shadow="hover">
              <!-- 总评分 -->
              <div style="text-align: center; margin-bottom: 16px">
                <el-progress type="dashboard" :percentage="a.overallScore" :color="scoreColor(a.overallScore)">
                  <template #default="{ percentage }">
                    <span class="percentage-value">{{ percentage }}分</span>
                  </template>
                </el-progress>
              </div>

              <!-- 评语 -->
              <el-alert :title="a.summary" type="info" :closable="false" show-icon style="margin-bottom: 16px" />

              <!-- 优势 -->
              <div v-if="a.strengthsJson" style="margin-bottom: 12px">
                <h4>✅ 优势</h4>
                <ul style="padding-left: 20px">
                  <li v-for="(s, i) in parseJson(a.strengthsJson)" :key="i" style="margin-bottom: 4px; color: var(--nt-success)">{{ s }}</li>
                </ul>
              </div>

              <!-- 改进建议 -->
              <div v-if="a.suggestionsJson">
                <h4>💡 改进建议</h4>
                <el-table :data="parseJson(a.suggestionsJson)" size="small" style="margin-top: 8px">
                  <el-table-column prop="category" label="类别" width="100">
                    <template #default="{ row }">
                      <el-tag size="small">{{ row.category }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="priority" label="优先级" width="80">
                    <template #default="{ row }">
                      <el-tag :type="row.priority === 'high' ? 'danger' : row.priority === 'medium' ? 'warning' : 'info'" size="small">
                        {{ row.priority }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="issue" label="问题" min-width="200" show-overflow-tooltip />
                  <el-table-column prop="recommendation" label="建议" min-width="200" show-overflow-tooltip />
                </el-table>
              </div>

              <div style="color: var(--nt-muted); font-size: 12px; margin-top: 8px">
                分析时间：{{ formatTime(a.analyzedAt) }}
              </div>
            </el-card>

            <!-- 历史分析（折叠） -->
            <el-collapse v-if="idx > 0 || detailData.analyses.length > 1">
              <el-collapse-item :title="`历史分析 #${idx + 1}（${a.overallScore} 分 — ${formatTime(a.analyzedAt)}）`">
                <p><strong>评语：</strong>{{ a.summary }}</p>
                <p><strong>优势：</strong>{{ parseJson(a.strengthsJson).join('；') }}</p>
              </el-collapse-item>
            </el-collapse>
          </div>
        </div>

        <!-- 文本预览 -->
        <el-collapse style="margin-top: 16px">
          <el-collapse-item title="📝 简历解析文本">
            <div style="max-height: 300px; overflow-y: auto; white-space: pre-wrap; background: var(--nt-surface-soft); padding: 12px; border-radius: 4px; font-size: 13px; line-height: 1.8">
              {{ detailData.parsedText || '(无文本)' }}
            </div>
          </el-collapse-item>
        </el-collapse>
      </template>

      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button type="warning" @click="doReanalyze" :loading="reanalyzing">重新分析</el-button>
        <el-button type="danger" @click="doDeleteFromDetail">删除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { resumeApi } from '../api/resume'

const uploadRef = ref(null)
const activePanels = ref(['upload'])
const selectedFile = ref(null)
const uploading = ref(false)
const listLoading = ref(false)
const polling = ref(false)
const reanalyzing = ref(false)
const resumeList = ref([])
const detailVisible = ref(false)
const detailData = ref(null)

// ===== 详情轮询 / 列表延迟刷新 定时器句柄（防关弹窗/切页后后台继续请求） =====
let detailPollTimer = null
let refreshTimer = null

function stopDetailPoll() {
  if (detailPollTimer) { clearTimeout(detailPollTimer); detailPollTimer = null }
}

function scheduleDetailPoll() {
  stopDetailPoll() // 只保留一条链
  detailPollTimer = setTimeout(pollCurrentDetail, 3000)
}

function scheduleListRefresh() {
  if (refreshTimer) clearTimeout(refreshTimer) // 防连续上传重复排期
  refreshTimer = setTimeout(loadList, 5000)
}

// ========== 生命周期 ==========
onMounted(() => loadList())
onBeforeUnmount(() => {
  stopDetailPoll()
  if (refreshTimer) clearTimeout(refreshTimer)
})

// ========== 列表 ==========
async function loadList() {
  listLoading.value = true
  try {
    resumeList.value = await resumeApi.list()
  } catch (e) {
    ElMessage.error('加载列表失败')
  } finally {
    listLoading.value = false
  }
}

// ========== 上传 ==========
function handleFileSelect(uploadFile) {
  selectedFile.value = uploadFile.raw
  activePanels.value = ['upload']
}

async function doUpload() {
  if (!selectedFile.value) return
  uploading.value = true
  try {
    await resumeApi.upload(selectedFile.value)
    ElMessage.success('上传成功，AI 正在分析...')
    selectedFile.value = null
    loadList()
    // 5秒后自动刷新列表看分析结果
    scheduleListRefresh()
  } catch (e) {
    ElMessage.error('上传失败: ' + (e.message || '未知错误'))
  } finally {
    uploading.value = false
  }
}

// ========== 详情 ==========
async function showDetail(row) {
  try {
    detailData.value = await resumeApi.detail(row.id)
    detailVisible.value = true
    // 如果还在分析中，自动轮询
    if (isAnalyzing(detailData.value.analyzeStatus)) {
      scheduleDetailPoll()
    }
  } catch (e) {
    ElMessage.error('获取详情失败')
  }
}

async function pollCurrentDetail() {
  if (!detailData.value?.id) return
  if (polling.value) return // 上轮在途，跳过本轮（链上已有后续排期）
  polling.value = true
  try {
    detailData.value = await resumeApi.detail(detailData.value.id)
    if (!isAnalyzing(detailData.value.analyzeStatus)) {
      stopDetailPoll()
      loadList() // 刷新列表中的状态
    } else {
      scheduleDetailPoll()
    }
  } catch (e) {
    // ignore（下次排期继续）
    scheduleDetailPoll()
  } finally {
    polling.value = false
  }
}

// 手动刷新详情：立即查一轮并重排轮询链
function refreshDetail() {
  stopDetailPoll()
  pollCurrentDetail()
}

// 关弹窗即停轮询（原实现关窗后仍在后台每 3s 请求直至状态翻转）
watch(detailVisible, (v) => { if (!v) stopDetailPoll() })

// ========== 删除 ==========
async function doDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.fileName}」吗？此操作同时删除OSS文件和AI分析结果。`, '确认删除', { type: 'warning' })
    await resumeApi.delete(row.id)
    ElMessage.success('删除成功')
    loadList()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}

async function doDeleteFromDetail() {
  if (!detailData.value) return
  await doDelete(detailData.value)
  detailVisible.value = false
}

// ========== 重分析 ==========
async function doReanalyze() {
  if (!detailData.value) return
  reanalyzing.value = true
  try {
    await resumeApi.reanalyze(detailData.value.id)
    ElMessage.success('已触发重新分析')
    stopDetailPoll() // 清掉旧链再起新轮询，防双链
    pollCurrentDetail()
  } catch (e) {
    ElMessage.error('重分析失败')
  } finally {
    reanalyzing.value = false
  }
}

// ========== 工具函数 ==========
function formatSize(bytes) {
  if (!bytes) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function formatTime(t) {
  if (!t) return ''
  return t.replace('T', ' ').substring(0, 19)
}

function statusType(s) {
  return { PENDING: 'info', PROCESSING: 'warning', COMPLETED: 'success', FAILED: 'danger' }[s] || 'info'
}

function statusLabel(s) {
  return { PENDING: '排队中', PROCESSING: '分析中', COMPLETED: '已完成', FAILED: '失败' }[s] || s
}

function scoreType(s) {
  if (s >= 80) return 'success'
  if (s >= 60) return 'warning'
  return 'danger'
}

function scoreColor(s) {
  if (s >= 80) return '#1aae39' // --nt-success
  if (s >= 60) return '#dd5b00' // --nt-warning
  return '#e03131' // --nt-error
}

function isAnalyzing(s) {
  return s === 'PENDING' || s === 'PROCESSING'
}

function parseJson(val) {
  if (!val) return []
  try {
    return typeof val === 'string' ? JSON.parse(val) : val
  } catch {
    return []
  }
}
</script>

<style scoped>
.percentage-value {
  font-size: 24px;
  font-weight: bold;
}
</style>
