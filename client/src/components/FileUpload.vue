<template>
  <div class="file-upload">
    <div v-if="overlayVisible" class="loading-overlay" role="status" aria-live="polite">
      <div class="loading-card">
        <div class="loading-spinner" />
        <div class="loading-title">{{ overlayTitle }}</div>
        <div class="loading-subtitle">文件较大时可能需要一些时间，请稍候…</div>
        <div class="loading-actions">
          <el-button v-if="uploading" type="danger" @click="cancelUpload">取消上传</el-button>
        </div>
      </div>
    </div>

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
      v-model:file-list="uploadFileList"
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
        <div v-if="showSheetPicker" class="sheet-picker">
          <p class="sheet-picker-label">该工作簿包含多个 Sheet，请选择要导入的工作表：</p>
          <el-radio-group
            v-model="selectedSheetIndex"
            class="sheet-radio-group"
            @change="onSheetIndexChange"
          >
            <el-radio
              v-for="s in parseResult.sheets"
              :key="s.index"
              :label="s.index"
              border
              class="sheet-radio"
            >
              <span class="sheet-radio-title">{{ s.name }}</span>
              <span class="sheet-radio-meta">（约 {{ s.rowCount }} 行）</span>
            </el-radio>
          </el-radio-group>
        </div>
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
        <p v-if="sheetSwitchLoading" class="sheet-switch-hint">正在切换工作表…</p>
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
        :disabled="!canProceed || uploading || sheetSwitchLoading"
        class="next-btn"
      >
        下一步 →
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, inject, computed, onMounted } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const emit = defineEmits(['next', 'back'])
const uploadedFile = inject('uploadedFile')

const uploadRef = ref(null)
const uploadFileList = ref([])
const uploadUrl = '/api/upload'
const currentFile = ref(null)
const fileInfo = ref(null)
const parseResult = ref(null)
const parseError = ref(null)
const canProceed = ref(false)
const uploading = ref(false)
const selectedSheetIndex = ref(0)
const sheetSwitchLoading = ref(false)
let uploadSeq = 0
let abortController = null

const showSheetPicker = computed(() => {
  const sheets = parseResult.value?.sheets
  return Array.isArray(sheets) && sheets.length > 1
})

const overlayVisible = computed(() => uploading.value || sheetSwitchLoading.value)
const overlayTitle = computed(() => {
  if (uploading.value) return '正在上传并解析…'
  if (sheetSwitchLoading.value) return '正在切换工作表…'
  return '处理中…'
})

const resetUploadState = () => {
  parseError.value = null
  parseResult.value = null
  canProceed.value = false
  uploadedFile.value = null
  selectedSheetIndex.value = 0
  sheetSwitchLoading.value = false
  uploadFileList.value = []
}

const displayNameFromServerFilename = (serverName) => {
  if (!serverName) return ''
  const i = serverName.indexOf('_')
  return i > 0 ? serverName.slice(i + 1) : serverName
}

/** 从预览等步骤返回时：父级仍保留 uploadedFile，恢复本页 UI */
const hydrateFromParent = () => {
  const uf = uploadedFile.value
  if (!uf?.filename || !uf.columns?.length) return

  const displayName = uf.clientFileName || displayNameFromServerFilename(uf.filename)
  const size = typeof uf.clientFileSize === 'number' ? uf.clientFileSize : 0
  fileInfo.value = {
    name: displayName,
    size,
    type: ''
  }
  parseResult.value = {
    filename: uf.filename,
    sheetName: uf.sheetName,
    rowCount: uf.rowCount,
    columns: uf.columns,
    sheetIndex: uf.sheetIndex ?? 0,
    sheets: uf.sheets
  }
  selectedSheetIndex.value = uf.sheetIndex ?? 0
  canProceed.value = true
  uploadFileList.value = [
    {
      name: displayName,
      size,
      status: 'success',
      uid: Date.now()
    }
  ]
}

