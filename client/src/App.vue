<template>
  <div class="app-wrapper">
    <div class="header">
      <div class="header-content">
        <div class="logo">
          <span class="logo-icon">📊</span>
          <span class="logo-text">Excel To DB</span>
        </div>
        <div class="header-tag">
          <el-tag type="success" effect="dark" round>数据导入工具</el-tag>
        </div>
      </div>
    </div>

    <div class="app-container">
      <div class="steps-card">
        <el-steps :active="stepsActive" finish-status="success" align-center class="steps">
          <el-step title="选择数据库" description="选择目标数据库" />
          <el-step title="上传文件" description="上传Excel/CSV" />
          <el-step title="数据预览" description="确认数据内容" />
          <el-step title="选择表" description="选择目标表" />
          <el-step title="导入数据" description="执行导入" />
        </el-steps>
      </div>

      <div class="content-card">
        <transition name="fade-slide" mode="out-in">
          <DbSelector v-if="currentStep === 0" @next="handleDbNext" />
          <FileUpload v-else-if="currentStep === 1" @next="handleUploadNext" @back="handleUploadStepBack" />
          <DataPreview v-else-if="currentStep === 2" @next="handlePreviewNext" @back="currentStep--" />
          <TableRecommend v-else-if="currentStep === 3" @next="handleTableNext" @back="currentStep--" />
          <ImportProgress
            v-else-if="currentStep === 4"
            :params="importParams"
            @done="handleImportDone"
            @reset="handleReset"
            @back="currentStep = 3"
          />
        </transition>
      </div>
    </div>

    <div class="footer">
      <p>Excel To DB 数据导入工具 · 支持 .xlsx .xls .csv</p>
    </div>
  </div>
</template>

<script setup>
import { ref, provide, computed } from 'vue'
import DbSelector from './components/DbSelector.vue'
import FileUpload from './components/FileUpload.vue'
import DataPreview from './components/DataPreview.vue'
import TableRecommend from './components/TableRecommend.vue'
import ImportProgress from './components/ImportProgress.vue'

const currentStep = ref(0)
const importDone = ref(false)
const selectedDb = ref(null)
const uploadedFile = ref(null)
const previewData = ref(null)
const selectedTable = ref(null)
const importParams = ref({})

provide('selectedDb', selectedDb)
provide('uploadedFile', uploadedFile)
provide('previewData', previewData)
provide('selectedTable', selectedTable)

const stepsActive = computed(() => {
  // el-steps: active 大于最后一个索引时会显示“全部完成”态
  return importDone.value ? 5 : currentStep.value
})

const handleDbNext = (db) => {
  selectedDb.value = db
  currentStep.value = 1
}

/** 从上传页返回选库：清空文件与后续步骤状态，避免换库后误恢复旧上传 */
const handleUploadStepBack = () => {
  currentStep.value = 0
  uploadedFile.value = null
  previewData.value = null
  selectedTable.value = null
  importParams.value = {}
}

const handleUploadNext = (file) => {
  uploadedFile.value = file
  currentStep.value = 2
}

const handlePreviewNext = () => {
  currentStep.value = 3
}

const handleTableNext = (table) => {
  selectedTable.value = table
  importParams.value = {
    filename: uploadedFile.value.filename,
    databaseId: selectedDb.value.id,
    tableName: table.name,
    importMode: table.importMode || 'INCREMENTAL',
    conflictStrategy: table.conflictStrategy || 'ERROR',
    sheetIndex: uploadedFile.value.sheetIndex ?? 0
  }
  importDone.value = false
  currentStep.value = 4
}

const handleImportDone = () => {
  importDone.value = true
}

const handleReset = () => {
  currentStep.value = 0
  importDone.value = false
  selectedDb.value = null
  uploadedFile.value = null
  previewData.value = null
  selectedTable.value = null
  importParams.value = {}
}
</script>

<style>
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap');

* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: 'Inter', 'Microsoft YaHei', Arial, sans-serif;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  min-height: 100vh;
}

.app-wrapper {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.header {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(10px);
  box-shadow: 0 2px 20px rgba(0, 0, 0, 0.1);
  position: sticky;
  top: 0;
  z-index: 100;
}

.header-content {
  max-width: 1200px;
  margin: 0 auto;
  padding: 16px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
}

.logo-icon {
  font-size: 28px;
  color: #667eea;
}

.logo-text {
  font-size: 22px;
  font-weight: 700;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.app-container {
  max-width: 1200px;
  width: 100%;
  margin: 0 auto;
  padding: 30px 20px;
  flex: 1;
}

.steps-card {
  background: rgba(255, 255, 255, 0.98);
  border-radius: 16px;
  padding: 30px;
  margin-bottom: 24px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.15);
}

.content-card {
  background: rgba(255, 255, 255, 0.98);
  border-radius: 16px;
  padding: 40px;
  min-height: 450px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.15);
}

.footer {
  text-align: center;
  padding: 20px;
  color: rgba(255, 255, 255, 0.7);
  font-size: 13px;
}

/* 过渡动画 */
.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: all 0.3s ease;
}

.fade-slide-enter-from {
  opacity: 0;
  transform: translateX(20px);
}

.fade-slide-leave-to {
  opacity: 0;
  transform: translateX(-20px);
}

/* 自定义步骤条样式 */
:deep(.el-step__title) {
  font-weight: 600;
}

:deep(.el-step__description) {
  font-size: 12px;
  color: #909399;
}

:deep(.el-steps--center) {
  justify-content: center;
}
</style>
