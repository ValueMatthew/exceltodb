<template>
  <div class="file-upload">
    <div class="section-header">
      <div class="section-icon upload-icon-bg">
        <span class="icon-text">📤</span>
      </div>
      <div>
        <h2>上传数据文件</h2>
        <p class="subtitle">支持 .xlsx、.xls、.csv 格式，单个文件最大 100MB</p>
      </div>
    </div>

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
      <div class="upload-content">
        <div class="upload-icon-wrapper">
          <span class="upload-icon-large">📁</span>
        </div>
        <div class="upload-text">
          <span class="primary-text">将文件拖到此处</span>
          <span class="secondary-text">或<em>点击上传</em></span>
        </div>
        <div class="upload-tips">
          <el-tag size="small" effect="plain">.xlsx</el-tag>
          <el-tag size="small" effect="plain">.xls</el-tag>
          <el-tag size="small" effect="plain">.csv</el-tag>
        </div>
      </div>
    </el-upload>

    <transition name="el-fade-in">
      <div v-if="fileInfo" class="file-info-card">
        <el-card shadow="hover" class="info-card">
          <template #header>
            <div class="card-header">
              <span class="file-icon">📄</span>
              <span class="file-name">{{ fileInfo.name }}</span>
            </div>
          </template>
          <div class="file-meta">
            <div class="meta-item">
              <span class="meta-label">文件大小</span>
              <span class="meta-value">{{ formatSize(fileInfo.size) }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">文件类型</span>
              <span class="meta-value">{{ getFileType(fileInfo.name) }}</span>
            </div>
          </div>
        </el-card>
      </div>
    </transition>

    <transition name="el-fade-in">
      <div v-if="parseResult" class="parse-result">
        <el-alert
          type="success"
          :closable="false"
          show-icon
          class="success-alert"
        >
          <template #title>
            <span class="success-text">
              ✅ 解析成功！Sheet: <strong>{{ parseResult.sheetName }}</strong>，共 <strong>{{ parseResult.rowCount }}</strong> 行数据
            </span>
          </template>
        </el-alert>
      </div>
    </transition>

    <transition name="el-fade-in">
      <div v-if="parseError" class="parse-error">
        <el-alert type="error" :closable="false" show-icon>
          <template #title>{{ parseError }}</template>
        </el-alert>
      </div>
    </transition>

    <transition name="el-fade-in">
      <div v-if="uploading" class="parse-result">
        <el-alert type="info" :closable="false" show-icon>
          <template #title>正在上传并解析，请稍候…</template>
        </el-alert>
      </div>
    </transition>

    <div class="actions">
      <el-button @click="$emit('back')" size="large">上一步</el-button>
      <el-button
        type="primary"
        size="large"
        @click="handleNext"
        :disabled="!canProceed || uploading"
        class="next-btn"
      >
        下一步 →
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, inject } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const emit = defineEmits(['next', 'back'])
const uploadedFile = inject('uploadedFile')

const uploadRef = ref(null)
const uploadUrl = '/api/upload'
const currentFile = ref(null)
const fileInfo = ref(null)
const parseResult = ref(null)
const parseError = ref(null)
const canProceed = ref(false)
const uploading = ref(false)
let uploadSeq = 0
let abortController = null

const resetUploadState = () => {
  parseError.value = null
  parseResult.value = null
  canProceed.value = false
  uploadedFile.value = null
}

