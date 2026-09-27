<template>
  <div>
    <h1 style="margin-bottom: 24px">💚 系统状态</h1>

    <el-alert
      v-if="!loading && services.length"
      :type="allOk ? 'success' : 'error'"
      :title="allOk ? '全部服务正常' : '存在异常服务，请检查后端配置'"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    />
    <el-alert
      v-else-if="errorMsg"
      type="error"
      :title="`检测失败：${errorMsg}`"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    />

    <el-card>
      <el-table :data="services" v-loading="loading" style="width: 100%">
        <el-table-column prop="label" label="服务" width="140" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.up ? 'success' : 'danger'" size="small">
              {{ row.up ? '正常' : (row.status || '异常') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="latency" label="延迟" width="120">
          <template #default="{ row }">
            <span style="color: var(--nt-text-secondary, #787774)">{{ row.latency }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="详情" />
        <template #empty>
          <span style="color: var(--nt-text-secondary, #787774)">
            {{ loading ? '检测中…' : '暂无数据' }}
          </span>
        </template>
      </el-table>

      <div style="margin-top: 16px; display: flex; align-items: center; gap: 12px">
        <el-button @click="checkHealth(true)" :loading="loading">重新检测</el-button>
        <span style="color: var(--nt-text-secondary, #787774); font-size: 13px">
          AI-Interview · Vue 3 + Element Plus
        </span>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { healthApi } from '../api/health'

/** 后端返回的服务键 → 展示名 */
const SERVICE_LABELS = {
  app: '应用',
  llm: 'LLM 模型',
  embedding: '向量模型',
  oss: '对象存储'
}
/** 服务对象里除 status/latency 外的附加字段 → 展示名 */
const DETAIL_LABELS = {
  dimensions: '维度',
  bucket: 'Bucket'
}

const healthData = ref(null)
const errorMsg = ref('')
const loading = ref(false)

/**
 * 把后端 data 拍平成表格行。兼容两种形态：
 *  - 字符串（app: "UP"）
 *  - 对象（{status, latency, ...附加字段}），附加字段统一拼进「详情」列
 * 这样后端将来新增服务或字段，前端不用改代码就能显示出来。
 */
const services = computed(() => {
  const d = healthData.value
  if (!d || typeof d !== 'object') return []
  return Object.entries(d).map(([key, val]) => {
    const row = { key, label: SERVICE_LABELS[key] || key, up: false, status: '', latency: '—', detail: '' }
    if (val == null) return row
    if (typeof val !== 'object') {
      row.status = String(val)
      row.up = val === 'UP'
      return row
    }
    const { status, latency, ...rest } = val
    row.status = status
    row.up = status === 'UP'
    row.latency = latency || '—'
    row.detail = Object.entries(rest)
      .map(([k, v]) => `${DETAIL_LABELS[k] || k} ${v}`)
      .join(' · ')
    return row
  })
})

const allOk = computed(() => services.value.length > 0 && services.value.every(s => s.up))

async function checkHealth(force = false) {
  loading.value = true
  errorMsg.value = ''
  try {
    healthData.value = await healthApi.check(force)
  } catch (e) {
    // 拦截器已弹过 ElMessage，这里只记录用于页面提示
    errorMsg.value = e.message
    healthData.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => checkHealth(false))
</script>
