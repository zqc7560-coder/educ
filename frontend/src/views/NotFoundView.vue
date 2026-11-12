<script setup>
/** 404（M10）：稳定兜底出口。 */
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <div class="nf">
    <h1>404</h1>
    <p>页面不存在，可能链接已失效或被删除。</p>
    <div class="ops">
      <el-button type="primary" @click="router.push('/')">回到大厅</el-button>
      <el-button @click="router.back()">返回上一页</el-button>
    </div>
  </div>
</template>

<style scoped>
.nf { min-height: 70vh; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; color: #606266; }
h1 { font-size: 64px; color: #409eff; margin: 0; }
.ops { margin-top: 10px; display: flex; gap: 10px; }
</style>