const handleFileChange = async (file) => {
  // 新选择文件时，清理旧结果 & 中断上一次上传
  resetUploadState()
  uploading.value = true
  const seq = ++uploadSeq

  if (abortController) abortController.abort()
  abortController = new AbortController()

  currentFile.value = file.raw
  fileInfo.value = {
    name: file.name,
    size: file.size,
    type: file.raw.type
  }

  const formData = new FormData()
  formData.append('file', file.raw)

  try {
    const res = await axios.post(uploadUrl, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      signal: abortController.signal
    })
    if (seq !== uploadSeq) return
    parseResult.value = res.data
    canProceed.value = true
    uploadedFile.value = {
      filename: res.data?.filename,
      ...res.data
    }
    ElMessage.success(`上传成功：${fileInfo.value?.name || ''}`.trim())
  } catch (err) {
    // 主动中断不提示错误
    if (err?.name === 'CanceledError' || err?.code === 'ERR_CANCELED') return
    if (seq !== uploadSeq) return
    parseError.value = err.response?.data?.message || '文件解析失败，请检查文件格式'
    ElMessage.error(parseError.value)
  } finally {
    if (seq === uploadSeq) uploading.value = false
  }
}

const handleFileRemove = () => {
  uploadSeq++
  if (abortController) abortController.abort()
  abortController = null
  currentFile.value = null
  fileInfo.value = null
  parseResult.value = null
  parseError.value = null
  canProceed.value = false
  uploading.value = false
  uploadedFile.value = null
}

const formatSize = (bytes) => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
}

const getFileType = (filename) => {
  const ext = filename.split('.').pop().toLowerCase()
  const types = {
    xlsx: 'Excel 2007+',
    xls: 'Excel 97-2003',
    csv: 'CSV 文件'
  }
  return types[ext] || ext.toUpperCase()
}

const handleNext = () => {
  if (parseResult.value) {
    // uploadedFile 已在上传成功时写入；这里保持兼容
    if (!uploadedFile.value) {
      uploadedFile.value = {
        filename: parseResult.value.filename,
        ...parseResult.value
      }
    }
    emit('next', uploadedFile.value)
  }
}
</script>

<style scoped>
.file-upload {
  max-width: 700px;
  margin: 0 auto;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 32px;
  padding-bottom: 20px;
  border-bottom: 1px solid #f0f0f0;
}

.section-icon {
  width: 56px;
  height: 56px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
}

.upload-icon-bg {
  background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
  box-shadow: 0 4px 15px rgba(245, 87, 108, 0.4);
}

.section-header h2 {
  margin: 0 0 4px 0;
  font-size: 22px;
  color: #303133;
}

.subtitle {
  color: #909399;
  font-size: 14px;
  margin: 0;
}

.upload-area {
  width: 100%;
}

.upload-area :deep(.el-upload-dragger) {
  padding: 40px;
  border-radius: 16px;
  border: 2px dashed #dcdfe6;
  background: #fafafa;
  transition: all 0.3s;
}

.upload-area :deep(.el-upload-dragger:hover) {
  border-color: #667eea;
  background: #f5f7ff;
}

.upload-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

.upload-icon-wrapper {
  width: 80px;
  height: 80px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.upload-icon-large {
  font-size: 36px;
}

.upload-text {
  text-align: center;
}

.primary-text {
  display: block;
  font-size: 16px;
  color: #303133;
  margin-bottom: 4px;
}

.secondary-text {
  font-size: 14px;
  color: #909399;
}

.secondary-text em {
  color: #667eea;
  font-style: normal;
  font-weight: 600;
}

.upload-tips {
  display: flex;
  gap: 8px;
}

.file-info-card {
  margin-top: 24px;
}

.info-card {
  border: none;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
}

.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.file-icon {
  font-size: 20px;
}

.file-name {
  font-weight: 600;
  color: #303133;
}

.file-meta {
  display: flex;
  gap: 32px;
}

.meta-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.meta-label {
  font-size: 12px;
  color: #909399;
}

.meta-value {
  font-size: 14px;
  color: #303133;
  font-weight: 500;
}

.parse-result,
.parse-error {
  margin-top: 20px;
}

.success-alert {
  border: none;
  background: linear-gradient(135deg, rgba(103, 126, 234, 0.1) 0%, rgba(118, 75, 162, 0.1) 100%);
}

.success-text {
  display: flex;
  align-items: center;
  gap: 6px;
}

.actions {
  margin-top: 32px;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.next-btn {
  padding: 0 40px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
}
</style>
