<template>
  <div class="data-preview">
    <h2>数据预览</h2>
    <p class="tip">请确认列名正确无误后继续</p>

    <div v-if="loading" class="loading">
      <el-icon class="is-loading"><loading /></el-icon>
      <span>正在加载数据...</span>
    </div>

    <div v-else-if="previewData" class="preview-container">
      <div class="info-bar">
        <el-tag>文件名: {{ previewData.filename }}</el-tag>
        <el-tag type="info">Sheet: {{ previewData.sheetName }}</el-tag>
        <el-tag type="success">总行数: {{ previewData.totalRows }}</el-tag>
        <el-tag type="warning">预览: 前 {{ previewData.previewRows?.length || 0 }} 行</el-tag>
      </div>

      <el-table
        :data="previewData.previewRows"
        border
        stripe
        :max-height="400"
        style="width: 100%; margin-top: 20px"
      >
        <el-table-column
          v-for="(col, index) in previewData.columns"
          :key="index"
          :prop="col"
          :label="col"
          min-width="150"
        />
      </el-table>
    </div>

    <div class="actions">
      <el-button @click="$emit('back')">上一步</el-button>
      <el-button type="primary" @click="handleNext">
        下一步
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, inject } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'

const emit = defineEmits(['next', 'back'])
const uploadedFile = inject('uploadedFile')
const previewData = inject('previewData')

const loading = ref(false)

const loadPreview = async () => {
  if (!uploadedFile.value?.filename) {
    ElMessage.error('请先上传文件')
    return
  }

  loading.value = true
  try {
    const res = await axios.get(`/api/preview/${uploadedFile.value.filename}`)
    previewData.value = res.data
  } catch (err) {
    ElMessage.error('加载预览数据失败')
  } finally {
    loading.value = false
  }
}

const handleNext = () => {
  emit('next')
}

onMounted(() => {
  loadPreview()
})
</script>

<style scoped>
.data-preview {
  max-width: 1000px;
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

.loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #909399;
  padding: 40px;
}

.preview-container {
  margin-top: 20px;
}

.info-bar {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.actions {
  margin-top: 30px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
