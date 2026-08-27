<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import Cover from '@/components/common/Cover.vue'

// 局部类型而不是直接用 ImageNodeSummary：往后 ImageSearchView 的搜索命中
// （形状更窄，字段名也可能不完全一样）会传别的对象过来。只要满足这七个字段，
// 结构类型系统就认——不用为了喂给 BookCard 反过来在 ImageNodeSummary 上加字段。
interface BookCardNode {
  id: number
  displayName: string
  coverAssetId: number | null
  readable: boolean
  browsable: boolean
  totalPageCount: number
  childNodeCount: number
}

const props = defineProps<{
  node: BookCardNode
  progress?: { pageIndex: number; totalPageCount: number }
}>()

const router = useRouter()

/**
 * readable 与 browsable 是两个独立的布尔值，不是一个 type 字段
 * （后端 ImageNodeDto.NodeSummary 的注释写明了这一点）。
 *
 * 一个目录既有散图又有子目录时两者同时为真——Perfect Viewer 正是这样处理的，
 * 后端的 image_node 也照此建模。这时默认进「浏览」，卡片右下角另给一个
 * 「阅读」小入口，让用户自己选。不要替用户二选一。
 */
const target = computed(() => {
  if (props.node.readable && !props.node.browsable) {
    return { name: 'image-read', params: { id: props.node.id } }
  }
  return { name: 'image-node', params: { id: props.node.id } }
})

// 两者皆为真时卡片本身进「浏览」，另外露出一个「阅读」小入口——
// 而不是隐藏在 hover 之后：触屏设备没有 hover，隐藏等于让这条路径消失。
const showReadButton = computed(() => props.node.readable && props.node.browsable)

function goRead(): void {
  router.push({ name: 'image-read', params: { id: props.node.id } })
}
</script>

<template>
  <!--
    .book 本身是一个纯容器 div，不是链接：HTML5 不允许交互内容互相嵌套
    （<a> 里不能合法地放 <button>）。「阅读」这个入口的意义正是给用户一个
    真实的二选一（不要替用户二选一），如果因为嵌套在 <a> 里而被部分辅助技术
    忽略，这个入口就白做了。所以拆成两个 RouterLink（封面一个、标题+元信息
    一个）夹着一个真正独立的 <button>，三者是兄弟节点，不是谁包着谁。
  -->
  <div class="book">
    <div class="volume">
      <!-- ★ 签名元素：书脊。
           一条 4px 的竖向渐变，让封面读起来是一本有厚度的册子而不是一张缩略图。
           它用两行 CSS 表达了后端整套 image_node 树最核心的主张：
           图片域的单位是「一本」，不是「一张」。 -->
      <span class="spine" aria-hidden="true" />
      <RouterLink :to="target" class="cover-link" :aria-label="node.displayName">
        <Cover :asset-id="node.coverAssetId" ratio="2/3" :alt="node.displayName" />
      </RouterLink>
      <button v-if="showReadButton" type="button" class="read-btn" @click="goRead">
        阅读
      </button>
    </div>
    <RouterLink :to="target" class="text-link">
      <h3 class="title">{{ node.displayName }}</h3>
      <p class="meta">
        <template v-if="progress">第 {{ progress.pageIndex + 1 }} / {{ progress.totalPageCount }} 页</template>
        <template v-else>
          <template v-if="node.readable">{{ node.totalPageCount }} 页</template>
          <template v-if="node.readable && node.browsable"> · </template>
          <template v-if="node.browsable">{{ node.childNodeCount }} 项</template>
        </template>
      </p>
    </RouterLink>
  </div>
</template>

<style scoped>
.cover-link,
.text-link {
  display: block;
  color: inherit;
  text-decoration: none;
}

.volume {
  position: relative;
  padding-left: 4px;   /* 给书脊让出位置 */
  border-radius: 0 var(--radius) var(--radius) 0;
  overflow: hidden;
  /* 图片域的高光是"投影"——黑色落影，模拟灯下桌面上的实体。
     视频域用的是彩色外发光。--elevation 在两域各自定义（tokens.css）。 */
  box-shadow: var(--elevation);
  transition: transform var(--dur-base) var(--ease), box-shadow var(--dur-base) var(--ease);
}

.spine {
  position: absolute;
  inset: 0 auto 0 0;
  width: 4px;
  z-index: 1;
  /* 亮—暗—亮：一道从书脊圆弧上反射的高光。纯色会读成一条边框，不是一个体积 */
  background: linear-gradient(
    to right,
    rgb(255 255 255 / 0.22) 0%,
    rgb(0 0 0 / 0.55) 55%,
    rgb(255 255 255 / 0.1) 100%
  );
}

/*
 * .book 不再是单一的可聚焦元素（它是纯容器 div），hover/focus 状态改从
 * .book 本身取——鼠标悬停在封面链接、标题链接或按钮任一子元素上都会让
 * .book:hover 成立（CSS :hover 对祖先同样生效）；键盘 Tab 到任一子元素时
 * :focus-within 成立，效果同上。
 */
.book:hover .volume,
.book:focus-within .volume {
  /* 抬起 2px，影子跟着变深——一个被拿起来的实体。
     刻意不做 scale：书不会因为你看它一眼就变大 */
  transform: translateY(-2px);
  box-shadow: 0 4px 8px rgb(0 0 0 / 0.55), 0 20px 40px -10px rgb(0 0 0 / 0.75);
}

.read-btn {
  position: absolute;
  right: var(--space-2);
  bottom: var(--space-2);
  z-index: 1;
  padding: var(--space-1) var(--space-3);
  border: none;
  border-radius: var(--radius);
  background: var(--accent-dim);
  color: var(--accent);
  font-family: var(--font-body);
  font-size: var(--step--1);
  font-weight: 600;
  cursor: pointer;
  transition: filter var(--dur-fast) var(--ease);
}

.read-btn:hover {
  filter: brightness(1.2);
}

.title {
  margin: var(--space-3) 0 0;
  font-family: var(--display);
  font-size: var(--step-0);
  font-weight: 500;
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.meta {
  margin: var(--space-1) 0 0;
  font-family: var(--font-data);
  font-variant-numeric: tabular-nums;
  font-size: var(--step--1);
  color: var(--dim);
}
</style>
