<script setup>
import { computed } from 'vue'

const props = defineProps({
  total: { type: Number, default: 0 },
  pageNum: { type: Number, default: 1 },
  pageSize: { type: Number, default: 10 },
  // 是否显示「每页条数」下拉，默认关闭（服务器列表等页面不受影响）
  showSize: { type: Boolean, default: false },
})

const emit = defineEmits(['change', 'size-change'])

// 可选的每页条数选项
const sizeOptions = [10, 20, 50, 100]

// 切换每页条数：向外抛出新的 pageSize，由父组件重置页码并重新查询
function onSizeChange(e) {
  emit('size-change', Number(e.target.value))
}

const pages = computed(() => {
  const totalPages = Math.max(Math.ceil(props.total / props.pageSize), 1)
  const cur = props.pageNum
  const list = []
  if (totalPages <= 7) {
    for (let i = 1; i <= totalPages; i++) list.push(i)
  } else {
    list.push(1)
    if (cur > 3) list.push('...')
    const start = Math.max(2, cur - 1)
    const end = Math.min(totalPages - 1, cur + 1)
    for (let i = start; i <= end; i++) list.push(i)
    if (cur < totalPages - 2) list.push('...')
    list.push(totalPages)
  }
  return list
})

function go(p) {
  if (p === '...' || p === props.pageNum || p < 1) return
  emit('change', p)
}
</script>

<template>
  <div class="pagination">
    <!-- 每页条数选择：showSize 开启时显示，切换后由父组件重置页码重新查询 -->
    <label v-if="showSize" class="size-select">
      每页
      <select :value="pageSize" @change="onSizeChange">
        <option v-for="s in sizeOptions" :key="s" :value="s">{{ s }} 条</option>
      </select>
    </label>
    <span>共 {{ total }} 条记录</span>
    <span class="divider">|</span>
    <button class="page-btn" :class="{ disabled: pageNum <= 1 }" @click="go(pageNum - 1)">⟨</button>
    <button
      v-for="(p, i) in pages"
      :key="i"
      class="page-btn"
      :class="{ active: p === pageNum, disabled: p === '...' }"
      @click="go(p)"
    >
      {{ p }}
    </button>
    <button
      class="page-btn"
      :class="{ disabled: pageNum >= Math.ceil(total / pageSize) }"
      @click="go(pageNum + 1)"
    >
      ⟩
    </button>
  </div>
</template>

<style scoped>
.pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: 14px 20px;
  font-size: 13px;
  color: var(--text-regular);
}
.divider {
  margin: 0 4px;
  color: var(--text-disabled);
}
/* 每页条数下拉：与分页按钮风格一致 */
.size-select {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-regular);
}
.size-select select {
  height: 32px;
  padding: 0 8px;
  border: 1px solid var(--input-border);
  border-radius: 6px;
  background: var(--card-bg);
  color: var(--text-regular);
  font-size: 13px;
  cursor: pointer;
  outline: none;
}
.size-select select:hover {
  border-color: #409eff;
}
.page-btn {
  min-width: 32px;
  height: 32px;
  padding: 0 6px;
  border: 1px solid var(--input-border);
  border-radius: 6px;
  background: var(--card-bg);
  color: var(--text-regular);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  transition: all 0.2s;
}
.page-btn:hover:not(.disabled):not(.active) {
  border-color: #409eff;
  color: #409eff;
}
.page-btn.active {
  background: #409eff;
  color: #fff;
  border-color: #409eff;
}
.page-btn.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
