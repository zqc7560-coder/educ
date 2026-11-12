<script setup>
/**
 * 发布 / 编辑物品（M10）：new 与 :id/edit 复用本页（route.params.id 区分）。
 *
 * 与后端契约逐条对齐：FOUND 必带核验问题（2003 后端拒，前端先拦省往返）；
 * type 编辑态锁死（后端禁改，翻转会让在途认领单语义坍塌）；编辑必带 version；
 * 图片走"先传后贴"：uploadImages 得 /uploads 相对路径再随表单提交。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchItem, createItem, updateItem, fetchCategories, fetchAreas } from '@/api/item'
import { uploadImages } from '@/api/common'
import { safeSrc } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const id = computed(() => route.params.id)
const isEdit = computed(() => !!id.value)

const form = reactive({
  type: 'LOST', title: '', description: '', categoryId: null,
  campusId: null, buildingId: null, locationText: '',
  happenedAt: '', contact: '', verifyQuestion: '', imageUrls: []
})
const version = ref(0)
const cats = ref([])
const areas = ref([])
const submitting = ref(false)

const buildings = computed(() =>
  areas.value.filter((a) => a.level === 2 && (!form.campusId || a.parentId === form.campusId)))

async function load() {
  cats.value = await fetchCategories('ITEM').catch(() => [])
  areas.value = await fetchAreas().catch(() => [])
  if (isEdit.value) {
    try {
      const it = await fetchItem(id.value)
      if (it.userId !== undefined) {
        Object.assign(form, {
          type: it.type, title: it.title, description: it.description,
          categoryId: it.categoryId || null, campusId: it.campusId || null,
          buildingId: it.buildingId || null, locationText: it.locationText || '',
          contact: it.contact || '', verifyQuestion: it.verifyQuestion || '',
          happenedAt: it.happenedAt ? String(it.happenedAt).replace('T', ' ').slice(0, 19) : '',
          imageUrls: it.images || []
        })
        version.value = it.version ?? 0
      }
    } catch { ElMessage.error('加载原物品失败'); }
  }
}
onMounted(load)

const files = ref([])
async function pickImages(e) {
  const picked = Array.from(e.target.files || [])
  if (!picked.length) return
  if (form.imageUrls.length + picked.length > 9) return ElMessage.warning('最多 9 张')
  try {
    const r = await uploadImages(picked, 'items')
    form.imageUrls.push(...(r.urls || []))
  } catch { /* 拦截器 toast */ }
}
function removeImg(i) { form.imageUrls.splice(i, 1) }

async function submit() {
  if (!form.title.trim()) return ElMessage.warning('请填写标题')
  if (!form.description.trim()) return ElMessage.warning('请填写描述')
  if (!form.categoryId) return ElMessage.warning('请选择分类')
  if (!form.happenedAt) return ElMessage.warning('请选择发生时间')
  if (form.type === 'FOUND' && !form.verifyQuestion.trim())
    return ElMessage.warning('招领信息必须设置核验问题（防冒领）')

  submitting.value = true
  const body = {
    type: form.type, title: form.title.trim(), description: form.description.trim(),
    categoryId: form.categoryId, campusId: form.campusId || undefined,
    buildingId: form.buildingId || undefined, locationText: form.locationText || undefined,
    happenedAt: form.happenedAt.replace(' ', 'T'), contact: form.contact || undefined,
    verifyQuestion: form.type === 'FOUND' ? form.verifyQuestion : undefined,
    imageUrls: form.imageUrls
  }
  try {
    if (isEdit.value) {
      body.version = version.value
      await updateItem(id.value, body)
      ElMessage.success('已保存')
      router.push(`/items/${id.value}`)
    } else {
      const r = await createItem(body)
      ElMessage.success(r.status === 1 ? '已发布' : '已提交，待审核通过后公开')
      router.push('/user/my-items')
    }
  } catch (err) {
    if (err.code === 2004) { ElMessage.warning('内容已被更新，请刷新后重试'); }
  } finally { submitting.value = false }
}
</script>