onMounted(() => {
  hydrateFromParent()
})

const cancelUpload = () => {
  // Bump sequence so any late responses are ignored.
  uploadSeq++
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  uploading.value = false
  sheetSwitchLoading.value = false
  parseError.value = null
  parseResult.value = null
  canProceed.value = false
  uploadedFile.value = null
  currentFile.value = null
  fileInfo.value = null
  uploadFileList.value = []
  ElMessage.info('已取消上传')
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
    selectedSheetIndex.value = res.data.sheetIndex ?? 0
    canProceed.value = true
    uploadedFile.value = {
      filename: res.data?.filename,
      ...res.data,
      clientFileName: file.name,
      clientFileSize: file.size
    }
    uploadFileList.value = [
      {
        name: file.name,
        size: file.size,
        status: 'success',
        uid: file.uid,
        raw: file.raw
      }
    ]
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
  selectedSheetIndex.value = 0
  sheetSwitchLoading.value = false
  uploadFileList.value = []
}

const onSheetIndexChange = (index) => {
  loadPreviewForSheet(Number(index))
}

const loadPreviewForSheet = async (sheetIndex) => {
  const fn = uploadedFile.value?.filename || parseResult.value?.filename
  if (!fn) return
  sheetSwitchLoading.value = true
  try {
    const res = await axios.get(`/api/preview/${encodeURIComponent(fn)}`, {
      params: { sheetIndex, maxRows: 100 }
    })
    const sheets = parseResult.value?.sheets
    parseResult.value = {
      ...parseResult.value,
      sheetIndex: res.data.sheetIndex ?? sheetIndex,
      sheetName: res.data.sheetName,
      rowCount: res.data.totalRows,
      columns: res.data.columns,
      sheets
    }
    uploadedFile.value = {
      ...uploadedFile.value,
      filename: fn,
      sheetIndex: res.data.sheetIndex ?? sheetIndex,
      sheetName: res.data.sheetName,
      rowCount: res.data.totalRows,
      columns: res.data.columns,
      sheets,
      clientFileName: uploadedFile.value?.clientFileName,
      clientFileSize: uploadedFile.value?.clientFileSize
    }
  } catch (err) {
    ElMessage.error(err.response?.data?.message || '加载所选工作表失败')
  } finally {
    sheetSwitchLoading.value = false
  }
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
  position: relative;
}

.loading-overlay {
  position: absolute;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.75);
  backdrop-filter: blur(2px);
  border-radius: 16px;
}

.loading-card {
  width: min(420px, 92%);
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid #ebeef5;
  border-radius: 16px;
  padding: 22px 18px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.12);
  text-align: center;
}

.loading-spinner {
  width: 44px;
  height: 44px;
  margin: 0 auto 12px auto;
  border-radius: 999px;
  border: 4px solid rgba(102, 126, 234, 0.22);
  border-top-color: #667eea;
  animation: spin 0.9s linear infinite;
}

.loading-title {
  font-size: 16px;
  font-weight: 700;
  color: #303133;
  margin-bottom: 6px;
}

.loading-subtitle {
  font-size: 13px;
  color: #909399;
  margin-bottom: 14px;
}

.loading-actions {
  display: flex;
  justify-content: center;
  gap: 10px;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
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

.sheet-picker {
  margin-bottom: 16px;
  padding: 16px;
  background: #f5f7ff;
  border-radius: 12px;
  border: 1px solid #e4e7ed;
}

.sheet-picker-label {
  margin: 0 0 12px 0;
  font-size: 14px;
  color: #606266;
  font-weight: 500;
}

.sheet-radio-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
}

.sheet-radio {
  margin-right: 0;
  width: 100%;
}

.sheet-radio-title {
  font-weight: 600;
  color: #303133;
}

.sheet-radio-meta {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}

.sheet-switch-hint {
  margin-top: 8px;
  font-size: 13px;
  color: #909399;
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
