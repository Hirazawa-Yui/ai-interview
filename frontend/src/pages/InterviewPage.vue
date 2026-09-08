<template>
  <div>
    <h1 style="margin-bottom: 24px">🎯 模拟面试</h1>

    <!-- ========== 续答加载中 ========== -->
    <el-card v-if="resumeLoading" style="max-width: 700px; text-align: center; padding: 40px">
      <el-progress :percentage="100" :indeterminate="true" :duration="2" />
      <p style="margin-top: 16px; color: #6b7280">正在恢复面试记录...</p>
    </el-card>

    <!-- ========== Phase 1: 面试设置 ========== -->
    <el-card v-if="!sessionId && !resumeLoading" style="max-width: 700px">
      <template #header><strong>面试设置</strong></template>
      <el-form :model="setupForm" label-width="100px">
        <el-form-item label="选择简历">
          <el-select v-model="setupForm.resumeId" placeholder="可选，不选则仅基于JD出题" clearable style="width: 100%">
            <el-option v-for="r in resumes" :key="r.id" :label="r.fileName" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="岗位JD" required>
          <el-input v-model="setupForm.jdText" type="textarea" :rows="4"
            placeholder="粘贴目标岗位的JD描述，例如：Java后端开发工程师，要求熟悉Spring Boot、MySQL、Redis..." />
        </el-form-item>
        <el-form-item label="技术方向" required>
          <el-select v-model="setupForm.direction" placeholder="选择面试方向" style="width: 100%">
            <el-option label="前端" value="frontend" />
            <el-option label="后端" value="backend" />
            <el-option label="测试" value="test" />
            <el-option label="算法" value="algorithm" />
            <el-option label="数据" value="data" />
            <el-option label="运维" value="devops" />
            <el-option label="全栈" value="fullstack" />
          </el-select>
        </el-form-item>
        <el-form-item label="题目数量">
          <el-select v-model="setupForm.questionCount" style="width: 120px">
            <el-option :value="5" label="5 道" />
            <el-option :value="8" label="8 道" />
            <el-option :value="10" label="10 道" />
            <el-option :value="15" label="15 道" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" @click="startInterview" :loading="creating"
            :disabled="!setupForm.jdText || !setupForm.direction">
            🚀 {{ creating ? 'AI正在出题...' : '开始面试' }}
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- ========== Phase 2: 聊天答题 ========== -->
    <div v-if="sessionId && !showReport">
      <!-- 顶部信息栏 -->
      <div style="margin-bottom: 12px; display: flex; gap: 12px; align-items: center; flex-wrap: wrap">
        <el-tag type="primary" size="large">{{ directionLabel }}</el-tag>
        <el-tag :type="allDone ? 'success' : 'warning'" size="large">
          {{ currentProgress }}/{{ totalQuestions }}
        </el-tag>
        <template v-if="allDone && !showReport">
          <span v-if="!resumeSuppressAuto" style="color: #16a34a; font-weight: 500">
            ⏳ 正在自动提交评估...
          </span>
          <el-button v-else type="primary" :loading="evaluating" @click="submitEvaluation">
            提交评估
          </el-button>
        </template>
        <el-button @click="handleExit" style="margin-left: auto">退出</el-button>
      </div>

      <!-- 聊天区 -->
      <div style="border: 1px solid #e5e7eb; border-radius: 12px; height: 500px; display: flex; flex-direction: column; background: #fafafa; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,.06)">
        <div ref="chatBox" style="flex: 1; overflow-y: auto; padding: 20px">
          <div v-for="(msg, i) in chatMessages" :key="i"
            :style="{ marginBottom: '16px', display: 'flex', flexDirection: 'column', alignItems: msg.role === 'user' ? 'flex-end' : 'flex-start' }">
            <div :style="{ maxWidth: '82%', padding: '12px 16px', borderRadius: '12px',
              background: msg.role === 'user' ? '#3b82f6' : msg.role === 'system' ? '#fef3c7' : '#fff',
              color: msg.role === 'user' ? '#fff' : '#1f2937', whiteSpace: 'pre-wrap', wordBreak: 'break-word',
              boxShadow: '0 1px 2px rgba(0,0,0,.06)', lineHeight: '1.6', fontSize: '14px' }">
              {{ msg.content }}
              <div v-if="msg.tags && msg.tags.length" style="margin-top: 8px">
                <el-tag v-for="t in msg.tags" :key="t" size="small" effect="plain" round style="margin-right: 4px">{{ t }}</el-tag>
              </div>
            </div>
          </div>
        </div>
        <!-- 输入区 -->
        <div style="border-top: 1px solid #e5e7eb; padding: 12px; display: flex; gap: 8px; align-items: flex-end">
          <el-input v-model="chatInput" type="textarea" :rows="3" :maxlength="5000" show-word-limit
            :placeholder="allDone ? '已完成全部题目' : '输入你的回答...（Ctrl+Enter 发送）'"
            @keyup.ctrl.enter="doSubmitAnswer" :disabled="allDone || submitting"
            style="flex:1" />
          <el-button type="primary" @click="doSubmitAnswer" :loading="submitting"
            :disabled="allDone || !chatInput.trim()" style="height:40px">
            发送
          </el-button>
        </div>
      </div>
    </div>

    <!-- ========== Phase 3: 评估报告 ========== -->
    <div v-if="showReport">
      <!-- 加载中 -->
      <el-card v-if="loadingReport" style="text-align: center; padding: 60px">
        <el-progress :percentage="100" :indeterminate="true" :duration="3" />
        <p style="margin-top: 20px; color: #6b7280; font-size: 16px">AI正在评估您的面试表现...</p>
      </el-card>

      <!-- 报告内容 -->
      <div v-else-if="reportData">
        <el-card style="margin-bottom: 16px; text-align: center">
          <h3>综合评分</h3>
          <el-progress type="dashboard" :percentage="reportData.overallScore || 0"
            :color="scoreColor(reportData.overallScore)" :stroke-width="12" style="margin: 12px 0" />
          <el-alert type="info" :closable="false" style="text-align: left; margin-top: 12px">
            {{ reportData.summary }}
          </el-alert>
        </el-card>

        <!-- 逐题评分 -->
        <el-card v-if="reportData.perQuestion && reportData.perQuestion.length" style="margin-bottom: 16px">
          <template #header><strong>逐题评价</strong></template>
          <el-collapse>
            <el-collapse-item v-for="(q, i) in reportData.perQuestion" :key="i"
              :title="'第' + (q.questionNumber || (i+1)) + '题 — ' + (q.score || 0) + '分'">
              <p style="color: #6b7280; margin-bottom: 8px">{{ q.comment }}</p>
              <!-- 查找对应回答 -->
              <p v-for="a in reportAnswers" :key="a.questionNumber"
                v-show="a.questionNumber === (q.questionNumber || i+1)"
                style="background:#f3f4f6; padding:8px; border-radius:4px; font-size:13px">
                <strong>你的回答：</strong>{{ a.answerText }}
              </p>
            </el-collapse-item>
          </el-collapse>
        </el-card>

        <!-- 优势 & 改进 -->
        <div style="display: flex; gap: 16px; flex-wrap: wrap">
          <el-card v-if="reportData.strengths && reportData.strengths.length" style="flex: 1; min-width: 300px">
            <template #header><strong>✅ 优势</strong></template>
            <el-tag v-for="(s, i) in reportData.strengths" :key="i" type="success" effect="plain"
              style="margin: 4px; display: block; text-align: left; white-space: pre-wrap; height: auto; padding: 4px 8px">
              {{ s }}
            </el-tag>
          </el-card>
          <el-card v-if="reportData.improvements && reportData.improvements.length" style="flex: 1; min-width: 300px">
            <template #header><strong>📝 改进建议</strong></template>
            <div v-for="(imp, i) in reportData.improvements" :key="i"
              style="margin-bottom: 12px; padding: 8px; background: #fffbeb; border-left: 3px solid #f59e0b; border-radius: 4px">
              <el-tag size="small" type="warning">{{ imp.category }}</el-tag>
              <p style="margin: 6px 0; font-size: 13px"><strong>问题：</strong>{{ imp.issue }}</p>
              <p style="margin: 0; font-size: 13px; color: #059669"><strong>建议：</strong>{{ imp.suggestion }}</p>
            </div>
          </el-card>
        </div>

        <!-- 底部操作 -->
        <div style="margin-top: 20px; text-align: center; display: flex; gap: 12px; justify-content: center">
          <el-button type="primary" @click="handleExit">{{ resumeMode ? '返回记录' : '🔄 重新面试' }}</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { interviewApi } from '../api/interview'
