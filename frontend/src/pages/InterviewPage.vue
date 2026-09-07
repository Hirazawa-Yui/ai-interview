<template>
  <div>
    <h1 style="margin-bottom: 24px">🎯 模拟面试</h1>

    <!-- ========== Phase 1: 面试设置 ========== -->
    <el-card v-if="!sessionId" style="max-width: 700px">
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
        <span v-if="allDone && !showReport" style="color: #16a34a; font-weight: 500">
          ⏳ 正在自动提交评估...
        </span>
        <el-button @click="resetChat" style="margin-left: auto">退出</el-button>
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
          <el-button type="primary" @click="resetAll">🔄 重新面试</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { interviewApi } from '../api/interview'
import { resumeApi } from '../api/resume'

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

// 加载简历列表
onMounted(async () => {
  try { resumes.value = await resumeApi.list() } catch { /* 空列表 */ }
})

async function startInterview() {
  if (!setupForm.jdText || !setupForm.direction) {
    ElMessage.warning('请填写岗位JD并选择技术方向')
    return
  }
  creating.value = true
  try {
    const data = await interviewApi.create(
      setupForm.resumeId, setupForm.jdText, setupForm.direction, setupForm.questionCount
    )
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

// ===== 输入草稿防丢失（localStorage） =====
let draftKey = ''
let draftTimer = null

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

// 全部答完 → 自动提交评估
watch(allDone, (val) => {
  if (val) setTimeout(() => submitEvaluation(), 1500)
})

function scrollChat() {
  nextTick(() => { if (chatBox.value) chatBox.value.scrollTop = chatBox.value.scrollHeight })
}

function resetChat() {
  clearDraft()
  draftKey = ''
  sessionId.value = null
  chatMessages.value = []
  allDone.value = false
  currentProgress.value = 0
  currentQuestionNumber.value = 0
}

// ========== Phase 3: Report ==========
const showReport = ref(false)
const evaluating = ref(false)
const loadingReport = ref(false)
const reportData = ref(null)
const reportAnswers = ref([])
let pollTimer = null

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

function startPolling() {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    try {
      const data = await interviewApi.getEvaluation(sessionId.value)
      if (data.sessionStatus === 'EVALUATED') {
        clearDraft()
        clearInterval(pollTimer)
        pollTimer = null
        loadingReport.value = false
        reportData.value = data

        // 加载对应的回答列表
        try {
          const detail = await interviewApi.detail(sessionId.value)
          reportAnswers.value = detail.answers || []
        } catch { reportAnswers.value = [] }
      } else if (data.sessionStatus === 'FAILED') {
        clearInterval(pollTimer)
        pollTimer = null
        loadingReport.value = false
        ElMessage.error('评估失败: ' + (data.error || '请稍后重试'))
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

function resetAll() {
  clearInterval(pollTimer)
  pollTimer = null
  resetChat()
  showReport.value = false
  reportData.value = null
  loadingReport.value = false
  reportAnswers.value = []
  setupForm.jdText = ''
  setupForm.direction = ''
}

// 清理轮询定时器
onBeforeUnmount(() => { if (pollTimer) clearInterval(pollTimer) })
</script>
