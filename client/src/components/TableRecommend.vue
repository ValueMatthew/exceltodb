<template>
  <div class="table-recommend">
    <h2>选择目标表</h2>
    <p class="tip">系统会根据Excel列名智能推荐最匹配的数据表</p>

    <div v-if="loading" class="loading">
      <el-icon class="is-loading"><loading /></el-icon>
      <span>正在分析匹配...</span>
    </div>

    <div v-else class="recommend-content">
      <div v-if="recommendation" class="recommendation">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span>推荐表</span>
              <el-tag type="success">匹配度: {{ recommendation.score }}%</el-tag>
            </div>
          </template>
          <div class="table-info">
            <p><strong>表名:</strong> {{ recommendation.tableName }}</p>
            <p><strong>列数:</strong> {{ recommendation.columnCount }}</p>
            <p><strong>主键:</strong> {{ recommendation.primaryKey || '无' }}</p>
            <div class="column-match">
              <strong>匹配列:</strong>
              <el-tag
                v-for="col in recommendation.matchedColumns"
                :key="col"
                size="small"
                style="margin: 2px"
              >
                {{ col }}
              </el-tag>
            </div>
          </div>
          <div class="select-action">
            <el-button type="primary" @click="selectTable(recommendation)">
              选择此表
            </el-button>
          </div>
        </el-card>
      </div>

      <div v-if="!recommendation && !loading" class="no-match">
        <el-alert type="warning" :closable="false">
          <template #title>
            未找到合适的匹配表 (最高匹配度 {{ topScore }}% < 30%)
          </template>
        </el-alert>
      </div>

      <div class="other-tables">
        <h3>或选择其他表</h3>
        <el-select v-model="selectedTableName" placeholder="请选择表" style="width: 100%">
          <el-option
            v-for="table in allTables"
            :key="table.name"
            :label="table.name"
            :value="table.name"
          >
            <span>{{ table.name }}</span>
            <span style="color: #909399; font-size: 12px; margin-left: 10px">
              {{ table.columnCount }} 列
            </span>
          </el-option>
        </el-select>
        <el-button
          v-if="selectedTableName"
          type="primary"
          plain
          @click="selectTableByName"
          style="margin-top: 10px"
        >
          选择此表
        </el-button>
      </div>

      <div class="create-new">
        <el-divider />
        <el-button type="text" @click="showCreateDialog = true">
          <el-icon><plus /></el-icon>
          创建新表
        </el-button>
      </div>

      <div v-if="selectedTableInfo" class="import-options">
        <el-divider />
        <h3>导入设置</h3>

        <el-form label-width="120px">
          <el-form-item label="目标表">
            <el-tag>{{ selectedTableInfo.name }}</el-tag>
            <el-tag v-if="selectedTableInfo.primaryKey" type="warning" style="margin-left: 10px">
              主键: {{ selectedTableInfo.primaryKey }}
            </el-tag>
            <el-tag v-else type="info" style="margin-left: 10px">无主键</el-tag>
          </el-form-item>

          <el-form-item label="导入模式">
            <el-radio-group v-model="importMode">
              <el-radio label="INCREMENTAL">增量导入</el-radio>
              <el-radio label="TRUNCATE">清空导入</el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item v-if="selectedTableInfo.primaryKey && importMode === 'INCREMENTAL'" label="主键冲突策略">
            <el-radio-group v-model="conflictStrategy">
              <el-radio label="ERROR">报错 (默认)</el-radio>
              <el-radio label="UPDATE">更新 (UPSERT)</el-radio>
              <el-radio label="IGNORE">忽略</el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item>
            <el-button type="primary" @click="confirmImport">
              开始导入
            </el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <el-dialog v-model="showCreateDialog" title="创建新表" width="500px">
      <p>系统将根据Excel列名和数据类型自动创建新表</p>
      <el-form label-width="100px">
        <el-form-item label="表名">
          <el-input v-model="newTableName" placeholder="请输入新表名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" @click="createTable">创建</el-button>
      </template>
    </el-dialog>

    <div class="actions">
      <el-button @click="$emit('back')">上一步</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, inject } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { Loading, Plus } from '@element-plus/icons-vue'

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

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.table-info p {
  margin: 8px 0;
}

.column-match {
  margin-top: 10px;
}

.select-action {
  margin-top: 20px;
}

.other-tables {
  margin-top: 30px;
}

.other-tables h3 {
  color: #606266;
  margin-bottom: 15px;
  font-size: 14px;
}

.create-new {
  text-align: center;
  margin-top: 20px;
}

.import-options {
  margin-top: 20px;
}

.import-options h3 {
  color: #606266;
  margin-bottom: 15px;
  font-size: 14px;
}

.actions {
  margin-top: 30px;
}
</style>