import { resumeApi } from '../api/resume'

const route = useRoute()
const router = useRouter()

// ========== Phase 1: Setup ==========
const setupForm = reactive({ resumeId: null, jdText: '', direction: '', questionCount: 8 })
const resumes = ref([])
const creating = ref(false)
const sessionId = ref(null)
const totalQuestions = ref(8)

// 方向标签
const directionMap = {
  frontend: '前端', backend: '后端', test: '测试', algorithm: '算法',
  data: '数据', devops: '运维', fullstack: '全栈'
}
const directionLabel = ref('')

// 加载简历列表；带 ?session= 时进入续答模式（从面试记录页跳来）
onMounted(async () => {
  try { resumes.value = await resumeApi.list() } catch { /* 空列表 */ }
  const sid = route.query.session
  if (sid) enterResume(Number(sid))
})

async function startInterview() {
  clearAllTimers() // 防上个会话残留的延迟回调触发
  if (!setupForm.jdText || !setupForm.direction) {
    ElMessage.warning('请填写岗位JD并选择技术方向')
    return
  }
  creating.value = true
  try {
    const data = await interviewApi.create(
      setupForm.resumeId, setupForm.jdText, setupForm.direction, setupForm.questionCount
    )
    resumeMode.value = false
    resumeSuppressAuto.value = false
    sessionId.value = data.sessionId
    totalQuestions.value = data.questionCount
    directionLabel.value = directionMap[data.direction] || data.direction

    // 设置草稿 Key 并恢复
    draftKey = 'interview:draft:' + data.sessionId
    restoreDraft()

    // 显示第一题
    const q = data.firstQuestion
    chatMessages.value.push({
      role: 'assistant', content: '【第 ' + q.questionNumber + ' 题】' + q.questionText,
      tags: q.tags, questionNumber: q.questionNumber
    })
    currentQuestionNumber.value = q.questionNumber
    ElMessage.success('AI已生成 ' + data.questionCount + ' 道题目，面试开始！')
  } catch (e) {
    ElMessage.error('创建面试失败: ' + (e.message || ''))
  } finally { creating.value = false }
}

