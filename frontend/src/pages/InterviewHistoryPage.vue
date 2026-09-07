<template>
  <div>
    <h1 style="margin-bottom: 24px">📋 面试记录</h1>

    <el-card v-if="sessions.length === 0 && !loading">
      <el-empty description="暂无面试记录">
        <el-button type="primary" @click="$router.push('/interview')">去面试</el-button>
      </el-empty>
    </el-card>

    <el-table v-else :data="sessions" stripe v-loading="loading" @row-click="showDetail" highlight-current-row>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="direction" label="方向" width="90">
        <template #default="{ row }">
          <el-tag size="small">{{ directionLabel(row.direction) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="questionCount" label="题数" width="70" />
      <el-table-column prop="currentQuestion" label="进度" width="90">
        <template #default="{ row }">{{ row.currentQuestion }}/{{ row.questionCount }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" min-width="160">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click.stop="showDetail(row)">详情</el-button>
          <el-button v-if="row.status === 'EVALUATED'" size="small" text type="success"
            @click.stop="viewReport(row)">报告</el-button>
          <el-button size="small" text type="danger" @click.stop="doDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- ========== 详情弹窗 ========== -->
    <el-dialog v-model="detailVisible" title="面试详情" width="800px" destroy-on-close>
      <template v-if="detailData">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 16px">
          <el-descriptions-item label="ID">{{ detailData.id }}</el-descriptions-item>
          <el-descriptions-item label="方向">
            <el-tag size="small">{{ directionLabel(detailData.direction) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="题数">{{ detailData.questionCount }}</el-descriptions-item>
          <el-descriptions-item label="进度">{{ detailData.currentQuestion }}/{{ detailData.questionCount }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(detailData.status)">{{ statusLabel(detailData.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(detailData.createdAt) }}</el-descriptions-item>
        </el-descriptions>

        <!-- Q&A列表 -->
        <h4 v-if="detailData.answers && detailData.answers.length" style="margin: 12px 0 8px">问答记录</h4>
        <el-collapse v-if="detailData.answers && detailData.answers.length">
          <el-collapse-item v-for="a in detailData.answers" :key="a.questionNumber"
            :title="'第' + a.questionNumber + '题'">
            <p style="color: #6b7280; font-size: 13px; margin-bottom: 4px"><strong>题目：</strong>{{ a.questionText }}</p>
            <p style="background: #f3f4f6; padding: 8px; border-radius: 4px; font-size: 13px; white-space: pre-wrap">
              <strong>回答：</strong>{{ a.answerText }}
            </p>
          </el-collapse-item>
        </el-collapse>

        <!-- 评估结果（如果有） -->
        <template v-if="detailData.status === 'EVALUATED' || detailData.status === 'EVALUATING'">
          <h4 style="margin: 16px 0 8px">评估结果</h4>
          <el-button type="success" @click="viewReport({ id: detailData.id }); detailVisible = false">
            查看完整报告
          </el-button>
        </template>
      </template>
    </el-dialog>

    <!-- ========== 报告弹窗 ========== -->
    <el-dialog v-model="reportVisible" title="评估报告" width="800px" destroy-on-close>
      <div v-if="reportLoading" style="text-align: center; padding: 40px">
        <el-progress :percentage="100" :indeterminate="true" />
        <p style="margin-top: 16px; color: #6b7280">加载中...</p>
      </div>
      <div v-else-if="reportData">
        <div style="text-align: center; margin-bottom: 20px">
          <el-progress type="dashboard" :percentage="reportData.overallScore || 0"
            :color="scoreColor(reportData.overallScore)" :stroke-width="12" />
          <p style="margin-top: 8px; color: #6b7280">{{ reportData.summary || '暂无综合评价' }}</p>
        </div>
        <el-collapse v-if="reportData.perQuestion && reportData.perQuestion.length">
          <el-collapse-item v-for="(q, i) in reportData.perQuestion" :key="i"
            :title="'第' + (q.questionNumber || (i+1)) + '题 — ' + (q.score || 0) + '分'">
            <p style="color: #6b7280">{{ q.comment }}</p>
          </el-collapse-item>
        </el-collapse>
        <div v-if="reportData.strengths && reportData.strengths.length" style="margin-top: 12px">
          <strong>✅ 优势：</strong>
          <el-tag v-for="(s, i) in reportData.strengths" :key="i" type="success" effect="plain"
            style="margin: 4px; display: block; text-align: left; white-space: pre-wrap; height: auto; padding: 4px 8px">{{ s }}</el-tag>
        </div>
        <div v-if="reportData.improvements && reportData.improvements.length" style="margin-top: 12px">
          <strong>📝 改进建议：</strong>
          <div v-for="(imp, i) in reportData.improvements" :key="i"
            style="margin: 8px 0; padding: 8px; background: #fffbeb; border-left: 3px solid #f59e0b; border-radius: 4px">
            <el-tag size="small" type="warning">{{ imp.category }}</el-tag>
            <p style="margin: 4px 0; font-size: 13px"><strong>问题：</strong>{{ imp.issue }}</p>
            <p style="margin: 0; font-size: 13px; color: #059669"><strong>建议：</strong>{{ imp.suggestion }}</p>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { interviewApi } from '../api/interview'

const sessions = ref([])
const loading = ref(false)
const detailVisible = ref(false)
const detailData = ref(null)
const reportVisible = ref(false)
const reportData = ref(null)
const reportLoading = ref(false)

// 加载列表
onMounted(() => loadSessions())

async function loadSessions() {
  loading.value = true
  try { sessions.value = await interviewApi.list() }
  catch { ElMessage.error('加载面试记录失败') }
  finally { loading.value = false }
}

// 查看详情
async function showDetail(row) {
  try {
    detailData.value = await interviewApi.detail(row.id)
    detailVisible.value = true
  } catch { ElMessage.error('加载详情失败') }
}

// 查看报告
async function viewReport(row) {
  reportVisible.value = true
  reportLoading.value = true
  reportData.value = null
  try {
    reportData.value = await interviewApi.getEvaluation(row.id)
  } catch { ElMessage.error('加载报告失败') }
  finally { reportLoading.value = false }
}

// 删除
async function doDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除面试记录 #${row.id} 吗？`, '确认删除', { type: 'warning' })
    await interviewApi.delete(row.id)
    ElMessage.success('已删除')
    loadSessions()
  } catch (e) { if (e !== 'cancel') ElMessage.error('删除失败') }
}

// ========== 工具函数 ==========
function directionLabel(d) {
  return { frontend: '前端', backend: '后端', test: '测试', algorithm: '算法', data: '数据', devops: '运维', fullstack: '全栈' }[d] || d
}
function statusType(s) {
  return { CREATED: 'info', IN_PROGRESS: 'warning', COMPLETED: 'success', EVALUATING: '', EVALUATED: 'success', FAILED: 'danger' }[s] || 'info'
}
function statusLabel(s) {
  return { CREATED: '未开始', IN_PROGRESS: '进行中', COMPLETED: '已完成', EVALUATING: '评估中', EVALUATED: '已评估', FAILED: '失败' }[s] || s
}
function formatTime(t) { return t ? t.replace('T', ' ').substring(0, 19) : '' }
function scoreColor(s) {
  if (!s) return '#909399'
  if (s >= 80) return '#67c23a'
  if (s >= 60) return '#e6a23c'
  return '#f56c6c'
}
</script>
