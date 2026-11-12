<script setup>
/**
 * 帖子详情（M10）：正文+图、点赞幂等按钮、两级回复树（回复楼中楼归一到一级 parent）、
 * 举报、作者/治理操作面（编辑/删除/审核/下架/置顶）。
 *
 * 两级封顶的前端配合（后端 5006 兜底）：对二级回复点"回复"时，parentId 归一为
 * 其所在一级回复的 id，@对象传 replyToUserId——与 ReplyDTO 契约一致。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DOMPurify from 'dompurify'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchPost, fetchReplyTree, replyPost, deleteReply, likePost, unlikePost, reportPost, deletePost, changePostStatus } from '@/api/forum'
import { toggleFavorite } from '@/api/common'
import { useUserStore } from '@/stores/user'
import { fmtTime, relTime, safeSrc } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const post = ref(null)
const tree = ref([])
const loading = ref(true)
const canAudit = computed(() => (store.perms || []).includes('forum:audit'))
const isAuthor = computed(() => post.value && store.user && post.value.userId === store.user.id)

// 后端 TextCleaner 已剥标签入库；渲染再经 DOMPurify 消毒（XSS 双防线第二道）
const safeHtml = (t) => DOMPurify.sanitize(String(t || '').replace(/\n/g, '<br>'))

async function loadAll() {
  loading.value = true
  try {
    post.value = await fetchPost(route.params.id)
    tree.value = await fetchReplyTree(route.params.id).catch(() => [])
  } catch { post.value = null } finally { loading.value = false }
}
onMounted(loadAll)
watch(() => route.params.id, loadAll)

// ---------- 点赞 ----------
async function doLike() {
  if (!store.isLoggedIn) return goLogin()
  try {
    if (post.value.liked) { await unlikePost(post.value.id) } else { await likePost(post.value.id) }
    post.value.liked = !post.value.liked
    post.value.likeCount += post.value.liked ? 1 : -1
  } catch { /* toast */ }
}
async function doFav() {
  if (!store.isLoggedIn) return goLogin()
  try {
    const r = await toggleFavorite('POST', post.value.id)
    ElMessage.success(r.favorited ? '已收藏' : '已取消收藏')
  } catch { /* toast */ }
}
const goLogin = () => router.push({ path: '/login', query: { redirect: route.fullPath } })

// ---------- 回复（对话框统一口：root=一级楼 null=发一级评论，target=被@者） ----------
const replyDlg = ref(false)
const replyForm = ref({ root: null, target: null, content: '' })
function openReply(root, target = null) {
  if (!store.isLoggedIn) return goLogin()
  replyForm.value = { root, target, content: '' }
  replyDlg.value = true
}
async function submitReply() {
  if (!replyForm.value.content.trim()) return ElMessage.warning('写点什么吧')
  try {
    const body = { content: replyForm.value.content }
    // 两级封顶归一：对二级回复点回复 → parentId 传其所在一级楼 id，@ 用 replyToUserId
    if (replyForm.value.root) body.parentId = replyForm.value.root.id
    if (replyForm.value.target && replyForm.value.target.userId !== undefined)
      body.replyToUserId = replyForm.value.target.userId
    await replyPost(post.value.id, body)
    ElMessage.success('已发布，审核通过后展示')
    replyDlg.value = false
    loadAll()
  } catch { /* 5006/5007 拦截器 toast */ }
}

async function delReply(r) {
  try {
    await ElMessageBox.confirm('删除这条回复？', '删除', { type: 'warning' })
    await deleteReply(r.id)
    ElMessage.success('已删除')
    loadAll()
  } catch { /* 取消 */ }
}

// ---------- 帖子治理 ----------
async function act(fn, msg) { try { await fn(); ElMessage.success(msg); loadAll() } catch { /* toast */ } }
const edit = () => router.push(`/forum/${post.value.id}/edit`)
async function delPost() {
  try {
    await ElMessageBox.confirm('删除帖子（回复一并不可见）？', '删除', { type: 'warning' })
    await deletePost(post.value.id)
    ElMessage.success('已删除')
    router.push('/forum')
  } catch { /* 取消 */ }
}
async function audit(pass) {
  if (pass) return act(() => changePostStatus(post.value.id, 1), '已通过并发布')
  try {
    const { value } = await ElMessageBox.prompt('下架理由', '驳回/下架', { inputPlaceholder: '必填' })
    if (!value?.trim()) return ElMessage.warning('理由必填')
    await act(() => changePostStatus(post.value.id, 2, value), '已下架')
  } catch { /* 取消 */ }
}
async function report() {
  if (!store.isLoggedIn) return goLogin()
  try {
    const { value } = await ElMessageBox.prompt('举报理由（转管理员审核队列处置）', '举报帖子', { inputPlaceholder: '例如：广告/不实信息' })
    await act(() => reportPost(post.value.id, value || '举报'), '已提交举报')
  } catch { /* 取消 */ }
}

/** 把后端 reply_to_nickname 高亮进正文（纯文本安全拼接） */
const at = (r) => r.replyToNickname ? `@${r.replyToNickname} ：` : ''
</script>

