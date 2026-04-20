<template>
  <div class="table-recommend">
    <div class="section-header">
      <div class="section-icon recommend-icon-bg">
        <span class="icon-text">✨</span>
      </div>
      <div>
        <h2>选择目标表</h2>
        <p class="subtitle">支持手动指定目标表，也支持系统智能推荐</p>
      </div>
    </div>

    <div class="mode-switch">
      <el-radio-group v-model="mode" size="large">
        <el-radio-button :label="MODE_MANUAL">手动指定目标表</el-radio-button>
        <el-radio-button :label="MODE_RECOMMEND">系统推荐表</el-radio-button>
      </el-radio-group>
    </div>

    <div class="recommend-content">
      <transition name="el-fade-in" mode="out-in">
        <div v-if="mode === MODE_MANUAL" key="manual" class="manual-mode">
          <el-card shadow="hover" class="manual-card">
            <el-form label-position="top" class="manual-form">
              <el-form-item label="目标表名称">
                <el-select
                  v-model="manualTableName"
                  filterable
                  allow-create
                  clearable
                  placeholder="请选择或输入目标表名称"
                  class="manual-select"
                  :loading="manualLoadingNames"
                >
                  <el-option
                    v-for="tableName in manualOptions"
                    :key="tableName"
                    :label="tableName"
                    :value="tableName"
                  />
                </el-select>
              </el-form-item>

              <el-form-item class="manual-actions">
                <el-button
                  type="primary"
                  size="large"
                  :loading="manualValidating"
                  :disabled="manualLoadingNames"
                  @click="validateManualTable"
                >
                  确认/校验
                </el-button>
              </el-form-item>
            </el-form>

            <el-alert
              v-if="manualValidationResult"
              :type="manualAlertType"
              :title="manualAlertTitle"
              :description="manualAlertDescription"
              :closable="false"
              show-icon
              class="manual-result"
            />
          </el-card>
        </div>

        <div v-else key="recommend" class="recommend-mode">
          <div v-if="loading" class="loading-state">
            <el-icon class="is-loading"><loading /></el-icon>
            <span>正在分析数据表匹配度...</span>
          </div>

          <template v-else>
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
              <div v-if="recommendLoaded && recommendations.length === 0" class="no-match">
                <el-alert
                  type="warning"
                  :closable="false"
                  show-icon
                  class="no-match-alert"
                  :title="`未找到合适的匹配表 (最高匹配度 ${topScore}% < ${recommendThreshold}%)`"
                >
                  请检查导入文件是否正确，或联系IT团队创建对应的数据表后再试
                </el-alert>
              </div>
            </transition>
          </template>
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
import { computed, inject, onMounted, ref, watch } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const MODE_MANUAL = 'MANUAL'
const MODE_RECOMMEND = 'RECOMMEND'

const emit = defineEmits(['next', 'back'])
const selectedDb = inject('selectedDb')
const uploadedFile = inject('uploadedFile')
const previewData = inject('previewData')
const selectedTable = inject('selectedTable')

const mode = ref(MODE_MANUAL)

const manualTableName = ref('')
const manualOptions = ref([])
const manualLoadingNames = ref(false)
const manualValidating = ref(false)
const manualValidationResult = ref(null)
const manualError = ref('')
const manualSelectedTableInfo = ref(null)

const loading = ref(false)
const recommendations = ref([])
const topScore = ref(0)
const recommendThreshold = ref(90)
const selectedTableName = ref('')
const recommendLoaded = ref(false)
const recommendSelectedTableInfo = ref(null)

const selectedTableInfo = ref(null)
const importMode = ref('INCREMENTAL')
const conflictStrategy = ref('ERROR')

const backupKeywords = ['test', 'bak', 'back', 'full']

const isBackupLike = (tableName) => {
  if (!tableName) return false
  const lower = String(tableName).toLowerCase()
  return backupKeywords.some(k => lower.includes(k))
}

const normalizeSelectedTableInfo = (table) => {
  if (!table) return null

  return {
    name: table.tableName || table.name,
    primaryKey: table.primaryKey,
    columns: table.columns || []
  }
}

const syncActiveSelectedTableInfo = () => {
  const activeTable = mode.value === MODE_MANUAL
    ? manualSelectedTableInfo.value
    : recommendSelectedTableInfo.value

  selectedTableInfo.value = activeTable ? { ...activeTable } : null
  selectedTable.value = activeTable ? { ...activeTable } : null
}

const setSelectedTableForMode = (table, targetMode = mode.value) => {
  const normalized = normalizeSelectedTableInfo(table)
  if (targetMode === MODE_MANUAL) {
    manualSelectedTableInfo.value = normalized
  } else {
    recommendSelectedTableInfo.value = normalized
  }
  syncActiveSelectedTableInfo()
}

const clearSelectedTableForMode = (targetMode = mode.value) => {
  if (targetMode === MODE_MANUAL) {
    manualSelectedTableInfo.value = null
  } else {
    recommendSelectedTableInfo.value = null
  }
  syncActiveSelectedTableInfo()
}

