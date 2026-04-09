<template>
  <div class="db-selector">
    <div class="section-header">
      <div class="section-icon">
        <el-icon><database /></el-icon>
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
            <el-icon><warning /></el-icon>
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
            <el-icon><location /></el-icon>
            <span>{{ selectedDbInfo?.host }}:{{ selectedDbInfo?.port }}</span>
          </div>
          <div class="detail-item">
            <el-icon><user /></el-icon>
            <span>{{ selectedDbInfo?.username }}</span>
          </div>
        </div>
      </el-card>

      <div class="test-section">
        <template v-if="connectionStatus === 'success'">
          <el-result
            icon="success"
            title="连接成功"
            class="connection-result"
          />
        </template>
        <template v-else-if="connectionStatus === 'failed'">
          <el-result
            icon="error"
            title="连接失败"
            :sub-title="connectionError"
            class="connection-result"
          />
        </template>
        <template v-else>
          <el-button type="primary" size="large" @click="testConnection" :loading="testing" class="test-btn">
            <el-icon v-if="!testing"><connection /></el-icon>
            测试连接
          </el-button>
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
        下一步
        <el-icon class="el-icon--right"><arrow-right /></el-icon>
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, inject, onMounted } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import {
  Database, Warning, Location, User, Connection, ArrowRight
} from '@element-plus/icons-vue'

const emit = defineEmits(['next'])
const selectedDb = inject('selectedDb')

const databases = ref([])
const selectedDbId = ref('')
const connectionStatus = ref(null)
const connectionError = ref('')
const testing = ref(false)

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
  testing.value = true
  connectionStatus.value = null
  try {
    await axios.get(`/api/databases/${selectedDbId.value}/test`)
    connectionStatus.value = 'success'
  } catch (err) {
    connectionStatus.value = 'failed'
    connectionError.value = err.response?.data?.message || '无法连接到数据库'
  } finally {
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
  color: #fff;
  font-size: 26px;
  box-shadow: 0 4px 15px rgba(102, 126, 234, 0.4);
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

.detail-item {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #606266;
  font-size: 14px;
}

.test-section {
  margin: 24px 0;
}

.connection-result {
  padding: 20px 0;
}

.test-btn {
  width: 100%;
  height: 48px;
  font-size: 16px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
}

.test-btn:hover {
  opacity: 0.9;
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