<template>
  <div class="wrap page" v-loading="loading">
    <template v-if="post">
      <article class="post">
        <div class="tags">
          <el-tag size="small" effect="plain">{{ post.boardName }}</el-tag>
          <el-tag v-if="post.topFlag === 1" size="small" type="danger" effect="dark">置顶</el-tag>
          <el-tag v-if="post.status !== 1" size="small" :type="post.status === 0 ? 'warning' : 'info'">
            {{ post.status === 0 ? '待审核' : '已下架' }}
          </el-tag>
        </div>
        <h1>{{ post.title }}</h1>
        <div class="author">
          <el-avatar :size="30" :src="post.avatar || undefined">{{ (post.nickname || '匿')[0] }}</el-avatar>
          <span>{{ post.nickname }}</span>
          <span class="dim">{{ fmtTime(post.publishedAt || post.createdAt) }} · 阅读 {{ post.viewCount }}</span>
          <div class="ops">
            <el-button size="small" :type="post.liked ? 'primary' : 'default'" @click="doLike">
              {{ post.liked ? '👍 已赞' : '👍 点赞' }} {{ post.likeCount }}
            </el-button>
            <el-button size="small" @click="doFav">☆ 收藏</el-button>
            <el-button size="small" link type="warning" @click="report">举报</el-button>
          </div>
        </div>
        <div class="body" v-html="safeHtml(post.content)"></div>
        <div v-if="post.images && post.images.length" class="imgs">
          <el-image v-for="(u, i) in post.images" :key="i" :src="safeSrc(u)" fit="cover" class="img"
                    :preview-src-list="post.images.map(safeSrc).filter(Boolean)" :initial-index="i" preview-teleported />
        </div>

        <div v-if="isAuthor || canAudit" class="govern">
          <el-button v-if="isAuthor" size="small" @click="edit">编辑</el-button>
          <el-button v-if="isAuthor" size="small" type="danger" plain @click="delPost">删除</el-button>
          <template v-if="canAudit">
            <el-button v-if="post.status === 0" size="small" type="success" @click="audit(true)">审核通过</el-button>
            <el-button v-if="post.status !== 2" size="small" type="warning" @click="audit(false)">下架</el-button>
          </template>
        </div>
      </article>

      <section class="replies">
        <h3>回复（{{ post.replyCount }}）</h3>
        <div class="newreply">
          <el-button type="primary" plain @click="openReply(null)">发表评论</el-button>
        </div>

        <div v-for="root in tree" :key="root.id" class="root">
          <div class="rline">
            <el-avatar :size="26" :src="root.avatar || undefined">{{ (root.nickname || '匿')[0] }}</el-avatar>
            <b>{{ root.nickname }}</b>
            <span class="dim">{{ relTime(root.createdAt) }}</span>
            <span v-if="root.status === 0" class="pend">（待审核）</span>
            <el-button size="small" link type="primary" @click="openReply(root, root)">回复</el-button>
            <el-button v-if="isAuthor || root.userId === store.user?.id" size="small" link type="danger" @click="delReply(root)">删除</el-button>
          </div>
          <div class="rbody" v-html="safeHtml(root.content)"></div>
          <div v-for="child in (root.children || [])" :key="child.id" class="child">
            <div class="rline">
              <b class="who">{{ child.nickname }}</b>
              <span class="dim">回复 {{ at(child).replace('：', '') }}</span>
              <span class="dim">{{ relTime(child.createdAt) }}</span>
              <span v-if="child.status === 0" class="pend">（待审核）</span>
              <el-button size="small" link type="primary" @click="openReply(root, child)">回复</el-button>
              <el-button v-if="isAuthor || child.userId === store.user?.id" size="small" link type="danger" @click="delReply(child)">删除</el-button>
            </div>
            <div class="rbody" v-html="safeHtml(child.content)"></div>
          </div>
        </div>
        <el-empty v-if="!tree.length" :image-size="60" description="还没有回复" />
      </section>
    </template>
    <el-empty v-else-if="!loading" description="帖子不存在、待审核或已删除">
      <el-button @click="router.push('/forum')">回论坛</el-button>
    </el-empty>

    <el-dialog v-model="replyDlg" :title="replyForm.root ? (replyForm.target && replyForm.target.id !== replyForm.root.id ? `回复 @${replyForm.target.nickname}` : '回复该楼') : '发表评论'" width="520px">
      <el-input v-model="replyForm.content" type="textarea" :rows="4" maxlength="1000" show-word-limit
                placeholder="友善回复，理性讨论" />
      <template #footer>
        <el-button @click="replyDlg = false">取消</el-button>
        <el-button type="primary" @click="submitReply">发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.wrap { max-width: 860px; margin: 0 auto; }
.page { padding: 20px 16px 48px; }
.post { background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 22px 26px; }
.tags { display: flex; gap: 8px; }
h1 { margin: 10px 0; font-size: 21px; }
.author { display: flex; align-items: center; gap: 10px; color: #606266; font-size: 13px; margin-bottom: 12px; }
.dim { color: #909399; font-size: 12px; }
.ops { margin-left: auto; display: flex; gap: 6px; }
.body { color: #303133; line-height: 1.8; font-size: 15px; }
.imgs { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 12px; }
.img { width: 130px; height: 130px; border-radius: 8px; }
.govern { margin-top: 16px; display: flex; gap: 8px; }
.replies { margin-top: 16px; background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 18px 22px; }
.replies h3 { margin: 0 0 12px; font-size: 16px; }
.newreply { margin-bottom: 14px; }
.nr-ops { margin-top: 8px; display: flex; align-items: center; gap: 8px; }
.root { border-top: 1px solid #f0f2f5; padding: 12px 0; }
.rline { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.rbody { margin: 6px 0 0 34px; color: #303133; line-height: 1.6; font-size: 14px; }
.child { margin: 8px 0 0 34px; background: #f8f9fb; border-radius: 6px; padding: 8px 12px; }
.child .rbody { margin-left: 0; }
.who { color: #409eff; }
.pend { color: #e6a23c; font-size: 12px; }
</style>