// ========== Phase 2: Chat ==========
const chatMessages = ref([])
const chatInput = ref('')
const submitting = ref(false)
const currentQuestionNumber = ref(0)
const chatBox = ref(null)

const currentProgress = ref(0)
const allDone = ref(false)

// ===== 续答模式（从面试记录页 ?session= 进入，T7） =====
const resumeMode = ref(false) // 续答中：退出回记录列表而非新面试表单
const resumeSuppressAuto = ref(false) // 续答进入的"已完成/失败"会话：不自动提交评估，等用户点按钮
const resumeLoading = ref(false)

// ===== 定时器集中管理（草稿防抖 / 自动提交 / 评估轮询） =====
let draftKey = ''
let draftTimer = null
let pollTimer = null
let autoSubmitTimer = null
let sessionEpoch = 0 // 会话代次：递增后旧会话的延迟回调全部作废（防退出竞态误触发评估）
let pollCount = 0

const MAX_POLL_COUNT = 40 // 评估轮询封顶 ≈2 分钟，防后端卡死永久 loading

function clearAllTimers() {
  if (draftTimer) { clearTimeout(draftTimer); draftTimer = null }
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
  if (autoSubmitTimer) { clearTimeout(autoSubmitTimer); autoSubmitTimer = null }
  sessionEpoch++
}

function stopPoll() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

// 防抖自动保存：停止输入 1.5s 后保存
watch(chatInput, (val) => {
  if (!draftKey) return
  if (draftTimer) clearTimeout(draftTimer)
  draftTimer = setTimeout(() => {
    if (val.trim()) {
      localStorage.setItem(draftKey, val)
    } else {
      localStorage.removeItem(draftKey)
    }
  }, 1500)
})

function restoreDraft() {
  if (!draftKey) return
  const saved = localStorage.getItem(draftKey)
  if (saved) {
    chatInput.value = saved
  }
}

function clearDraft() {
  if (draftKey) localStorage.removeItem(draftKey)
}

async function doSubmitAnswer() {
  const text = chatInput.value.trim()
  if (!text || allDone.value || !sessionId.value) return

  const qNum = currentQuestionNumber.value
  chatInput.value = ''
  submitting.value = true

  // 添加用户回答气泡
  chatMessages.value.push({ role: 'user', content: text })
  scrollChat()

  try {
    const result = await interviewApi.submitAnswer(sessionId.value, qNum, text)
    currentProgress.value = parseInt((result.progress || '').split('/')[0]) || qNum

    if (result.allDone) {
      allDone.value = true
      chatMessages.value.push({ role: 'system', content: '✅ ' + result.message })
      clearDraft()
      ElMessage.success('所有题目已完成，正在自动提交评估...')
    } else if (result.nextQuestion) {
      clearDraft()
      // 显示下一题
      const nq = result.nextQuestion
      chatMessages.value.push({
        role: 'assistant', content: '【第 ' + nq.questionNumber + ' 题】' + nq.questionText,
        tags: nq.tags, questionNumber: nq.questionNumber
      })
      currentQuestionNumber.value = nq.questionNumber
    }
  } catch (e) {
    chatMessages.value.push({ role: 'system', content: '❌ 提交失败: ' + (e.message || '请重试') })
    ElMessage.error('提交回答失败')
  } finally {
    submitting.value = false
    scrollChat()
  }
}