<template>
  <div class="wrap">
    <div class="panel">
      <h2>{{ isEdit ? '编辑信息' : '发布信息' }}</h2>
      <el-alert v-if="!isEdit" type="info" :closable="false" class="tip"
                title="捡到东西选『招领』并设置核验问题（例如“钱包里有什么”），认领人答对才通过——防冒领的关键一步。" />

      <el-form label-width="92px" label-position="left">
        <el-form-item label="类型">
          <el-radio-group v-model="form.type" :disabled="isEdit">
            <el-radio-button value="LOST">我丢了东西（失物）</el-radio-button>
            <el-radio-button value="FOUND">我捡到东西（招领）</el-radio-button>
          </el-radio-group>
          <div v-if="isEdit" class="lock">（类型发布后不可更改）</div>
        </el-form-item>

        <el-form-item label="标题">
          <el-input v-model="form.title" maxlength="100" show-word-limit placeholder="例如：丢失黑色钱包一个" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="2000" show-word-limit
                    placeholder="物品特征、丢失/捡到经过（联系人等隐私别写在这里）" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.categoryId" placeholder="选择分类" style="width:200px">
            <el-option v-for="c in cats" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>

        <el-form-item label="校区">
          <el-select v-model="form.campusId" placeholder="校区（可空）" clearable style="width:160px"
                     @change="form.buildingId = null">
            <el-option v-for="a in areas.filter(x => x.level === 1)" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
          <el-select v-model="form.buildingId" placeholder="楼栋（可空）" clearable style="width:160px;margin-left:8px">
            <el-option v-for="a in buildings" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
          <el-input v-model="form.locationText" style="width:220px;margin-left:8px" maxlength="200"
                    placeholder="更细的位置描述" />
        </el-form-item>

        <el-form-item label="发生时间">
          <el-date-picker v-model="form.happenedAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
                          placeholder="选择日期时间" style="width:220px" />
        </el-form-item>

        <el-form-item v-if="form.type === 'FOUND'" label="核验问题">
          <el-input v-model="form.verifyQuestion" maxlength="200" placeholder="例如：描述一下钱包内有什么" />
        </el-form-item>

        <el-form-item v-if="!isEdit || form.contact !== undefined" label="联系方式">
          <el-input v-model="form.contact" maxlength="100" placeholder="微信/QQ/手机（仅登录用户可见）" />
        </el-form-item>

        <el-form-item label="图片">
          <div class="imgs">
            <div v-for="(u, i) in form.imageUrls" :key="u" class="thumb">
              <img :src="safeSrc(u)" alt="" />
              <button class="del" @click.prevent="removeImg(i)">×</button>
            </div>
            <label v-if="form.imageUrls.length < 9" class="up">
              <input type="file" accept="image/*" multiple hidden @change="pickImages" />
              <span>＋</span>
            </label>
          </div>
          <div class="cnt">{{ form.imageUrls.length }}/9 张（png/jpg/gif/webp，≤5MB）</div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="submit">{{ isEdit ? '保存修改' : '发布' }}</el-button>
          <el-button @click="router.back()">取消</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.wrap { max-width: 760px; margin: 0 auto; padding: 24px 16px 48px; }
.panel { background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 22px 26px; }
h2 { margin: 0 0 6px; font-size: 19px; }
.tip { margin: 10px 0 18px; }
.lock { color: #c0c4cc; font-size: 12px; margin-left: 10px; }
.imgs { display: flex; flex-wrap: wrap; gap: 10px; }
.thumb { position: relative; width: 96px; height: 96px; }
.thumb img { width: 100%; height: 100%; object-fit: cover; border-radius: 8px; border: 1px solid #ebeef5; }
.del { position: absolute; top: -6px; right: -6px; width: 20px; height: 20px; border-radius: 50%; border: none; background: #f56c6c; color: #fff; cursor: pointer; }
.up { width: 96px; height: 96px; border: 1px dashed #c0c4cc; border-radius: 8px; display: flex; align-items: center; justify-content: center; cursor: pointer; color: #909399; font-size: 26px; }
.up:hover { border-color: #409eff; color: #409eff; }
.cnt { color: #909399; font-size: 12px; margin-top: 6px; width: 100%; }
</style>
