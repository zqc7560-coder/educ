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

/**
 * 头像两段式（用户口径：选了图≠上传服务器，必须点"更换头像"才提交）：
 * onPick 只本地暂存 File 并给即时预览；doUpload 才真发 multipart。
 * 未提交时点"取消"或换页丢弃，服务器不留垃圾文件。
 */
const picked = ref(null)
const previewUrl = ref('')
const uploading = ref(false)

function onPick(e) {
  const f = (e.target.files || [])[0]
  e.target.value = ''   // 允许重复选同一文件仍触发 change
  if (!f) return
  if (!/^image\//.test(f.type)) return ElMessage.warning('只能选择图片文件')
  if (f.size > 5 * 1024 * 1024) return ElMessage.warning('图片不能超过 5MB')
  if (picked.value) URL.revokeObjectURL(previewUrl.value)
  picked.value = f
  previewUrl.value = URL.createObjectURL(f)
}

async function doUpload() {
  if (!picked.value) return
  uploading.value = true
  const fd = new FormData()
  fd.append('file', picked.value)
  try {
    const u = await request.post('/users/avatar', fd)
    avatar.value = u.avatar || ''
    store.applyLogin(store.token, u)
    ElMessage.success('头像已更新')
    cancelPick()
  } catch { /* 4001/4002 拦截器 toast */ } finally { uploading.value = false }
}

function cancelPick() {
  if (picked.value && previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  picked.value = null
  previewUrl.value = ''
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
        <el-avatar :size="64" :src="(picked ? previewUrl : avatar) || undefined">{{ (profile.nickname || '我')[0] }}</el-avatar>
        <template v-if="!picked">
          <label class="up">
            <input type="file" accept="image/*" hidden @change="onPick" />
            <el-button size="small">选择图片</el-button>
          </label>
        </template>
        <template v-else>
          <el-button size="small" type="primary" :loading="uploading" @click="doUpload">确认更换</el-button>
          <el-button size="small" @click="cancelPick">取消</el-button>
        </template>
        <span class="dim">png/jpg 等，≤5MB；选图后须点「确认更换」才会上传服务器</span>
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
