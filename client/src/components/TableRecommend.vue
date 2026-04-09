<template>
  <div class="table-recommend">
    <div class="section-header">
      <div class="section-icon recommend-icon-bg">
        <span class="icon-text">✨</span>
      </div>
      <div>
        <h2>选择目标表</h2>
        <p class="subtitle">系统会根据 Excel 列名智能推荐最匹配的数据表</p>
      </div>
    </div>

    <div v-if="loading" class="loading-state">
      <el-icon class="is-loading"><loading /></el-icon>
      <span>正在分析数据表匹配度...</span>
    </div>

    <div v-else class="recommend-content">
      <transition name="el-fade-in">
        <div v-if="recommendation" class="recommendation-card">
          <el-card shadow="hover" class="rec-card" :body-style="{ padding: '0px' }">
            <div class="rec-header">
              <div class="rec-badge">
                <span>⭐ 最佳推荐</span>
              </div>
              <el-tag type="success" class="score-tag">{{ recommendation.score }}% 匹配</el-tag>
            </div>
            <div class="rec-body">
              <div class="rec-table-name">
                <span>📋 {{ recommendation.tableName }}</span>
              </div>
              <div class="rec-stats">
                <div class="stat-item">
                  <span class="stat-value">{{ recommendation.columnCount }}</span>
                  <span class="stat-label">列数</span>
                </div>
                <div class="stat-item">
                  <span class="stat-value">{{ recommendation.primaryKey || '-' }}</span>
                  <span class="stat-label">主键</span>
                </div>
                <div class="stat-item">
                  <span class="stat-value">{{ recommendation.matchedColumns?.length || 0 }}</span>
                  <span class="stat-label">匹配列</span>
                </div>
              </div>
              <div class="matched-columns">
                <span class="match-label">匹配列：</span>
                <el-tag
                  v-for="col in recommendation.matchedColumns"
                  :key="col"
                  size="small"
                  type="success"
                  effect="plain"
                  class="match-tag"
                >
                  {{ col }}
                </el-tag>
              </div>
            </div>
            <div class="rec-footer">
              <el-button type="primary" @click="selectTable(recommendation)" class="select-btn">
                选择此表
              </el-button>
            </div>
          </el-card>
        </div>
      </transition>

      <transition name="el-fade-in">
        <div v-if="!recommendation && !loading" class="no-match">
          <el-alert type="warning" :closable="false" show-icon class="no-match-alert">
            <template #title>
              <span>
                未找到合适的匹配表 (最高匹配度 {{ topScore }}% < 30%)
              </span>
            </template>
            <template #description>
              您可以选择其他表或创建新表
            </template>
          </el-alert>
        </div>
      </transition>

      <div class="other-tables">
        <div class="divider-with-text">
          <el-divider />
          <span class="divider-text">或选择其他表</span>
          <el-divider />
        </div>

        <el-select
          v-model="selectedTableName"
          placeholder="请选择表"
          size="large"
          filterable
          clearable
          class="table-select"
        >
          <template #empty>
            <span class="empty-text">暂无可用表</span>
          </template>
          <el-option
            v-for="table in allTables"
            :key="table.name"
            :label="table.name"
            :value="table.name"
            class="table-option"
          >
            <div class="table-option-content">
              <span class="table-name">{{ table.name }}</span>
              <span class="table-meta">{{ table.columnCount }} 列</span>
            </div>
          </el-option>
        </el-select>

        <el-button
          v-if="selectedTableName"
          type="primary"
          plain
          @click="selectTableByName"
          class="confirm-select-btn"
        >
          确认选择
        </el-button>
      </div>

      <div class="create-new">
        <el-button type="text" @click="showCreateDialog = true" class="create-btn">
          ➕ 创建新表（根据 Excel 自动推断列类型）
        </el-button>
      </div>

      <transition name="el-fade-in">
        <div v-if="selectedTableInfo" class="import-options">
          <el-divider content-position="left">
            <span class="import-divider-text">导入设置</span>
          </el-divider>

          <el-card shadow="never" class="import-card">
            <div class="import-info">
              <span class="target-table">
                目标表：
                <el-tag type="primary">{{ selectedTableInfo.name }}</el-tag>
              </span>
              <el-tag v-if="selectedTableInfo.primaryKey" type="warning" size="small">
                主键: {{ selectedTableInfo.primaryKey }}
              </el-tag>
              <el-tag v-else type="info" size="small">无主键</el-tag>
            </div>

            <el-form label-position="top" class="import-form">
              <el-form-item label="导入模式">
                <el-radio-group v-model="importMode" size="large">
                  <el-radio-button label="INCREMENTAL">
                    ➕ 增量导入
                  </el-radio-button>
                  <el-radio-button label="TRUNCATE">
                    🗑 清空导入
                  </el-radio-button>
                </el-radio-group>
              </el-form-item>

              <el-form-item
                v-if="selectedTableInfo.primaryKey && importMode === 'INCREMENTAL'"
                label="主键冲突策略"
              >
                <el-radio-group v-model="conflictStrategy" size="large">
                  <el-radio-button label="ERROR">❌ 报错</el-radio-button>
                  <el-radio-button label="UPDATE">🔄 更新</el-radio-button>
                  <el-radio-button label="IGNORE">⏭ 忽略</el-radio-button>
                </el-radio-group>
              </el-form-item>

              <el-form-item>
                <el-button type="primary" @click="confirmImport" size="large" class="import-btn">
                  开始导入 →
                </el-button>
              </el-form-item>
            </el-form>
          </el-card>
        </div>
      </transition>
    </div>

    <el-dialog v-model="showCreateDialog" title="创建新表" width="450px" center>
      <div class="create-dialog-content">
        <span class="create-dialog-icon">➕</span>
        <p>系统将根据 Excel 列名和数据类型自动创建新表</p>
        <el-form label-width="80px">
          <el-form-item label="表名">
            <el-input v-model="newTableName" placeholder="请输入新表名" size="large" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" @click="createTable">创建</el-button>
      </template>
    </el-dialog>

    <div class="actions">
      <el-button @click="$emit('back')" size="large">上一步</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, inject } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const emit = defineEmits(['next', 'back'])
