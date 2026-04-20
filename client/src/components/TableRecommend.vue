<template>
  <div class="table-recommend">
    <div class="section-header">
      <div class="section-icon recommend-icon-bg">
        <span class="icon-text">✨</span>
      </div>
      <div>
        <h2>选择目标表</h2>
        <p class="subtitle">系统根据 Excel 列名智能推荐匹配度 ≥ 90% 的数据表</p>
      </div>
    </div>

    <div v-if="loading" class="loading-state">
      <el-icon class="is-loading"><loading /></el-icon>
      <span>正在分析数据表匹配度...</span>
    </div>

    <div v-else class="recommend-content">
      <transition name="el-fade-in">
        <div v-if="recommendations.length > 0" class="recommendation-list">
          <el-card shadow="hover" class="rec-card" :body-style="{ padding: '0px' }">
            <div class="rec-header">
              <div class="rec-badge">
                <span>⭐ 推荐列表</span>
              </div>
              <el-tag type="success" class="score-tag">最高 {{ topScore }}%</el-tag>
            </div>

            <div class="rec-body">
              <el-radio-group v-model="selectedTableName" class="rec-radio-group">
                <div
                  v-for="(rec, idx) in recommendations"
                  :key="rec.tableName"
                  class="rec-item"
                >
                  <el-radio :label="rec.tableName">
                    <span class="rec-item-title">
                      <span class="rec-rank">{{ idx + 1 }}</span>
                      <span class="rec-name">📋 {{ rec.tableName }}</span>
                    </span>
                  </el-radio>
                  <div class="rec-item-meta">
                    <el-tag type="success" size="small" effect="plain">{{ rec.score }}% 匹配</el-tag>
                    <el-tag v-if="isBackupLike(rec.tableName)" type="warning" size="small" effect="plain">
                      可能是备份表
                    </el-tag>
                    <span class="meta-text">列数 {{ rec.columnCount }}</span>
                    <span class="meta-text">主键 {{ rec.primaryKey || '-' }}</span>
                    <span class="meta-text">匹配列 {{ rec.matchedColumns?.length || 0 }}</span>
                  </div>
                </div>
              </el-radio-group>
            </div>

            <div class="rec-footer">
              <el-button type="primary" @click="selectChosenTable" class="select-btn">
                选择此表
              </el-button>
            </div>
          </el-card>
        </div>
      </transition>

      <transition name="el-fade-in">
        <div v-if="recommendations.length === 0 && !loading" class="no-match">
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            class="no-match-alert"
            :title="'未找到合适的匹配表 (最高匹配度 ' + topScore + '% < 90%)'"
          >
            请检查导入文件是否正确，或联系IT团队创建对应的数据表后再试
          </el-alert>
        </div>
      </transition>

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
const recommendations = ref([])
const topScore = ref(0)
const selectedTableInfo = ref(null)
const selectedTableName = ref('')
const importMode = ref('INCREMENTAL')
const conflictStrategy = ref('ERROR')

const backupKeywords = ['test', 'bak', 'back', 'full']
const isBackupLike = (tableName) => {
  if (!tableName) return false
  const lower = String(tableName).toLowerCase()
  return backupKeywords.some(k => lower.includes(k))
}

const loadRecommendation = async () => {
  if (!selectedDb.value?.id) return

  loading.value = true
  try {
    const recommendRes = await axios.post('/api/recommend', {
      databaseId: selectedDb.value.id,
      filename: uploadedFile.value?.filename,
      sheetIndex: uploadedFile.value?.sheetIndex ?? 0,
      columns: previewData.value?.columns || []
    })

    if (recommendRes.data) {
      recommendations.value = recommendRes.data.recommendations || []
      topScore.value = recommendRes.data.topScore || 0
      if (recommendations.value.length > 0) {
        selectedTableName.value = recommendations.value[0].tableName
      } else {
        selectedTableName.value = ''
      }
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

const selectChosenTable = () => {
  const chosen = recommendations.value.find(r => r.tableName === selectedTableName.value)
  if (!chosen) {
    ElMessage.warning('请选择一个目标表')
    return
  }
  selectTable(chosen)
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
  loadRecommendation()
})
</script>

<style scoped>
.table-recommend {
  max-width: 800px;
  margin: 0 auto;
}

.recommendation-list {
  margin-bottom: 24px;
}

.rec-radio-group {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px 20px 6px 20px;
}

.rec-item {
  padding: 12px 12px;
  border: 1px solid #f0f0f0;
  border-radius: 12px;
  transition: all 0.2s ease;
}

.rec-item:hover {
  border-color: rgba(102, 126, 234, 0.35);
  box-shadow: 0 8px 20px rgba(102, 126, 234, 0.12);
}

.rec-item-title {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
}

.rec-rank {
  width: 22px;
  height: 22px;
  border-radius: 7px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  background: rgba(102, 126, 234, 0.12);
  color: #4c5bd4;
}

.rec-item-meta {
  margin-left: 30px;
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  color: #606266;
  font-size: 12px;
}

.meta-text {
  opacity: 0.9;
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

.actions {
  margin-top: 32px;
}
</style>
