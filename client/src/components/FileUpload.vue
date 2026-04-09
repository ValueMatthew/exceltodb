<template>
  <div class="file-upload">
    <h2>上传数据文件</h2>
    <p class="tip">支持 .xlsx、.xls、.csv 格式，最大 100MB</p>

    <el-upload
      ref="uploadRef"
      class="upload-area"
      drag
      :action="uploadUrl"
      :auto-upload="false"
      :on-change="handleFileChange"
      :on-remove="handleFileRemove"
      :limit="1"
      accept=".xlsx,.xls,.csv"
    >
      <el-icon class="upload-icon"><upload-filled /></el-icon>
      <div class="upload-text">
        <span>将文件拖到此处，或<em>点击上传</em></span>
      </div>
      <template #tip>
        <div class="el-upload__tip">单个文件不超过100MB</div>
      </template>
    </el-upload>

    <div v-if="fileInfo" class="file-info">
      <el-descriptions title="文件信息" :column="2" border>
        <el-descriptions-item label="文件名">{{ fileInfo.name }}</el-descriptions-item>
        <el-descriptions-item label="文件大小">{{ formatSize(fileInfo.size) }}</el-descriptions-item>
        <el-descriptions-item label="文件类型">{{ fileInfo.type || 'Unknown' }}</el-descriptions-item>
      </el-descriptions>
    </div>

    <div v-if="parseResult" class="parse-result">
      <el-alert type="success" :closable="false">
        <template #title>
          解析成功！Sheet: {{ parseResult.sheetName }}, 行数: {{ parseResult.rowCount }}
        </template>
      </el-alert>
    </div>

    <div v-if="parseError" class="parse-error">
      <el-alert type="error" :closable="false">
        <template #title>{{ parseError }}</template>
      </el-alert>
    </div>

    <div class="actions">
      <el-button @click="$emit('back')">上一步</el-button>
      <el-button type="primary" @click="handleNext" :disabled="!canProceed">
        下一步
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, inject } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'

const emit = defineEmits(['next', 'back'])
const uploadedFile = inject('uploadedFile')

const uploadRef = ref(null)
const uploadUrl = '/api/upload'
const currentFile = ref(null)
const fileInfo = ref(null)
const parseResult = ref(null)
const parseError = ref(null)

const canProceed = ref(false)

const handleFileChange = async (file) => {
  currentFile.value = file.raw
  fileInfo.value = {
    name: file.name,
    size: file.size,
    type: file.raw.type
  }
  parseError.value = null
  parseResult.value = null
  canProceed.value = false

  // 自动上传并解析
  const formData = new FormData()
  formData.append('file', file.raw)

  try {
    const res = await axios.post(uploadUrl, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    parseResult.value = res.data
    canProceed.value = true
  } catch (err) {
    parseError.value = err.response?.data?.message || '文件解析失败'
    ElMessage.error(parseError.value)
  }
}

const handleFileRemove = () => {
  currentFile.value = null
  fileInfo.value = null
  parseResult.value = null
  parseError.value = null
  canProceed.value = false
}

const formatSize = (bytes) => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
}

const handleNext = () => {
  if (parseResult.value) {
    uploadedFile.value = {
      filename: parseResult.value.filename,
      ...parseResult.value
    }
    emit('next', uploadedFile.value)
  }
}
</script>

<style scoped>
.file-upload {
  max-width: 800px;
  margin: 0 auto;
}

h2 {
  margin-bottom: 10px;
  color: #303133;
}

.tip {
  color: #909399;
  margin-bottom: 30px;
}

.upload-area {
  margin-bottom: 20px;
}

.upload-icon {
  font-size: 67px;
  color: #8c939d;
  margin-bottom: 16px;
}

.upload-text {
  color: #606266;
}

.upload-text em {
  color: #409eff;
  font-style: normal;
}

.file-info {
  margin-top: 20px;
}

.parse-result,
.parse-error {
  margin-top: 20px;
}

.actions {
  margin-top: 30px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
