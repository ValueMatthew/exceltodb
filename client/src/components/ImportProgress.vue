<template>
  <div class="import-progress">
    <h2>数据导入</h2>

    <div v-if="status === 'idle' || status === 'importing'" class="progress-container">
      <el-progress
        :percentage="progress"
        :status="progressStatus"
        :stroke-width="20"
        striped
        striped-flow
      />
      <p class="progress-text">{{ statusText }}</p>
      <p class="detail-text">{{ detailText }}</p>
    </div>

    <div v-else-if="status === 'success'" class="result success">
      <el-result
        icon="success"
        title="导入成功"
        :sub-title="`共导入 ${resultData?.importedRows || 0} 行数据`"
      >
        <template #extra>
          <el-button type="primary" @click="handleReset">重新导入</el-button>
        </template>
      </el-result>
    </div>

    <div v-else-if="status === 'error'" class="result error">
      <el-result
        icon="error"
        title="导入失败"
        :sub-title="errorMessage"
      >
        <template #extra>
          <el-button @click="handleReset">重新导入</el-button>
          <el-button type="primary" @click="handleBack">返回上一步</el-button>
        </template>
      </el-result>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, inject } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const props = defineProps({
  params: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['reset', 'back'])

const status = ref('idle')
const progress = ref(0)
const statusText = ref('准备开始导入...')
const detailText = ref('')
const errorMessage = ref('')
const resultData = ref(null)

const progressStatus = ref('')

const startImport = async () => {
  status.value = 'importing'
  progress.value = 0
  statusText.value = '正在导入数据...'
  detailText.value = '连接数据库...'

  try {
    const res = await axios.post('/api/import', props.params, {
      onUploadProgress: (progressEvent) => {
        if (progressEvent.total) {
          const percent = Math.round((progressEvent.loaded * 100) / progressEvent.total)
          progress.value = Math.min(percent, 100)
          detailText.value = `已上传 ${formatNumber(progressEvent.loaded)} / ${formatNumber(progressEvent.total)} 字节`
        }
      }
    })

    resultData.value = res.data
    progress.value = 100
    statusText.value = '导入完成'
    detailText.value = ''
    status.value = 'success'
  } catch (err) {
    status.value = 'error'
    progressStatus.value = 'exception'
    errorMessage.value = err.response?.data?.message || err.message || '未知错误'
    ElMessage.error('导入失败: ' + errorMessage.value)
  }
}

const formatNumber = (num) => {
  return new Intl.NumberFormat().format(num)
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
</script>

<style scoped>
.import-progress {
  max-width: 600px;
  margin: 0 auto;
  text-align: center;
}

h2 {
  margin-bottom: 40px;
  color: #303133;
}

.progress-container {
  padding: 40px 20px;
}

.progress-text {
  margin-top: 20px;
  font-size: 16px;
  color: #303133;
}

.detail-text {
  margin-top: 10px;
  font-size: 14px;
  color: #909399;
}

.result {
  padding: 40px 20px;
}

.result.success,
.result.error {
  text-align: center;
}
</style>
