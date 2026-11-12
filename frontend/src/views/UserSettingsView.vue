<script setup>
/** 账号设置（M10）：资料 / 改密（成功即全端下线重登）/ 头像（三卡校验后端裁决）。 */
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchMe, changePassword as apiChangePwd } from '@/api/auth'
import request from '@/api/request'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const store = useUserStore()

const profile = reactive({ nickname: '', email: '', phone: '' })
const avatar = ref('')
onMounted(async () => {
  try {
    const me = await fetchMe()
    Object.assign(profile, { nickname: me.nickname || '', email: me.email || '', phone: me.phone || '' })
    avatar.value = me.avatar || ''
  } catch { /* 拦截器 toast */ }
})

async function saveProfile() {
  if (!profile.nickname.trim()) return ElMessage.warning('昵称不能为空')
  try {
    const u = await request.put('/users/profile', profile)
    store.applyLogin(store.token, u)   // 快照就地更新（applyLogin 兼写缓存语义）
    ElMessage.success('资料已保存')
  } catch { /* toast */ }
}

async function pickAvatar(e) {
  const f = (e.target.files || [])[0]
  if (!f) return
  const fd = new FormData()
  fd.append('file', f)
  try {
    const u = await request.post('/users/avatar', fd)
    avatar.value = u.avatar || ''
    store.applyLogin(store.token, u)
    ElMessage.success('头像已更新')
  } catch { /* 4001/4002 拦截器 toast */ }
}

const pwd = reactive({ oldPassword: '', newPassword: '', confirm: '' })
async function changePwd() {
  if (!/^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(pwd.newPassword))
    return ElMessage.warning('新密码须≥8位且包含字母和数字')
  if (pwd.newPassword !== pwd.confirm) return ElMessage.warning('两次输入不一致')
  try {
    await apiChangePwd({ oldPassword: pwd.oldPassword, newPassword: pwd.newPassword })
    ElMessage.success('密码已修改，请重新登录')
    store.logout()
    router.push('/login')
  } catch { /* toast */ }
}
</script>

<template>
  <div class="page">
    <div class="panel">
      <h3>个人资料</h3>
      <div class="ava-row">
        <el-avatar :size="64" :src="avatar || undefined">{{ (profile.nickname || '我')[0] }}</el-avatar>
        <label class="up">
          <input type="file" accept="image/*" hidden @change="pickAvatar" />
          <el-button size="small">更换头像</el-button>
        </label>
        <span class="dim">png/jpg 等，≤5MB</span>
      </div>
      <el-form label-width="70px" style="max-width:420px">
        <el-form-item label="学号"><el-input :model-value="store.user?.studentNo" disabled /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="profile.nickname" maxlength="30" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="profile.email" maxlength="100" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="profile.phone" maxlength="20" /></el-form-item>
        <el-form-item><el-button type="primary" @click="saveProfile">保存</el-button></el-form-item>
      </el-form>
    </div>

    <div class="panel">
      <h3>修改密码</h3>
      <el-alert type="warning" :closable="false" class="tip" title="改密成功后所有设备登录立即失效，需要重新登录。" />
      <el-form label-width="90px" style="max-width:420px">
        <el-form-item label="当前密码"><el-input v-model="pwd.oldPassword" type="password" show-password /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="pwd.newPassword" type="password" show-password /></el-form-item>
        <el-form-item label="确认新密码"><el-input v-model="pwd.confirm" type="password" show-password /></el-form-item>
        <el-form-item><el-button type="primary" @click="changePwd">修改密码</el-button></el-form-item>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.page { padding: 6px 0 30px; display: flex; flex-direction: column; gap: 14px; }
.panel { background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 16px 20px; }
h3 { margin: 0 0 14px; font-size: 15px; }
.ava-row { display: flex; align-items: center; gap: 14px; margin-bottom: 16px; }
.dim { color: #909399; font-size: 12px; }
.tip { margin-bottom: 14px; }
.up { cursor: pointer; }
</style>
