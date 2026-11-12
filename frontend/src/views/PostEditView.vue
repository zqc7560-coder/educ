<script setup>
/** 发帖页（M10）：板块 + 标题 + 正文 + 0–9 图（先传后贴）。 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createPost, updatePost, fetchPost } from '@/api/forum'
import { fetchCategories } from '@/api/item'
import { uploadImages } from '@/api/common'
import { safeSrc } from '@/utils/format'

const route = useRoute()
const router = useRouter()
// new 与 :id/edit 复用本页（与物品发布页同款模式）；编辑必带 version（详情回显）
const id = computed(() => route.params.id)
const isEdit = computed(() => !!id.value)
const version = ref(0)

const boards = ref([])
const form = reactive({ boardId: null, title: '', content: '' })
const imageUrls = ref([])
const submitting = ref(false)

onMounted(async () => {
  boards.value = await fetchCategories('BOARD').catch(() => [])
  if (isEdit.value) {
    try {
      const p = await fetchPost(id.value)
      form.boardId = p.boardId
      form.title = p.title
      form.content = p.content
      imageUrls.value = p.images || []
      version.value = p.version ?? 0
    } catch { ElMessage.error('加载原帖失败') }
  }
})

async function pickImages(e) {
  const picked = Array.from(e.target.files || [])
  if (!picked.length) return
  if (imageUrls.value.length + picked.length > 9) return ElMessage.warning('最多 9 张图')
  try {
    const r = await uploadImages(picked, 'forum')
    imageUrls.value.push(...(r.urls || []))
  } catch { /* toast */ }
}
const removeImg = (i) => imageUrls.value.splice(i, 1)

async function submit() {
  if (!form.boardId) return ElMessage.warning('请选择板块')
  if (!form.title.trim()) return ElMessage.warning('请填写标题')
  if (!form.content.trim()) return ElMessage.warning('请填写正文')
  submitting.value = true
  try {
    if (isEdit.value) {
      await updatePost(id.value, { ...form, imageUrls: imageUrls.value, version: version.value })
      ElMessage.success('已保存（审核开启时将回到待审核）')
      router.push(`/forum/${id.value}`)
    } else {
      const vo = await createPost({ ...form, imageUrls: imageUrls.value })
      ElMessage.success(vo.status === 1 ? '已发布' : '已提交，待审核通过后展示')
      router.push(`/forum/${vo.id}`)
    }
  } catch { /* 2004/5007 等拦截器 toast */ } finally { submitting.value = false }
}
</script>

<template>
  <div class="wrap">
    <div class="panel">
      <h2>{{ isEdit ? '编辑帖子' : '发布帖子' }}</h2>
      <el-form label-width="60px">
        <el-form-item label="板块">
          <el-select v-model="form.boardId" placeholder="选择板块" style="width:200px">
            <el-option v-for="b in boards" :key="b.id" :label="b.name" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="form.title" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="正文">
          <el-input v-model="form.content" type="textarea" :rows="8" maxlength="5000" show-word-limit
                    placeholder="友善交流，理性讨论。图片最多 9 张（下方按钮添加）。" />
        </el-form-item>
        <el-form-item label="图片">
          <div class="imgs">
            <div v-for="u in imageUrls" :key="u" class="thumb">
              <img :src="safeSrc(u)" alt="" />
              <button class="del" @click.prevent="removeImg(imageUrls.indexOf(u))">×</button>
            </div>
            <label v-if="imageUrls.length < 9" class="up">
              <input type="file" accept="image/*" multiple hidden @change="pickImages" />
              <span>＋</span>
            </label>
          </div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="submit">{{ isEdit ? '保存' : '发布' }}</el-button>
          <el-button @click="router.back()">取消</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.wrap { max-width: 780px; margin: 0 auto; padding: 24px 16px 48px; }
.panel { background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 20px 24px; }
h2 { margin: 0 0 16px; font-size: 18px; }
.imgs { display: flex; flex-wrap: wrap; gap: 10px; }
.thumb { position: relative; width: 92px; height: 92px; }
.thumb img { width: 100%; height: 100%; object-fit: cover; border-radius: 8px; border: 1px solid #ebeef5; }
.del { position: absolute; top: -6px; right: -6px; width: 20px; height: 20px; border-radius: 50%; border: none; background: #f56c6c; color: #fff; cursor: pointer; }
.up { width: 92px; height: 92px; border: 1px dashed #c0c4cc; border-radius: 8px; display: flex; align-items: center; justify-content: center; cursor: pointer; color: #909399; font-size: 26px; }
.up:hover { border-color: #409eff; color: #409eff; }
</style>