// 全部答完 → 延迟自动提交评估（存句柄 + 会话代次校验：1.5s 内退出/重开不误触）
watch(allDone, (val) => {
  if (!val) return
  if (resumeSuppressAuto.value) return // 续答进入的已完成会话：等用户点"提交评估"
  const epoch = sessionEpoch
  if (autoSubmitTimer) clearTimeout(autoSubmitTimer)
  autoSubmitTimer = setTimeout(() => {
    autoSubmitTimer = null
    if (epoch === sessionEpoch) submitEvaluation()
  }, 1500)
})

function scrollChat() {
  nextTick(() => { if (chatBox.value) chatBox.value.scrollTop = chatBox.value.scrollHeight })
}

// ===== 续答：从 ?session= 恢复历史会话（T7） =====
async function enterResume(id) {
  if (resumeLoading.value) return // 防重入
  clearAllTimers()
  // 复位上一会话的界面态（可能正停在答题/报告视图）
  showReport.value = false
  loadingReport.value = false
  reportData.value = null
  reportAnswers.value = []
  allDone.value = false
  resumeMode.value = true
  resumeLoading.value = true
  try {
    const d = await interviewApi.detail(id)
    sessionId.value = d.id
    totalQuestions.value = d.questionCount
    directionLabel.value = directionMap[d.direction] || d.direction
    currentProgress.value = d.currentQuestion || 0
    draftKey = 'interview:draft:' + d.id
    buildChatFromDetail(d)
    dispatchByStatus(d.status)
  } catch (e) {
    ElMessage.error('加载面试失败: ' + (e.message || ''))
    resetChat()
    router.replace({ path: '/interview' }) // 去掉 query 回落 Phase1
  } finally { resumeLoading.value = false }
}

// 由 questions + answers 重建聊天记录（已答 Q&A 对 + 未答的下一题）
function buildChatFromDetail(d) {
  const byNumber = new Map((d.questions || []).map(q => [q.questionNumber, q]))
  const list = []
  const answers = (d.answers || []).slice().sort((a, b) => a.questionNumber - b.questionNumber)
  for (const a of answers) {
    const q = byNumber.get(a.questionNumber)
    list.push({
      role: 'assistant',
      content: '【第 ' + a.questionNumber + ' 题】' + (q ? q.questionText : a.questionText),
      tags: q ? (q.tags || []) : [], questionNumber: a.questionNumber
    })
    list.push({ role: 'user', content: a.answerText })
  }
  chatMessages.value = list

  // 下一题 = 已答数 + 1（find 防御，不依赖数组下标）
  const cur = d.currentQuestion || answers.length
  const nextQ = (d.questions || []).find(q => q.questionNumber === cur + 1)
  if (nextQ) {
    chatMessages.value.push({
      role: 'assistant', content: '【第 ' + nextQ.questionNumber + ' 题】' + nextQ.questionText,
      tags: nextQ.tags, questionNumber: nextQ.questionNumber
    })
    currentQuestionNumber.value = nextQ.questionNumber
  } else {
    currentQuestionNumber.value = cur
  }
}

// 按会话状态分流：答题续 / 待评估只读 / 评估中轮询 / 已评估直接报告
function dispatchByStatus(status) {
  if (status === 'EVALUATED') {
    showReport.value = true
    loadingReport.value = true
    resumeIntoReport()
  } else if (status === 'EVALUATING') {
    showReport.value = true
    loadingReport.value = true
    resumeIntoReport() // 探测一次：已终态直接出报告，仍在评估中则转轮询
  } else if (status === 'COMPLETED' || status === 'FAILED') {
    // 全答完但评估未完成/失败：只读态 + 手动"提交评估"（不自动，避免无声 POST）
    allDone.value = true
    resumeSuppressAuto.value = true
    chatMessages.value.push({
      role: 'system',
      content: status === 'COMPLETED'
        ? '✅ 已答完所有题目，请点击上方"提交评估"生成报告'
        : '⚠️ 上次评估失败，可点击上方"提交评估"重试'
    })
  } else {
    // IN_PROGRESS / CREATED：直接续答，并恢复该会话未发送的输入草稿
    restoreDraft()
  }
}

