<script setup>
/**
 * 物品详情（M10 实体版）—— 认领交互主战场（M4 状态机的前端半边）。
 *
 * 视角三态（与后端可见性矩阵/权限口逐一对齐）：
 *  - 游客：看公开视图（contact 后端摘除），点认领引导登录；
 *  - 浏览者（非作者）：FOUND 已发布件可提交认领申请（uk 重复由后端 3003 toast）；
 *    自己的认领单下方可撤回式跟进——本系统无撤回，改"申请仲裁"通道；
 *  - 作者：认领单核验列表（通过/拒绝/确认归还三口），这是 M4 状态机的操作面；
 *  - 下架件：可见但置灰（作者/管理员能进来看治理理由）。
 * 编辑/删除入口仅作者本人（后端 owner 校验兜底，前端藏按钮是体验不是安全）。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchItem, changeItemStatus, deleteItem, fetchSimilarItems, toggleItemTop } from '@/api/item'
import { applyClaim, listClaimsForItem, verifyClaim, rejectClaim, confirmReturn, applyArbitration } from '@/api/claim'
import { toggleFavorite } from '@/api/common'
import { useUserStore } from '@/stores/user'
import { fmtTime, relTime, CLAIM_STATUS, safeSrc } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const item = ref(null)
const similar = ref([])
const top = () => act(() => toggleItemTop(item.value.id), item.value.topFlag === 1 ? '已取消置顶' : '已置顶')
const loading = ref(true)
const claims = ref([])
const isAuthor = computed(() => item.value && store.user && item.value.userId === store.user.id)
const canGovern = computed(() => (store.perms || []).includes('item:audit'))
const showContact = computed(() => item.value && store.isLoggedIn)
const live = computed(() => item.value && item.value.status === 1)

async function loadAll() {
  loading.value = true
  try {
    item.value = await fetchItem(route.params.id)
    if (isAuthor.value || canGovern.value) {
      try { claims.value = await listClaimsForItem(route.params.id) } catch { claims.value = [] }
    }
    try { similar.value = await fetchSimilarItems(route.params.id) } catch { similar.value = [] }
  } catch {
    item.value = null
  } finally {
    loading.value = false
  }
}
onMounted(loadAll)
watch(() => route.params.id, loadAll)

// ---------- 认领申请 ----------
const claimDialog = ref(false)
const claimForm = ref({ answer: '', evidenceNote: '' })
function openClaim() {
  if (!store.isLoggedIn) return router.push({ path: '/login', query: { redirect: route.fullPath } })
  claimForm.value = { answer: '', evidenceNote: '' }
  claimDialog.value = true
}
async function submitClaim() {
  if (!claimForm.value.answer.trim()) return ElMessage.warning('请填写核验答案')
  try {
    await applyClaim(item.value.id, claimForm.value)
    ElMessage.success('认领申请已提交，等待发布者核验')
    claimDialog.value = false
    loadAll()
  } catch { /* 3003/3005 等由拦截器 toast */ }
}

// ---------- 作者核验操作 ----------
async function act(fn, okMsg) {
  try { await fn(); ElMessage.success(okMsg); loadAll() } catch { /* 拦截器 toast */ }
}
const pass = (c) => act(() => verifyClaim(c.id, ''), '已通过，进入约定交接')
async function reject(c) {
  try {
    const { value } = await ElMessageBox.prompt('请填写拒绝理由（申请人可见）', '拒绝认领', {
      inputPlaceholder: '例如：描述与实物特征不符'
    })
    await act(() => rejectClaim(c.id, value), '已拒绝')
  } catch { /* 用户取消 */ }
}
const confirm = (c) => act(() => ElMessageBox.confirm(
  `确认把「${item.value.title}」归还给 ${c.claimantNickname}？确认后物品下架。`,
  '确认归还', { type: 'warning' }).then(() => confirmReturn(c.id)), '已确认归还，物品下架')
const arb = (c) => act(() => applyArbitration(c.id), '已申请仲裁，等待管理员裁决')

// ---------- 作者/治理其他动作 ----------
async function offline() {
  try {
    const { value } = await ElMessageBox.prompt('下架原因', '下架物品', { inputPlaceholder: '选填' })
    await act(() => changeItemStatus(item.value.id, 2, value || '作者自行下架'), '已下架')
  } catch { /* 取消 */ }
}
const relist = () => act(() => changeItemStatus(item.value.id, 1), '已重新上架')
async function remove() {
  try {
    await ElMessageBox.confirm('删除后进回收站（管理员可恢复/彻底删除）。确定？', '删除', { type: 'warning' })
    await deleteItem(item.value.id)
    ElMessage.success('已删除')
    router.push('/user/my-items')
  } catch { /* 取消 */ }
}
const fav = ref(false)
async function toggleFav() {
  if (!store.isLoggedIn) return router.push('/login')
  try {
    const r = await toggleFavorite('ITEM', item.value.id)
    fav.value = r.favorited
    ElMessage.success(r.favorited ? '已收藏' : '已取消收藏')
  } catch { /* toast */ }
}
const statusCls = computed(() => {
  if (!item.value) return ''
  return { 0: 'pending', 1: '', 2: 'off' }[item.value.status]
})
</script>

