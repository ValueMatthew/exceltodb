<template>
  <div class="app-container">
    <el-steps :active="currentStep" finish-status="success" align-center class="steps">
      <el-step title="选择数据库" />
      <el-step title="上传文件" />
      <el-step title="数据预览" />
      <el-step title="选择表" />
      <el-step title="导入数据" />
    </el-steps>

    <div class="content">
      <DbSelector v-if="currentStep === 0" @next="handleDbNext" />
      <FileUpload v-else-if="currentStep === 1" @next="handleUploadNext" />
      <DataPreview v-else-if="currentStep === 2" @next="handlePreviewNext" @back="currentStep--" />
      <TableRecommend v-else-if="currentStep === 3" @next="handleTableNext" @back="currentStep--" />
      <ImportProgress v-else-if="currentStep === 4" :params="importParams" />
    </div>
  </div>
</template>

<script setup>
import { ref, provide } from 'vue'
import DbSelector from './components/DbSelector.vue'
import FileUpload from './components/FileUpload.vue'
import DataPreview from './components/DataPreview.vue'
import TableRecommend from './components/TableRecommend.vue'
import ImportProgress from './components/ImportProgress.vue'

const currentStep = ref(0)
const selectedDb = ref(null)
const uploadedFile = ref(null)
const previewData = ref(null)
const selectedTable = ref(null)
const importParams = ref({})

provide('selectedDb', selectedDb)
provide('uploadedFile', uploadedFile)
provide('previewData', previewData)
provide('selectedTable', selectedTable)

const handleDbNext = (db) => {
  selectedDb.value = db
  currentStep.value = 1
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
    conflictStrategy: table.conflictStrategy || 'ERROR'
  }
  currentStep.value = 4
}
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: 'Microsoft YaHei', Arial, sans-serif;
  background: #f5f7fa;
  min-height: 100vh;
}

.app-container {
  max-width: 1200px;
  margin: 0 auto;
  padding: 40px 20px;
}

.steps {
  margin-bottom: 40px;
  background: #fff;
  padding: 20px;
  border-radius: 8px;
}

.content {
  background: #fff;
  padding: 30px;
  border-radius: 8px;
  min-height: 400px;
}
</style>
