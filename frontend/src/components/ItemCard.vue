<script setup>
/**
 * 物品卡片（M10 共享件）：大厅/我的发布/收藏/回收站四处复用。
 * 字段按视图裁剪（VO 装配已分场景），点击整卡进详情；status≠1 时由调用方
 * 通过 showStatus 打开状态角标（作者视角/治理视角才需要）。
 * 封面轮播（用户要求"每张卡片可轮播自己那组图，一张就不轮"）：
 * - images 多图 → 卡片内小轮播（3.5s 自动、可手动箭头/圆点，hover 暂停）；
 * - 仅 1 图 → 静态一张；0 图 → 不占位。
 * - contain 完整展示：任意宽高比不裁切，白底居中自适应缩放。
 * - 轮播内的点击不能冒成"跳详情"——箭头/圆点 stop，图片本身点击跳详情（就是要看这件）。
 */
import { computed } from 'vue'
import { fmtTime, safeSrc, statusTag } from '@/utils/format'

const props = defineProps({
  item: { type: Object, required: true },
  showStatus: { type: Boolean, default: false }
})
const typeLabel = computed(() => (props.item.type === 'FOUND' ? '招领' : '失物'))
const typeColor = computed(() => (props.item.type === 'FOUND' ? 'success' : 'danger'))
const st = computed(() => statusTag(props.item.status))
// 图集：列表接口现在直接带有序 images（首图=coverUrl）；老数据兜底 coverUrl
const gallery = computed(() => {
  const arr = (props.item.images || []).map(safeSrc).filter(Boolean)
  if (arr.length) return arr
  return props.item.coverUrl && safeSrc(props.item.coverUrl) ? [safeSrc(props.item.coverUrl)] : []
})
</script>

<template>
  <div class="card">
    <div class="head">
      <el-tag v-if="item.topFlag === 1" size="small" type="danger" effect="dark">置顶</el-tag>
      <el-tag :type="typeColor" size="small" effect="dark">{{ typeLabel }}</el-tag>
      <el-tag v-if="showStatus" :type="st.type" size="small">{{ st.text }}</el-tag>
      <span class="cat">{{ item.categoryName || '未分类' }}</span>
    </div>

    <!-- 多图：卡片内小轮播 -->
    <el-carousel v-if="gallery.length > 1" :interval="3500" class="cover-carousel" arrow="hover"
                 :autoplay="true" indicator-position="inside" height="150px" trigger="click">
      <el-carousel-item v-for="(u, i) in gallery" :key="i">
        <router-link :to="`/items/${item.id}`" class="cover-box" tabindex="-1">
          <img :src="u" alt="" class="cover-img" loading="lazy" />
        </router-link>
      </el-carousel-item>
    </el-carousel>
    <!-- 单图：静态不轮 -->
    <router-link v-else-if="gallery.length === 1" :to="`/items/${item.id}`" class="cover-box" tabindex="-1">
      <img :src="gallery[0]" alt="" class="cover-img" loading="lazy" />
    </router-link>

    <router-link :to="`/items/${item.id}`" class="title">{{ item.title }}</router-link>
    <div class="desc">{{ item.description }}</div>
    <div class="meta">
      <span>{{ item.userNickname || '匿名' }}</span>
      <span>{{ fmtTime(item.publishedAt || item.createdAt) }}</span>
      <span>浏览 {{ item.viewCount ?? 0 }} · 申请 {{ item.claimCount ?? 0 }}</span>
    </div>
  </div>
</template>

<style scoped>
.card { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; padding: 14px 16px; display: flex; flex-direction: column; gap: 8px; transition: box-shadow .15s; }
.card:hover { box-shadow: 0 2px 12px rgba(0,0,0,.08); }
.head { display: flex; align-items: center; gap: 8px; }
.cat { color: #909399; font-size: 12px; margin-left: auto; }
/* 封面：定高框 + contain——任何宽高比完整显示不裁切，白底居中自适应 */
.cover-box { display: block; height: 150px; border-radius: 6px; overflow: hidden; background: #f7f8fa; border: 1px solid #f0f2f5; text-decoration: none; }
.cover-img { width: 100%; height: 100%; object-fit: contain; display: block; }
.cover-carousel { border-radius: 6px; overflow: hidden; background: #f7f8fa; border: 1px solid #f0f2f5; }
.cover-carousel :deep(.el-carousel__container) { height: 150px; }
/* 圆点缩小并抬高，别压住图 */
.cover-carousel :deep(.el-carousel__indicators--inside) { bottom: 4px; }
.cover-carousel :deep(.el-carousel__indicator .el-carousel__button) { width: 14px; height: 4px; border-radius: 2px; opacity: .55; }
.title { color: #303133; font-weight: 600; text-decoration: none; font-size: 15px; line-height: 1.4; }
.title:hover { color: #409eff; }
.desc { color: #606266; font-size: 13px; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.meta { display: flex; gap: 14px; color: #909399; font-size: 12px; }
</style>