<template>
  <div class="wrap page" v-loading="loading">
    <template v-if="item">
      <div :class="['panel', statusCls]">
        <div class="top">
          <el-tag :type="item.type === 'FOUND' ? 'success' : 'danger'" effect="dark">
            {{ item.type === 'FOUND' ? '招领' : '失物' }}
          </el-tag>
          <el-tag v-if="item.status !== 1" :type="item.status === 0 ? 'warning' : 'info'">
            {{ item.status === 0 ? '待审核' : '已下架' }}
          </el-tag>
          <span class="cat">{{ item.categoryName || '未分类' }}</span>
          <!-- 未过审内容不可收藏：按钮直接隐藏（后端 2005/5008 硬闸兜底） -->
          <el-button v-if="live" class="fav" link @click="toggleFav">{{ fav ? '★ 已收藏' : '☆ 收藏' }}</el-button>
        </div>

        <h1 class="title">{{ item.title }}</h1>
        <div class="author">
          <span>{{ item.userNickname || '匿名' }} · 发布于 {{ fmtTime(item.publishedAt || item.createdAt) }}</span>
          <span class="stat">浏览 {{ item.viewCount }} · 认领申请 {{ item.claimCount }}</span>
        </div>

        <p class="desc">{{ item.description }}</p>

        <el-descriptions :column="2" border class="fields">
          <el-descriptions-item label="发生时间">{{ fmtTime(item.happenedAt) }}</el-descriptions-item>
          <el-descriptions-item label="发生地点">
            {{ [item.campusName, item.buildingName, item.locationText].filter(Boolean).join(' · ') || '—' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="item.verifyQuestion" label="核验问题" :span="2">
            <b>{{ item.verifyQuestion }}</b><span class="hint">（认领时须回答此问题）</span>
          </el-descriptions-item>
          <el-descriptions-item label="联系方式" :span="2">
            <template v-if="showContact">{{ item.contact || '未提供' }}</template>
            <el-button v-else link type="primary" @click="router.push('/login')">登录后可见</el-button>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="item.images && item.images.length" class="imgs">
          <el-image v-for="(u, i) in item.images" :key="i" :src="safeSrc(u)" fit="cover"
                    class="img" :preview-src-list="item.images.map(safeSrc).filter(Boolean)"
                    :initial-index="i" preview-teleported />
        </div>

        <el-alert v-if="item.status === 2 && item.offlineReason" type="info" :closable="false"
                  :title="`下架原因：${item.offlineReason}`" class="reason" />

        <div class="actions">
          <template v-if="isAuthor">
            <el-button v-if="item.status === 2" type="primary" @click="relist">重新上架</el-button>
            <template v-else-if="item.status === 1">
              <el-button @click="router.push(`/user/items/${item.id}/edit`)">编辑</el-button>
              <el-button @click="offline">下架</el-button>
            </template>
            <el-button type="danger" plain @click="remove">删除</el-button>
          </template>
          <template v-else>
            <el-button v-if="canGovern && live" size="small" plain @click="top">{{ item.topFlag === 1 ? '取消置顶' : '置顶' }}</el-button>
            <el-button v-if="item.type === 'FOUND' && live" type="primary" size="large" @click="openClaim">
              这是我丢的东西，申请认领
            </el-button>
            <el-button v-else-if="item.type === 'LOST' && live && showContact && item.contact"
                       type="primary" size="large" @click="ElMessage.success('请直接通过联系方式与发布者对接')">
              我捡到了，联系失主
            </el-button>
            <div v-else-if="!live" class="dead-tip">该物品当前不可认领</div>
          </template>
        </div>
      </div>

      <!-- 作者/治理：认领单核验区（M4 状态机操作面） -->
      <div v-if="(isAuthor || canGovern) && claims.length" class="claims">
        <h3>认领申请（{{ claims.length }}）</h3>
        <el-table :data="claims" size="small">
          <el-table-column prop="claimantNickname" label="申请人" width="110" />
          <el-table-column label="核验答案" min-width="180">
            <template #default="{ row }">
              <div>{{ row.answer }}</div>
              <div v-if="row.evidenceNote" class="evi">补充：{{ row.evidenceNote }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="(CLAIM_STATUS[row.status] || {}).type" size="small">
                {{ (CLAIM_STATUS[row.status] || {}).text }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="130">
            <template #default="{ row }">{{ relTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column v-if="isAuthor" label="操作" width="230">
            <template #default="{ row }">
              <template v-if="row.status === 0">
                <el-button size="small" type="success" @click="pass(row)">通过</el-button>
                <el-button size="small" type="danger" plain @click="reject(row)">拒绝</el-button>
                <el-button size="small" @click="confirm(row)">直接确认归还</el-button>
              </template>
              <el-button v-else-if="row.status === 1" size="small" type="primary" @click="confirm(row)">确认归还</el-button>
              <span v-else-if="row.status === 3" class="rej-reason">{{ row.rejectReason }}</span>
              <span v-else class="done">—</span>
            </template>
          </el-table-column>
          <el-table-column v-if="!isAuthor && canGovern" label="治理" width="90">
            <template #default="{ row }">
              <span v-if="row.status === 4" class="done">仲裁中</span>
              <span v-else class="done">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 相似物品推荐（开题文档"详情页相似推荐 Top5"口） -->
      <div v-if="similar.length" class="similar">
        <h3>相似物品推荐</h3>
        <div class="slist">
          <router-link v-for="s in similar" :key="s.id" :to="`/items/${s.id}`" class="srow">
            <el-tag size="small" :type="s.type === 'FOUND' ? 'success' : 'danger'" effect="plain">{{ s.type === 'FOUND' ? '招领' : '失物' }}</el-tag>
            <span class="stitle">{{ s.title }}</span>
            <span class="scat">{{ s.categoryName || '' }}</span>
            <span class="sscore">匹配度 {{ s.score }}</span>
          </router-link>
        </div>
      </div>

      <el-dialog v-model="claimDialog" title="申请认领" width="460px">
        <el-alert v-if="item && item.verifyQuestion" type="info" :closable="false"
                  :title="`核验问题：${item.verifyQuestion}`" class="mq" />
        <el-form label-position="top">
          <el-form-item label="你的答案（描述实物特征，让发布者认得出你）">
            <el-input v-model="claimForm.answer" type="textarea" :rows="2" maxlength="500" />
          </el-form-item>
          <el-form-item label="补充凭证（选填）">
            <el-input v-model="claimForm.evidenceNote" type="textarea" :rows="2" maxlength="500" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="claimDialog = false">取消</el-button>
          <el-button type="primary" @click="submitClaim">提交申请</el-button>
        </template>
      </el-dialog>
    </template>
    <el-empty v-else-if="!loading" description="物品不存在或无权查看">
      <el-button @click="router.push('/')">回大厅</el-button>
    </el-empty>
  </div>
</template>

<style scoped>
.wrap { max-width: 860px; margin: 0 auto; }
.page { padding: 24px 16px 48px; }
.panel { background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 22px 24px; }
.panel.off { opacity: .75; }
.panel.pending { border-color: #ffd9a0; }
.top { display: flex; align-items: center; gap: 8px; }
.cat { color: #909399; font-size: 13px; }
.fav { margin-left: auto; }
.title { margin: 12px 0 6px; font-size: 22px; color: #303133; }
.author { display: flex; justify-content: space-between; color: #909399; font-size: 13px; }
.desc { margin: 14px 0; color: #606266; line-height: 1.7; white-space: pre-wrap; }
.fields { margin-bottom: 14px; }
.hint { color: #909399; font-size: 12px; margin-left: 6px; }
.imgs { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 14px; }
.img { width: 130px; height: 130px; border-radius: 8px; border: 1px solid #ebeef5; }
.reason { margin-bottom: 14px; }
.actions { margin-top: 18px; display: flex; align-items: center; gap: 10px; }
.dead-tip { color: #c0c4cc; }
.similar { margin-top: 20px; background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 16px 22px; }
.similar h3 { margin: 0 0 10px; font-size: 15px; }
.slist { display: flex; flex-direction: column; gap: 8px; }
.srow { display: flex; align-items: center; gap: 10px; text-decoration: none; color: #303133; padding: 6px 8px; border-radius: 6px; }
.srow:hover { background: #f5f7fa; }
.stitle { font-weight: 600; flex: 1; }
.scat, .sscore { color: #909399; font-size: 12px; }
.claims { margin-top: 20px; background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 18px 22px; }
.claims h3 { margin: 0 0 12px; font-size: 16px; }
.evi { color: #909399; font-size: 12px; }
.rej-reason { color: #f56c6c; font-size: 12px; }
.done { color: #c0c4cc; }
.mq { margin-bottom: 10px; }
</style>
