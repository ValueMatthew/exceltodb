<template>
  <div class="db-selector">
    <h2>选择目标数据库</h2>
    <p class="tip">请从下拉列表中选择要导入的目标数据库</p>

    <el-form>
      <el-form-item label="数据库">
        <el-select v-model="selectedDbId" placeholder="请选择数据库" style="width: 100%">
          <el-option
            v-for="db in databases"
            :key="db.id"
            :label="`${db.name} (${db.database})`"
            :value="db.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item v-if="selectedDbId">
        <el-alert
          v-if="connectionStatus === 'success'"
          title="连接成功"
          type="success"
          show-icon
        />
        <el-alert
          v-else-if="connectionStatus === 'failed'"
          title="连接失败"
          :description="connectionError"
          type="error"
          show-icon
        />
        <el-button v-else type="primary" @click="testConnection" :loading="testing">
          测试连接
        </el-button>
      </el-form-item>

      <el-form-item>
        <el-button type="primary" @click="handleNext" :disabled="!canProceed">
          下一步
        </el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { ref, computed, inject } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

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

loadDatabases()
</script>

<style scoped>
.db-selector {
  max-width: 600px;
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
</style>
