<template>
  <div class="chunk-uploader">
    <!-- 文件选择区 -->
    <el-upload
      v-if="!uploadState.md5"
      ref="fileSelectRef"
      :auto-upload="false"
      :limit="1"
      :accept="accept"
      :on-change="handleFileSelect"
      drag
    >
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">
        拖拽文件到此处 或 <em>点击选择</em>
      </div>
      <template #tip>
        <div class="el-upload__tip">
          {{ tipText }}
        </div>
      </template>
    </el-upload>

    <!-- 进度展示区 -->
    <div v-else class="upload-progress">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="文件名">{{ uploadState.fileName }}</el-descriptions-item>
        <el-descriptions-item label="文件大小">{{ formatSize(uploadState.fileSize) }}</el-descriptions-item>
        <el-descriptions-item label="MD5">{{ uploadState.md5 }}</el-descriptions-item>
        <el-descriptions-item label="分片数">{{ uploadState.totalChunks }}</el-descriptions-item>
      </el-descriptions>

      <!-- 秒传提示 -->
      <el-alert
        v-if="uploadState.completed"
        title="秒传成功！该文件已存在，无需重复上传"
        type="success"
        :closable="false"
        show-icon
        style="margin: 16px 0"
      />

      <!-- 上传进度 -->
      <div v-if="!uploadState.completed" style="margin-top: 16px">
        <el-progress
          :percentage="uploadPercent"
          :status="uploadPercent === 100 ? 'success' : ''"
          :stroke-width="20"
          striped
          striped-flow
        />
        <p style="color: var(--nt-slate); margin-top: 8px; text-align: center">
          已上传 {{ uploadState.uploadedChunks.length }} / {{ uploadState.totalChunks }} 个分片
          <template v-if="uploadState.paused">（已暂停）</template>
          <template v-if="uploading">（上传中...）</template>
        </p>

        <!-- 操作按钮 -->
        <div style="text-align: center; margin-top: 12px">
          <el-button v-if="uploading" type="warning" @click="pauseUpload">
            暂停上传
          </el-button>
          <el-button v-if="merging" type="success" loading>
            正在合并文件...
          </el-button>
        </div>
      </div>

      <!-- 结果展示 -->
      <el-alert
        v-if="uploadState.url"
        title="上传完成！"
        type="success"
        :closable="false"
        show-icon
        style="margin: 16px 0"
      >
        <template #default>
          <p style="word-break: break-all; font-size: 12px">{{ uploadState.url }}</p>
        </template>
      </el-alert>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import SparkMD5 from 'spark-md5'
import { computeFileMD5 } from '../utils/md5'
import { fileApi } from '../api/file'

const props = defineProps({
  accept: { type: String, default: '.pdf,.doc,.docx,.txt' },
  tipText: { type: String, default: '支持 PDF、Word、文本文件' }
})

const emit = defineEmits(['upload-success'])

const CHUNK_SIZE = 5 * 1024 * 1024  // 5MB per chunk
const MAX_CONCURRENT = 3            // 并发上传数

const fileSelectRef = ref(null)
const uploading = ref(false)        // 是否正在上传
const merging = ref(false)          // 是否正在合并
const abortControllers = ref([])    // 用于暂停

// 上传状态（响应式）
const uploadState = reactive({
  md5: '',
  fileName: '',
  fileSize: 0,
  totalChunks: 0,
  uploadId: '',
  uploadedChunks: [],
  completed: false,
  paused: false,
  url: ''
})

// 文件引用（切片用）
let selectedFile = null

// 上传进度百分比
const uploadPercent = computed(() => {
  if (uploadState.totalChunks === 0) return 0
  return Math.round((uploadState.uploadedChunks.length / uploadState.totalChunks) * 100)
})

