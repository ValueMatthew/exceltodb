<template>
  <div class="import-progress">
    <div class="section-header">
      <div class="section-icon" :class="statusIconBg">
        <span class="icon-text">{{ statusIcon }}</span>
      </div>
      <div>
        <h2>数据导入</h2>
        <p class="subtitle" v-if="status === 'importing'">服务端正在写入数据库，请勿关闭页面...</p>
        <p class="subtitle" v-else-if="status === 'success'">导入完成</p>
        <p class="subtitle" v-else-if="status === 'error'">导入失败</p>
        <p class="subtitle" v-else>准备开始导入...</p>
      </div>
    </div>

    <div v-if="status === 'idle' || status === 'importing'" class="progress-container">
      <div class="progress-circle-wrapper">
        <el-progress
          type="circle"
          :percentage="progress"
          :status="progressStatus"
          :stroke-width="12"
          :width="180"
        >
          <template #default="{ percentage }">
            <div class="progress-inner">
              <span class="progress-value" v-if="status === 'importing'">写入中</span>
              <span class="progress-value" v-else>{{ percentage }}%</span>
              <span class="progress-label">{{ progressLabel }}</span>
            </div>
          </template>
        </el-progress>
      </div>

      <div class="progress-info">
        <el-card shadow="never" class="info-card">
          <div class="info-grid">
            <div class="info-item">
              <span class="info-label">导入模式</span>
              <el-tag :type="params.importMode === 'INCREMENTAL' ? 'success' : 'warning'" size="small">
                {{ params.importMode === 'INCREMENTAL' ? '增量导入' : '清空导入' }}
              </el-tag>
            </div>
            <div class="info-item">
              <span class="info-label">冲突策略</span>
              <el-tag type="info" size="small">
                {{ conflictStrategyText }}
              </el-tag>
            </div>
            <div class="info-item">
              <span class="info-label">目标表</span>
              <span class="info-value">{{ params.tableName }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">文件名</span>
              <span class="info-value">{{ params.filename }}</span>
            </div>
          </div>
        </el-card>
      </div>

      <div class="status-messages">
        <transition name="el-fade-in">
          <el-alert
            v-if="detailText"
            type="info"
            :closable="false"
            show-icon
            class="status-alert"
          >
            {{ detailText }}
          </el-alert>
        </transition>
      </div>
    </div>

    <div v-else-if="status === 'success'" class="result-container">
      <el-result
        icon="success"
        title="导入成功"
        class="result-card"
      >
        <template #sub-title>
          <div class="success-info">
            <p>共导入 <strong class="highlight">{{ resultData?.importedRows || 0 }}</strong> 行数据</p>
            <p class="success-tip">数据已成功写入数据库</p>
          </div>
        </template>
        <template #extra>
          <div class="result-actions">
            <el-button type="primary" size="large" @click="handleReset" class="action-btn">
              🔄 重新导入
            </el-button>
          </div>
        </template>
      </el-result>
    </div>

    <div v-else-if="status === 'error'" class="result-container">
      <el-result
        icon="error"
        title="导入失败"
        class="result-card"
      >
        <template #sub-title>
          <div class="error-info">
            <p class="error-message">{{ errorMessage }}</p>
            <p class="error-tip">请检查数据格式或数据库连接后重试</p>
          </div>
        </template>
        <template #extra>
          <div class="result-actions">
            <el-button size="large" @click="handleReset" class="action-btn">
              🔄 重新导入
            </el-button>
            <el-button type="primary" size="large" @click="handleBack" class="action-btn">
              返回重试
            </el-button>
          </div>
        </template>
      </el-result>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const props = defineProps({
  params: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['reset', 'back', 'done'])

const status = ref('idle')
const progress = ref(0)
const detailText = ref('')
const errorMessage = ref('')
const resultData = ref(null)
const pulseTimer = ref(null)

const progressStatus = computed(() => {
  if (status.value === 'error') return 'exception'
  if (status.value === 'success') return 'success'
  return ''
})

const progressLabel = computed(() => {
  if (status.value === 'importing') return '写入数据库中'
  if (status.value === 'success') return '已完成'
  if (status.value === 'error') return '已失败'
  return '准备中'
})

const statusIcon = computed(() => {
  if (status.value === 'success') return '✅'
  if (status.value === 'error') return '❌'
  return '⏳'
})

const statusIconBg = computed(() => {
  if (status.value === 'success') return 'success-icon-bg'
  if (status.value === 'error') return 'error-icon-bg'
  return 'importing-icon-bg'
})

const conflictStrategyText = computed(() => {
  const map = {
    'ERROR': '报错',
    'UPDATE': '更新 (UPSERT)',
    'IGNORE': '忽略'
  }
  return map[props.params.conflictStrategy] || props.params.conflictStrategy
})

const startPulse = () => {
  stopPulse()
  // 仅用于“有在工作”的视觉提示，避免误导为真实进度
  progress.value = 10
  pulseTimer.value = window.setInterval(() => {
    if (status.value !== 'importing') return
    const next = progress.value + 3
    progress.value = next >= 90 ? 10 : next
  }, 300)
}

const stopPulse = () => {
  if (pulseTimer.value) {
    window.clearInterval(pulseTimer.value)
    pulseTimer.value = null
  }
}

const startImport = async () => {
  status.value = 'importing'
  detailText.value = '已提交导入请求，正在等待服务器写入完成...'
  startPulse()

  try {
    const res = await axios.post('/api/import', props.params)

    resultData.value = res.data
    stopPulse()
    progress.value = 100
    status.value = 'success'
    detailText.value = ''
    emit('done')
  } catch (err) {
    stopPulse()
    status.value = 'error'
    errorMessage.value = err.response?.data?.message || err.message || '未知错误'
    ElMessage.error('导入失败: ' + errorMessage.value)
  }
}

const handleReset = () => {
  emit('reset')
}

const handleBack = () => {
  emit('back')
}

onMounted(() => {
  startImport()
})

onBeforeUnmount(() => {
  stopPulse()
})
</script>

<style scoped>
.import-progress {
  max-width: 600px;
  margin: 0 auto;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 40px;
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

.importing-icon-bg {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  box-shadow: 0 4px 15px rgba(102, 126, 234, 0.4);
}

.success-icon-bg {
  background: linear-gradient(135deg, #67c23a 0%, #85ce61 100%);
  box-shadow: 0 4px 15px rgba(103, 194, 58, 0.4);
}

.error-icon-bg {
  background: linear-gradient(135deg, #f56c6c 0%, #e6a23c 100%);
  box-shadow: 0 4px 15px rgba(245, 108, 108, 0.4);
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

.progress-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 20px 0;
}

.progress-circle-wrapper {
  margin-bottom: 32px;
}

.progress-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.progress-value {
  font-size: 32px;
  font-weight: 700;
  color: #303133;
}

.progress-label {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.progress-info {
  width: 100%;
  margin-bottom: 20px;
}

.info-card {
  border: none;
  background: #fafafa;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.info-label {
  font-size: 12px;
  color: #909399;
}

.info-value {
  font-size: 14px;
  color: #303133;
  font-weight: 500;
}

.status-messages {
  width: 100%;
  margin-top: 16px;
}

.status-alert {
  border-radius: 8px;
}

.result-container {
  padding: 20px 0;
}

.result-card {
  border: none;
}

.success-info,
.error-info {
  text-align: center;
}

.success-info p {
  margin: 8px 0;
  font-size: 15px;
  color: #606266;
}

.highlight {
  color: #67c23a;
  font-size: 18px;
}

.success-tip {
  color: #909399 !important;
  font-size: 13px !important;
}

.error-message {
  color: #f56c6c;
  font-size: 15px;
  margin: 8px 0;
}

.error-tip {
  color: #909399 !important;
  font-size: 13px !important;
}

.result-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
  flex-wrap: wrap;
}

.action-btn {
  min-width: 140px;
}
</style>