const manualAlertType = computed(() => {
  if (!manualValidationResult.value) return 'info'
  if (!manualValidationResult.value.exists) return 'error'
  if ((manualValidationResult.value.score ?? 0) < (manualValidationResult.value.threshold ?? 90)) {
    return 'warning'
  }
  if (manualError.value) return 'error'
  return 'success'
})

const manualAlertTitle = computed(() => {
  if (!manualValidationResult.value) return ''
  if (!manualValidationResult.value.exists) return '目标表不存在'

  const score = manualValidationResult.value.score ?? 0
  const threshold = manualValidationResult.value.threshold ?? 90
  if (score < threshold) {
    return '目标表存在，但匹配度未达标'
  }

  if (manualError.value) return '目标表校验失败'

  return '目标表校验通过'
})

const manualAlertDescription = computed(() => {
  if (!manualValidationResult.value) return ''
  if (manualError.value) return manualError.value

  const score = manualValidationResult.value.score ?? 0
  const threshold = manualValidationResult.value.threshold ?? 90
  return `匹配度 ${score}%，阈值 ${threshold}%`
})

const loadManualTableNames = async () => {
  if (!selectedDb.value?.id || manualLoadingNames.value || manualOptions.value.length > 0) return

  manualLoadingNames.value = true
  manualError.value = ''
  try {
    const response = await axios.get(`/api/tables/${selectedDb.value.id}/names`)
    manualOptions.value = Array.isArray(response.data) ? response.data : []
  } catch (err) {
    manualError.value = '加载目标表名称失败，请稍后重试'
    ElMessage.error(manualError.value)
  } finally {
    manualLoadingNames.value = false
  }
}

const loadRecommendation = async () => {
  if (!selectedDb.value?.id || loading.value) return

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
      recommendThreshold.value = recommendRes.data.threshold || 90
      recommendLoaded.value = true

      if (!selectedTableName.value && recommendations.value.length > 0) {
        selectedTableName.value = recommendations.value[0].tableName
      }
    }
  } catch (err) {
    ElMessage.error('加载表推荐失败')
  } finally {
    loading.value = false
  }
}

const validateManualTable = async () => {
  if (!manualTableName.value?.trim()) {
    ElMessage.warning('请选择目标表')
    return
  }

  manualValidating.value = true
  manualError.value = ''
  manualValidationResult.value = null

  try {
    const response = await axios.post('/api/validate-table', {
      databaseId: selectedDb.value?.id,
      tableName: manualTableName.value.trim(),
      filename: uploadedFile.value?.filename,
      sheetIndex: uploadedFile.value?.sheetIndex ?? 0,
      columns: previewData.value?.columns || []
    })

    manualValidationResult.value = response.data

    if (!response.data?.exists) {
      manualError.value = '未找到该目标表，请检查表名后重试'
      clearSelectedTableForMode(MODE_MANUAL)
      ElMessage.warning(manualError.value)
      return
    }

    if ((response.data.score ?? 0) < (response.data.threshold ?? 90)) {
      manualError.value = `目标表存在，但匹配度 ${response.data.score}% 低于阈值 ${response.data.threshold}%`
      clearSelectedTableForMode(MODE_MANUAL)
      ElMessage.warning(manualError.value)
      return
    }

    if (!response.data.table) {
      manualError.value = '服务器未返回表结构信息，请稍后重试'
      clearSelectedTableForMode(MODE_MANUAL)
      ElMessage.error(manualError.value)
      return
    }

    manualTableName.value = response.data.table.tableName || manualTableName.value.trim()
    setSelectedTableForMode(response.data.table, MODE_MANUAL)
    ElMessage.success(`校验通过，匹配度 ${response.data.score}%`)
  } catch (err) {
    manualError.value = '目标表校验失败，请稍后重试'
    clearSelectedTableForMode(MODE_MANUAL)
    ElMessage.error(manualError.value)
  } finally {
    manualValidating.value = false
  }
}

const selectChosenTable = () => {
  const chosen = recommendations.value.find(r => r.tableName === selectedTableName.value)
  if (!chosen) {
    ElMessage.warning('请选择一个目标表')
    return
  }
  setSelectedTableForMode(chosen, MODE_RECOMMEND)
}

const confirmImport = () => {
  selectedTable.value = {
    ...selectedTableInfo.value,
    importMode: importMode.value,
    conflictStrategy: conflictStrategy.value
  }
  emit('next', selectedTable.value)
}

watch(mode, (nextMode) => {
  if (nextMode === MODE_MANUAL) {
    void loadManualTableNames()
  } else if (!recommendLoaded.value) {
    void loadRecommendation()
  }

  syncActiveSelectedTableInfo()
})

onMounted(() => {
  void loadManualTableNames()
  syncActiveSelectedTableInfo()
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
  margin-bottom: 24px;
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

.mode-switch {
  margin-bottom: 24px;
}

.manual-card,
.rec-card {
  border-radius: 16px;
  overflow: hidden;
}

.manual-form {
  margin-bottom: 8px;
}

.manual-select {
  width: 100%;
}

.manual-actions :deep(.el-form-item__content) {
  justify-content: flex-end;
}

.manual-result {
  margin-top: 8px;
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

.rec-card {
  border: 2px solid #67c23a;
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
