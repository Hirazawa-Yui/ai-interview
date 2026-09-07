<template>
  <div>
    <h1 style="margin-bottom: 24px">💚 系统状态</h1>
    <el-card>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="应用名称">AI-Interview</el-descriptions-item>
        <el-descriptions-item label="前端框架">Vue 3 + Element Plus</el-descriptions-item>
        <el-descriptions-item label="后端状态">
          <el-tag :type="backendOk ? 'success' : 'danger'">
            {{ backendOk ? '正常' : '异常' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="响应信息">{{ backendMsg }}</el-descriptions-item>
      </el-descriptions>
      <el-button style="margin-top: 16px" @click="checkHealth" :loading="loading">
        重新检测
      </el-button>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../api/request'

const backendOk = ref(false)
const backendMsg = ref('')
const loading = ref(false)

async function checkHealth() {
  loading.value = true
  try {
    const res = await request.get('/health')
    backendMsg.value = typeof res.data === 'string' ? res.data : JSON.stringify(res.data)
    backendOk.value = true
  } catch (e) {
    backendMsg.value = e.message
    backendOk.value = false
  } finally {
    loading.value = false
  }
}

onMounted(checkHealth)
</script>
