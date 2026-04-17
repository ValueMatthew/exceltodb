<template>
  <div class="db-selector">
    <div class="section-header">
      <div class="section-icon">
        <span class="icon-text">DB</span>
      </div>
      <div>
        <h2>选择目标数据库</h2>
        <p class="subtitle">请从下拉列表中选择要导入的目标数据库</p>
      </div>
    </div>

    <div class="db-list">
      <el-select
        v-model="selectedDbId"
        placeholder="请选择数据库"
        size="large"
        class="db-select"
        filterable
      >
        <template #empty>
          <div class="empty-state">
            <span>暂无可用数据库</span>
          </div>
        </template>
        <el-option
          v-for="db in databases"
          :key="db.id"
          :label="db.name"
          :value="db.id"
          class="db-option"
        >
          <div class="db-option-content">
            <span class="db-name">{{ db.name }}</span>
            <span class="db-info">{{ db.host }}:{{ db.port }}/{{ db.database }}</span>
          </div>
        </el-option>
      </el-select>
    </div>

    <div v-if="selectedDbId" class="connection-test">
      <el-card class="db-preview-card" shadow="hover">
        <template #header>
          <div class="card-header">
            <span class="card-title">{{ selectedDbInfo?.name }}</span>
            <el-tag size="small" type="info">{{ selectedDbInfo?.database }}</el-tag>
          </div>
        </template>
        <div class="db-details">
          <div class="detail-item">
            <span class="detail-icon">📍</span>
            <span>{{ selectedDbInfo?.host }}:{{ selectedDbInfo?.port }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-icon">👤</span>
            <span class="user-line">
              <span>{{ selectedDbInfo?.username }}</span>
              <el-tag v-if="testing" size="small" type="info" effect="plain" class="status-tag">
                连接中...
              </el-tag>
              <el-tag v-else-if="connectionStatus === 'success'" size="small" type="success" class="status-tag">
                已连接
              </el-tag>
              <el-tag
                v-else-if="connectionStatus === 'failed'"
                size="small"
                type="danger"
                effect="plain"
                class="status-tag"
              >
                连接失败
              </el-tag>
            </span>
          </div>
        </div>
      </el-card>

      <div class="test-section">
        <template v-if="connectionStatus === 'failed'">
          <el-alert
            type="error"
            show-icon
            :closable="false"
            class="conn-alert"
            title="数据库连接失败"
            :description="connectionError || '无法连接到数据库'"
          />
        </template>
      </div>
    </div>

    <div class="actions">
      <el-button
        type="primary"
        size="large"
        @click="handleNext"
        :disabled="!canProceed"
        class="next-btn"
      >
        下一步 →
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, inject, onMounted, watch, onBeforeUnmount } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const emit = defineEmits(['next'])
const selectedDb = inject('selectedDb')

const databases = ref([])
const selectedDbId = ref('')
const connectionStatus = ref(null)
const connectionError = ref('')
const testing = ref(false)
let testDebounceTimer = null
const testSeq = ref(0)

const selectedDbInfo = computed(() => {
  return databases.value.find(db => db.id === selectedDbId.value)
})

const canProceed = computed(() => {
  return connectionStatus.value === 'success'
})

const loadDatabases = async () => {
  try {
    const res = await axios.get('/api/databases')
    databases.value = res.data
  } catch (err) {
    ElMessage.error('加载数据库列表失败')
  }
}

const testConnection = async () => {
  if (!selectedDbId.value) return
  const seq = ++testSeq.value
  testing.value = true
  connectionStatus.value = null
  connectionError.value = ''
  try {
    await axios.get(`/api/databases/${selectedDbId.value}/test`)
    if (seq !== testSeq.value) return
    connectionStatus.value = 'success'
  } catch (err) {
    if (seq !== testSeq.value) return
    connectionStatus.value = 'failed'
    connectionError.value = err.response?.data?.message || '无法连接到数据库'
  } finally {
    if (seq !== testSeq.value) return
    testing.value = false
  }
}

const handleNext = () => {
  if (selectedDbInfo.value) {
    selectedDb.value = selectedDbInfo.value
    emit('next', selectedDbInfo.value)
  }
}

onMounted(() => {
  loadDatabases()
})

watch(
  selectedDbId,
  (id) => {
    testSeq.value++
    testing.value = false
    connectionStatus.value = null
    connectionError.value = ''

    if (testDebounceTimer) clearTimeout(testDebounceTimer)
    if (!id) return

    testDebounceTimer = setTimeout(() => {
      testConnection()
    }, 250)
  },
  { flush: 'post' }
)

onBeforeUnmount(() => {
  if (testDebounceTimer) clearTimeout(testDebounceTimer)
})
</script>

<style scoped>
.db-selector {
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
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 15px rgba(102, 126, 234, 0.4);
}

.icon-text {
  color: #fff;
  font-size: 18px;
  font-weight: 700;
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

.db-list {
  margin-bottom: 24px;
}

.db-select {
  width: 100%;
}

:deep(.db-option-content) {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 0;
}

.db-name {
  font-weight: 600;
  color: #303133;
}

.db-info {
  font-size: 12px;
  color: #909399;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 20px;
  color: #909399;
}

.db-preview-card {
  margin-bottom: 24px;
  border: none;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-weight: 600;
  font-size: 16px;
}

.db-details {
  display: flex;
  gap: 24px;
}

.user-line {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.status-tag {
  border-radius: 999px;
}

.detail-item {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #606266;
  font-size: 14px;
}

.detail-icon {
  font-size: 14px;
}

.test-section {
  margin: 24px 0;
}

.testing-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #667eea;
  padding: 20px 12px;
  font-size: 14px;
}

.conn-alert {
  border-radius: 12px;
}

.retest-btn {
  margin-top: 10px;
}

.actions {
  margin-top: 32px;
  display: flex;
  justify-content: flex-end;
}

.next-btn {
  height: 48px;
  padding: 0 40px;
  font-size: 16px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
}
</style>
