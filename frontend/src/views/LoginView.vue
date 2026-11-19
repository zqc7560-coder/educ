<script setup>
/**
 * 登录 / 注册页（M2 实装，替换 M0 占位）——《API接口设计.md》§2 的前端半边。
 *
 * 交互决策：
 * 1. 登录与注册共用一页两 tab：注册入口不常驻导航（游客只有登录诉求），
 *    tab 切换比独立路由少一次跳转、少维护一条路由守卫；
 * 2. 表单校验规则与后端 RegisterDTO 的 @Pattern 逐条对齐（学号 4~20 位字母数字、密码 ≥8 位含字母数字、邮箱格式）——前端校验是"省一次
 *    往返"，不是安全边界（绕开页面直接 POST 依然被后端 400 拦住）；
 * 3. 登录成功后回跳 redirect 参数（路由守卫塞进来的原目的地），无参数回首页；
 *    userStore.applyLogin 把 token+user 快照双写（内存 + localStorage），
 *    随后的路由跳转凭 isLoggedIn 即通过守卫，不再打 /me；
 * 4. 失败提示：拦截器对非认证失效码统一 toast，本页 catch 只负责把
 *    "提交中"状态复位（loading）——文案不在前端二次维护（服务端已本地化）。
 */
import { ref, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login, register } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

/** 当前面板：login / register（el-tabs 双向绑定） */
const tab = ref('login')
const submitting = ref(false)

/** 表单初值抽函数：注册成功后"清空并切回登录 tab"复用 */
const emptyLogin = () => ({ studentNo: '', password: '' })
const emptyRegister = () => ({ studentNo: '', password: '', confirm: '', nickname: '', email: '' })

const loginForm = reactive(emptyLogin())
const registerForm = reactive(emptyRegister())

/** 校验规则：正则与后端 DTO 完全一致的来源是《API接口设计.md》§2 文字约定 */
const loginRules = {
  studentNo: [{ required: true, message: '请输入学号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}
const registerRules = {
  studentNo: [
    { required: true, message: '请输入学号', trigger: 'blur' },
    { pattern: /^[0-9A-Za-z]{4,20}$/, message: '学号须为4~20位字母或数字', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,}$/, message: '密码须≥8位且包含字母和数字', trigger: 'blur' }
  ],
  // 二次确认纯前端体验项：后端没有 confirm 字段（多余字段 DTO 直接忽略）
  confirm: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) =>
        value === registerForm.password ? callback() : callback(new Error('两次输入的密码不一致')),
      trigger: 'blur'
    }
  ],
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ]
}

const loginRef = ref(null)
const registerRef = ref(null)

/** 提交登录：拦截器成功时已拆信封，payload = { token, expiresIn, user } */
async function submitLogin() {
  await loginRef.value.validate()
  submitting.value = true
  try {
    const payload = await login({ studentNo: loginForm.studentNo, password: loginForm.password })
    store.applyLogin(payload.token, payload.user)
    ElMessage.success('登录成功')
    // redirect 白名单化：只接受站内相对路径，防"登录跳板"式开放重定向
    const to = route.query.redirect
    router.push(typeof to === 'string' && to.startsWith('/') && !to.startsWith('//') ? to : '/')
  } finally {
    submitting.value = false
  }
}

/** 提交注册：成功提示后清空表单切回登录 tab（让用户立刻用新账号登录） */
async function submitRegister() {
  await registerRef.value.validate()
  submitting.value = true
  try {
    await register({
      studentNo: registerForm.studentNo,
      password: registerForm.password,
      nickname: registerForm.nickname,
      email: registerForm.email
    })
    ElMessage.success('注册成功，请登录')
    Object.assign(loginForm, emptyLogin(), { studentNo: registerForm.studentNo }) // 带回学号
    Object.assign(registerForm, emptyRegister())
    tab.value = 'login'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <el-card class="login-card">
      <template #header><h2 style="margin: 0">校园失物招领系统</h2></template>
      <el-tabs v-model="tab" stretch>
        <el-tab-pane label="登录" name="login">
          <el-form ref="loginRef" :model="loginForm" :rules="loginRules" label-position="top"
                   @submit.prevent="submitLogin">
            <el-form-item label="学号" prop="studentNo">
              <el-input v-model="loginForm.studentNo" placeholder="学号" clearable />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="loginForm.password" type="password" placeholder="密码"
                        show-password @keyup.enter="submitLogin" />
            </el-form-item>
            <el-button type="primary" native-type="submit" style="width: 100%"
                       :loading="submitting">登 录</el-button>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="注册" name="register">
          <el-form ref="registerRef" :model="registerForm" :rules="registerRules" label-position="top"
                   @submit.prevent="submitRegister">
            <el-form-item label="学号" prop="studentNo">
              <el-input v-model="registerForm.studentNo" placeholder="4~20位字母或数字" clearable />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="registerForm.password" type="password" placeholder="≥8位，含字母和数字" show-password />
            </el-form-item>
            <el-form-item label="确认密码" prop="confirm">
              <el-input v-model="registerForm.confirm" type="password" placeholder="再次输入密码" show-password />
            </el-form-item>
            <el-form-item label="昵称" prop="nickname">
              <el-input v-model="registerForm.nickname" placeholder="展示用昵称" maxlength="50" clearable />
            </el-form-item>
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="registerForm.email" placeholder="you@example.com" clearable />
            </el-form-item>
            <el-button type="primary" native-type="submit" style="width: 100%"
                       :loading="submitting">注 册</el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
      <!-- 游客入口：不必登录即可进大厅（发布/认领等操作会再引导登录） -->
      <div class="guest-entry">
        <el-link type="primary" :underline="false" @click="$router.push('/')">
          先随便逛逛（以游客身份浏览失物大厅）→
        </el-link>
      </div>
    </el-card>
  </main>
</template>

<style scoped>
.guest-entry { margin-top: 14px; text-align: center; }
.login-page {
  display: flex;
  justify-content: center;
  padding-top: 8vh;
}
.login-card {
  width: 380px;
}
/* M15 移动端断点系统回归：380px 定宽卡在 375px 屏上会横向溢出（登录是认证
   失效闸的落点页，手机上溢出=输入框被裁，属于"进不去门"级体验事故） */
@media (max-width: 768px) {
  .login-page { padding-top: 4vh; }
  .login-card { width: 100%; max-width: 380px; margin: 0 12px; }
}
</style>
