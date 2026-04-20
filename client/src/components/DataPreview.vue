<template>
  <div class="data-preview">
    <div class="section-header">
      <div class="section-icon preview-icon-bg">
        <span class="icon-text">📊</span>
      </div>
      <div>
        <h2>数据预览</h2>
        <p class="subtitle">请确认列名和数据内容正确无误后继续</p>
      </div>
    </div>

    <div v-if="loading" class="loading-state">
      <el-icon class="is-loading"><loading /></el-icon>
      <span>正在加载预览数据...</span>
    </div>

    <div v-else-if="previewData" class="preview-container">
      <div class="info-bar">
        <el-card shadow="never" class="info-card">
          <div class="info-items">
            <div class="info-item">
              <span class="info-icon">📄</span>
              <span class="info-label">文件名</span>
              <span class="info-value">{{ previewData.filename }}</span>
            </div>
            <div class="info-item">
              <span class="info-icon">📑</span>
              <span class="info-label">Sheet</span>
              <el-tag size="small" type="info">{{ previewData.sheetName }}</el-tag>
            </div>
            <div class="info-item">
              <span class="info-icon">📈</span>
              <span class="info-label">总行数</span>
              <el-tag size="small" type="success">{{ previewData.totalRows }}</el-tag>
            </div>
            <div class="info-item">
              <span class="info-icon">👁</span>
              <span class="info-label">预览</span>
              <el-tag size="small" type="warning">前 {{ previewData.previewRows?.length || 0 }} 行</el-tag>
            </div>
          </div>
        </el-card>
      </div>

      <div class="table-wrapper">
        <el-table
          :data="previewData.previewRows"
          border
          stripe
          :max-height="420"
          style="width: 100%"
          :header-cell-style="{ background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)', color: '#fff', fontWeight: '600' }"
        >
          <el-table-column
            v-for="(col, index) in previewData.columns"
            :key="index"
            :prop="col"
            :label="col"
            min-width="150"
            show-overflow-tooltip
          />
        </el-table>
      </div>

      <div class="column-tags">
        <span class="column-label">列名列表：</span>
        <el-tag
          v-for="(col, index) in previewData.columns"
          :key="index"
          size="small"
          effect="plain"
          class="column-tag"
        >
          {{ index + 1 }}. {{ col }}
        </el-tag>
      </div>
    </div>

    <div class="actions">
      <el-button @click="$emit('back')" size="large">上一步</el-button>
      <el-button type="primary" size="large" @click="handleNext" class="next-btn">
        下一步 →
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, inject } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

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
    const sheetIndex = uploadedFile.value?.sheetIndex ?? 0
    const res = await axios.get(`/api/preview/${encodeURIComponent(uploadedFile.value.filename)}`, {
      params: { maxRows: 100, sheetIndex }
    })
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

.preview-icon-bg {
  background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%);
  box-shadow: 0 4px 15px rgba(79, 172, 254, 0.4);
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

.loading-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #667eea;
  padding: 60px 20px;
  font-size: 16px;
}

.info-bar {
  margin-bottom: 20px;
}

.info-card {
  border: none;
  background: #fafafa;
}

.info-items {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
}

.info-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.info-icon {
  font-size: 14px;
}

.info-label {
  color: #909399;
}

.info-value {
  color: #303133;
  font-weight: 500;
}

.table-wrapper {
  margin: 20px 0;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid #ebeef5;
}

.column-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  padding: 16px;
  background: #fafafa;
  border-radius: 8px;
}

.column-label {
  font-size: 13px;
  color: #606266;
  font-weight: 500;
}

.column-tag {
  margin: 2px;
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