function resetChat() {
  clearAllTimers() // 含 pollTimer/autoSubmitTimer/draftTimer + 会话代次递增
  clearDraft()
  draftKey = ''
  sessionId.value = null
  chatMessages.value = []
  allDone.value = false
  currentProgress.value = 0
  currentQuestionNumber.value = 0
  chatInput.value = '' // 防残留半截输入冒充新会话草稿
  resumeMode.value = false
  resumeSuppressAuto.value = false
}

// ========== Phase 3: Report ==========
const showReport = ref(false)
const evaluating = ref(false)
const loadingReport = ref(false)
const reportData = ref(null)
const reportAnswers = ref([])

async function submitEvaluation() {
  if (!sessionId.value) return
  evaluating.value = true
  try {
    await interviewApi.evaluate(sessionId.value)
    ElMessage.success('评估已提交，AI正在分析中...')
    showReport.value = true
    loadingReport.value = true
    startPolling()
  } catch (e) {
    ElMessage.error('提交评估失败: ' + (e.message || ''))
  } finally { evaluating.value = false }
}

// 评估报告就绪：停止轮询、填报告并拉回答列表（轮询完成 / 续答探测共用）
async function fillReport(data) {
  stopPoll()
  loadingReport.value = false
  reportData.value = data
  try {
    const detail = await interviewApi.detail(sessionId.value)
    reportAnswers.value = detail.answers || []
  } catch { reportAnswers.value = [] }
}

// 评估失败/超时：退出报告态回"已答完待提交"视图（顶部按钮可重试）
function backToRetry() {
  stopPoll()
  showReport.value = false
  allDone.value = true
  chatMessages.value.push({ role: 'system', content: '⚠️ 评估未完成，可点击上方"提交评估"重试' })
}

// 续答进入已评估/评估中的会话：先探测一次——已终态直接出报告，仍在评估转轮询（不重复 POST /evaluate）
async function resumeIntoReport() {
  try {
    const data = await interviewApi.getEvaluation(sessionId.value)
    if (data.sessionStatus === 'EVALUATED') { fillReport(data) }
    else if (data.sessionStatus === 'FAILED') {
      ElMessage.error('评估失败: ' + (data.error || '请稍后重试'))
      backToRetry()
    } else { startPolling() }
  } catch { startPolling() }
}

function startPolling() {
  stopPoll()
  pollCount = 0
  pollTimer = setInterval(async () => {
    pollCount++
    try {
      const data = await interviewApi.getEvaluation(sessionId.value)
      if (data.sessionStatus === 'EVALUATED') {
        clearDraft()
        fillReport(data)
      } else if (data.sessionStatus === 'FAILED') {
        ElMessage.error('评估失败: ' + (data.error || '请稍后重试'))
        backToRetry()
      } else if (pollCount >= MAX_POLL_COUNT) {
        ElMessage.error('评估超时（2 分钟未完成），可点击上方"提交评估"重试')
        backToRetry()
      }
    } catch { /* 继续轮询 */ }
  }, 3000)
}

function scoreColor(score) {
  if (!score) return '#909399'
  if (score >= 80) return '#67c23a'
  if (score >= 60) return '#e6a23c'
  return '#f56c6c'
}

// 退出/重新面试统一出口：续答模式回记录列表（重进自动刷新状态），普通模式清表单留 Phase1
function handleExit() {
  const fromResume = resumeMode.value
  resetChat() // 已含 clearAllTimers + 清草稿与输入 + 复位续答标志
  showReport.value = false
  reportData.value = null
  loadingReport.value = false
  reportAnswers.value = []
  evaluating.value = false
  if (fromResume) {
    router.replace('/interviews')
  } else {
    setupForm.jdText = ''
    setupForm.direction = ''
  }
}

// 路由 query 兜底：新 ?session= 触发续答；侧栏重进 /interview（去掉 query）则复位回 Phase1
watch(() => route.query.session, (v) => {
  if (v && String(v) !== String(sessionId.value) && !resumeLoading.value) {
    enterResume(Number(v))
  } else if (!v && (sessionId.value || resumeMode.value)) {
    resetChat()
    showReport.value = false
  }
})

// 卸载清理全部定时器（草稿防抖 / 自动提交 / 评估轮询）
onBeforeUnmount(clearAllTimers)
</script>