const selectedDb = inject('selectedDb')
const uploadedFile = inject('uploadedFile')
const previewData = inject('previewData')
const selectedTable = inject('selectedTable')

const loading = ref(false)
const recommendation = ref(null)
const topScore = ref(0)
const allTables = ref([])
const selectedTableName = ref('')
const selectedTableInfo = ref(null)
const importMode = ref('INCREMENTAL')
const conflictStrategy = ref('ERROR')
const showCreateDialog = ref(false)
const newTableName = ref('')

const loadTables = async () => {
  if (!selectedDb.value?.id) return

  loading.value = true
  try {
    const [tablesRes, recommendRes] = await Promise.all([
      axios.get(`/api/tables/${selectedDb.value.id}`),
      axios.post('/api/recommend', {
        databaseId: selectedDb.value.id,
        filename: uploadedFile.value?.filename,
        columns: previewData.value?.columns || []
      })
    ])

    allTables.value = tablesRes.data
    if (recommendRes.data) {
      recommendation.value = recommendRes.data
      topScore.value = recommendRes.data.score || 0
    }
  } catch (err) {
    ElMessage.error('加载表信息失败')
  } finally {
    loading.value = false
  }
}

const selectTable = (table) => {
  selectedTableInfo.value = {
    name: table.tableName || table.name,
    primaryKey: table.primaryKey,
    columns: table.columns
  }
  selectedTable.value = selectedTableInfo.value
}

const selectTableByName = async () => {
  const table = allTables.value.find(t => t.name === selectedTableName.value)
  if (table) {
    selectedTableInfo.value = {
      name: table.name,
      primaryKey: table.primaryKey,
      columns: table.columns
    }
  }
}