// ========== 1. 文件选择 → 计算 MD5 → check ==========
async function handleFileSelect(uploadFile) {
  const file = uploadFile.raw
  if (!file) return

  selectedFile = file
  uploadState.fileName = file.name
  uploadState.fileSize = file.size
  uploadState.totalChunks = Math.ceil(file.size / CHUNK_SIZE)

  ElMessage.info('正在计算文件 MD5...')
  const md5 = await computeFileMD5(file)
  uploadState.md5 = md5

  // 调后端 check
  ElMessage.info('正在检查上传状态...')
  try {
    const res = await fileApi.check(md5, file.name, file.size, uploadState.totalChunks)
    uploadState.uploadId = res.uploadId
    uploadState.uploadedChunks = res.uploadedChunks || []
    uploadState.completed = res.completed
    if (res.url) uploadState.url = res.url
    if (res.completed) {
      ElMessage.success('秒传！文件已存在')
    } else if (uploadState.uploadedChunks.length > 0) {
      ElMessage.info(`检测到断点续传，已跳过 ${uploadState.uploadedChunks.length} 个分片`)
    }
  } catch (e) {
    ElMessage.error('检查上传状态失败: ' + e.message)
    return
  }

  // 秒传：直接触发成功事件
  if (uploadState.completed) {
    emit('upload-success', {
      md5: uploadState.md5,
      url: uploadState.url || '',
      fileName: uploadState.fileName
    })
    return
  }

  // 自动开始上传 → 完成后自动合并
  try {
    await startUpload()
    if (uploadState.uploadedChunks.length >= uploadState.totalChunks) {
      await doMerge()
    }
  } catch (e) {
    // 用户暂停或出错，静默处理
  }
}

// ========== 2. 并发上传分片 ==========
async function startUpload() {
  uploading.value = true
  uploadState.paused = false
  abortControllers.value = []

  // 构建待传分片列表（排除已传的）
  const pendingChunks = []
  for (let i = 0; i < uploadState.totalChunks; i++) {
    if (!uploadState.uploadedChunks.includes(i)) {
      pendingChunks.push(i)
    }
  }

  if (pendingChunks.length === 0) {
    ElMessage.warning('所有分片已上传')
    uploading.value = false
    return
  }

  // 并发控制：每次最多发 MAX_CONCURRENT 个
  const controller = new AbortController()
  abortControllers.value.push(controller)

  let activeCount = 0
  let cursor = 0

  return new Promise((resolve, reject) => {
    function launchNext() {
      while (activeCount < MAX_CONCURRENT && cursor < pendingChunks.length) {
        if (uploadState.paused || controller.signal.aborted) break

        const chunkIndex = pendingChunks[cursor++]
        activeCount++
        uploadChunk(chunkIndex, controller.signal)
          .then(() => {
            activeCount--
            // 检查是否全部完成
            if (uploadState.uploadedChunks.length >= uploadState.totalChunks) {
              uploading.value = false
              resolve()
            } else {
              launchNext()
            }
          })
          .catch((err) => {
            if (err.name !== 'AbortError' && err.name !== 'CanceledError') {
              ElMessage.error(`分片 ${chunkIndex} 上传失败: ${err.message}`)
              uploading.value = false
              reject(err)
            }
          })
      }
    }
    launchNext()
  })
}

async function uploadChunk(chunkIndex, signal) {
  const start = chunkIndex * CHUNK_SIZE
  const end = Math.min(start + CHUNK_SIZE, selectedFile.size)
  const chunkBlob = selectedFile.slice(start, end)

  // 计算分片 MD5
  const chunkMd5 = await computeChunkMD5(chunkBlob)

  // 上传
  await fileApi.uploadChunk(uploadState.md5, chunkIndex, chunkMd5, chunkBlob)

  // 更新已传列表
  if (!uploadState.uploadedChunks.includes(chunkIndex)) {
    uploadState.uploadedChunks.push(chunkIndex)
  }
}

function computeChunkMD5(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = (e) => {
      const spark = new SparkMD5.ArrayBuffer()
      spark.append(e.target.result)
      resolve(spark.end())
    }
    reader.onerror = reject
    reader.readAsArrayBuffer(blob)
  })
}

// ========== 3. 暂停 ==========
function pauseUpload() {
  uploadState.paused = true
  uploading.value = false
  abortControllers.value.forEach(c => c.abort())
  ElMessage.info('已暂停上传，可继续')
}

// ========== 4. 合并 ==========
async function doMerge() {
  merging.value = true
  try {
    const url = await fileApi.merge(uploadState.md5, uploadState.fileName)
    uploadState.url = url
    ElMessage.success('文件合并完成！')
    emit('upload-success', { md5: uploadState.md5, url, fileName: uploadState.fileName })
  } catch (e) {
    ElMessage.error('合并失败: ' + e.message)
  } finally {
    merging.value = false
  }
}

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

onBeforeUnmount(() => {
  abortControllers.value.forEach(c => c.abort())
})
</script>

<style scoped>
.chunk-uploader {
  width: 100%;
}
.upload-progress {
  background: #fff;
  padding: 20px;
  border-radius: 8px;
}
</style>