const createTable = async () => {
  if (!newTableName.value) {
    ElMessage.warning('请输入表名')
    return
  }

  try {
    await axios.post('/api/create-table', {
      databaseId: selectedDb.value.id,
      tableName: newTableName.value,
      columns: previewData.value?.columns || [],
      filename: uploadedFile.value?.filename
    })
    ElMessage.success('建表成功')
    showCreateDialog.value = false
    await loadTables()
    selectedTableName.value = newTableName.value
    await selectTableByName()
  } catch (err) {
    ElMessage.error(err.response?.data?.message || '建表失败')
  }
}

const confirmImport = () => {
  selectedTable.value = {
    ...selectedTableInfo.value,
    importMode: importMode.value,
    conflictStrategy: conflictStrategy.value
  }
  emit('next', selectedTable.value)
}

onMounted(() => {
  loadTables()
})
</script>

<style scoped>
.table-recommend {
  max-width: 800px;
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

.recommend-icon-bg {
  background: linear-gradient(135deg, #fa709a 0%, #fee140 100%);
  box-shadow: 0 4px 15px rgba(250, 112, 154, 0.4);
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

.recommendation-card {
  margin-bottom: 24px;
}

.rec-card {
  border: 2px solid #67c23a;
  border-radius: 16px;
  overflow: hidden;
}

.rec-header {
  background: linear-gradient(135deg, #67c23a 0%, #85ce61 100%);
  padding: 16px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: #fff;
}

.rec-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

.score-tag {
  background: rgba(255, 255, 255, 0.2);
  border: none;
  color: #fff;
}

.rec-body {
  padding: 20px;
}

.rec-table-name {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 16px;
}

.rec-stats {
  display: flex;
  gap: 24px;
  margin-bottom: 16px;
}

.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 12px 20px;
  background: #f5f7fa;
  border-radius: 8px;
}

.stat-value {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.stat-label {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.matched-columns {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.match-label {
  font-size: 13px;
  color: #606266;
}

.match-tag {
  margin: 2px;
}

.rec-footer {
  padding: 16px 20px;
  border-top: 1px solid #f0f0f0;
  display: flex;
  justify-content: flex-end;
}

.select-btn {
  background: linear-gradient(135deg, #67c23a 0%, #85ce61 100%);
  border: none;
}

.no-match {
  margin-bottom: 24px;
}

.no-match-alert {
  border-radius: 12px;
}

.other-tables {
  margin: 24px 0;
}

.divider-with-text {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
}

.divider-with-text .el-divider {
  flex: 1;
  margin: 0;
}

.divider-text {
  color: #909399;
  font-size: 14px;
  white-space: nowrap;
}

.table-select {
  width: 100%;
  margin-bottom: 12px;
}

.table-option-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.table-name {
  font-weight: 500;
}

.table-meta {
  font-size: 12px;
  color: #909399;
}

.confirm-select-btn {
  width: 100%;
}

.create-new {
  text-align: center;
  margin: 24px 0;
}

.create-btn {
  color: #667eea;
  font-size: 14px;
}

.create-btn:hover {
  color: #764ba2;
}

.import-options {
  margin-top: 24px;
}

.import-divider-text {
  color: #606266;
  font-weight: 500;
}

.import-card {
  background: #fafafa;
  border: none;
}

.import-info {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.target-table {
  font-size: 14px;
  color: #606266;
}

.import-form {
  max-width: 500px;
}

.import-btn {
  width: 100%;
  height: 48px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
  font-size: 16px;
}

.create-dialog-content {
  text-align: center;
  padding: 20px 0;
}

.create-dialog-icon {
  font-size: 48px;
  color: #667eea;
  margin-bottom: 16px;
}

.create-dialog-content p {
  color: #606266;
  margin-bottom: 20px;
}

.empty-text {
  color: #909399;
}

.actions {
  margin-top: 32px;
}
</style>
