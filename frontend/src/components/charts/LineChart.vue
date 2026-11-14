<script setup>
import { computed, ref } from 'vue'
import { useThemeStore } from '@/stores/theme'

const props = defineProps({
  series: { type: Array, default: () => [] },
  timestamps: { type: Array, default: () => [] },
  height: { type: Number, default: 220 },
  unit: { type: String, default: '' },
  yMax: { type: Number, default: null },
  areaFill: { type: Boolean, default: true },
  dark: { type: Boolean, default: false },
  hoverIndex: { type: Number, default: null },
  // 修改：新增 maxFillGap —— 连续空值点数不超过该值时用前值填充（曲线保持连续），
  // 超过则保留空值（折线断开留缺口，直观体现"该时段无数据"）。默认 5。
  maxFillGap: { type: Number, default: 5 },
})

const emit = defineEmits(['hover'])

const themeStore = useThemeStore()
const isDark = computed(() => props.dark || themeStore.mode === 'dark')

const W = 800
const H = 240
const PAD_L = 44
const PAD_R = 12
const PAD_T = 12
const PAD_B = 24

const localHover = ref(-1)
const hoverIndex = computed(() =>
  props.hoverIndex != null ? props.hoverIndex : localHover.value
)
const svgRef = ref(null)

// 修改：小规模空值（连续空值点数 ≤ maxFillGap）用前值填充，折线保持连续；
// 大规模空值（> maxFillGap）保留 null，折线在缺口处断开、留缺口。
function fillSmallGaps(data) {
  if (!Array.isArray(data)) return []
  const arr = data.slice()
  const n = arr.length
  const maxGap = Math.max(0, Number(props.maxFillGap) || 0)
  let i = 0
  while (i < n) {
    if (arr[i] == null || !Number.isFinite(arr[i])) {
      let j = i
      while (j < n && (arr[j] == null || !Number.isFinite(arr[j]))) j++
      const gap = j - i
      const prev = i > 0 ? arr[i - 1] : null
      // 仅当间隙前面存在有效值（可作前值）且间隙不超过阈值时才填充
      if (prev != null && gap > 0 && gap <= maxGap) {
        for (let k = i; k < j; k++) arr[k] = prev
      }
      i = j
    } else {
      i++
    }
  }
  return arr
}

// 处理后的展示数据：对每个系列的 data 应用小间隙填充；
// 时间轴仍由调用方保证线性均匀，此处不改变数组长度。
const preparedSeries = computed(() =>
  props.series.map((s) => ({ ...s, data: fillSmallGaps(s.data) }))
)

const allValues = computed(() =>
  preparedSeries.value.flatMap((s) => (s.data || []).filter((v) => Number.isFinite(v)))
)
const maxVal = computed(() => {
  const m = Math.max(...allValues.value, 0)
  if (props.yMax != null) return props.yMax
  if (m <= 0) return 10
  const mag = Math.pow(10, Math.floor(Math.log10(m)))
  return Math.ceil((m * 1.1) / mag) * mag
})
const minVal = computed(() => Math.min(...allValues.value, 0))

const xCount = computed(() =>
  preparedSeries.value.reduce((n, s) => Math.max(n, (s.data || []).length), 0)
)

// 数据点已重采样为固定间隔的均匀网格（如每 5 秒一个点），
// 因此横坐标按下标等距分布即等价于按时间线性均匀分布。
// timestamps 仅用于显示时间标签与悬浮提示。
function xPos(i) {
  const n = xCount.value
  if (n <= 1) return PAD_L
  return PAD_L + ((W - PAD_L - PAD_R) * i) / (n - 1)
}

function yPos(v) {
  if (!Number.isFinite(v)) return PAD_T // NaN/undefined/null 时落到顶部，避免 SVG 报错
  const usable = H - PAD_T - PAD_B
  return PAD_T + usable * (1 - (v - minVal.value) / (maxVal.value - minVal.value || 1))
}

function pointsOf(data) {
  // 修改：空值（null / NaN）处折线断开、留缺口，多段 M ... L ... 绘制，
  // 直观体现"该时段无数据"。
  // （说明：时间轴本身由调用方保证线性均匀，空值点仍占位，只是折线在缺口处断开）
  const segments = []
  let current = []
  for (let i = 0; i < data.length; i++) {
    if (data[i] == null || !Number.isFinite(data[i])) {
      if (current.length) {
        segments.push(current.join(' '))
        current = []
      }
    } else {
      current.push(`${xPos(i)},${yPos(data[i])}`)
    }
  }
  if (current.length) segments.push(current.join(' '))
  return segments
}

function areaPoints(data) {
  if (!data.length) return []
  // 修改：与折线一致，空值（null / NaN）处面积分段断开、留缺口。
  // 每段的左右底边分别落在段中第一个/最后一个有效点的 x 坐标，
  // 避免相隔较远的间断段从同一左下角辐射出"扇形"填充。
  const segments = []
  let current = []
  let startIdx = -1
  function flush(endIdx) {
    if (current.length < 1) return
    const lastIdx = endIdx
    const firstIdx = lastIdx - (current.length - 1)
    segments.push(`${xPos(firstIdx)},${H - PAD_B} ${current.join(' ')} ${xPos(lastIdx)},${H - PAD_B}`)
  }
  for (let i = 0; i < data.length; i++) {
    if (data[i] == null || !Number.isFinite(data[i])) {
      if (current.length) {
        flush(i - 1)
        current = []
        startIdx = -1
      }
    } else {
      if (startIdx < 0) startIdx = i
      current.push(`${xPos(i)},${yPos(data[i])}`)
    }
  }
  if (current.length) flush(data.length - 1)
  return segments
}

const yTicks = computed(() => {
  const ticks = []
  const steps = 4
  for (let i = 0; i <= steps; i++) {
    const v = minVal.value + ((maxVal.value - minVal.value) * i) / steps
    ticks.push({ v, y: yPos(v) })
  }
  return ticks
})

const xLabels = computed(() => {
  const n = xCount.value
  if (!n || !props.timestamps.length) return []
  const count = Math.min(5, n)
  const labels = []
  for (let i = 0; i < count; i++) {
    const idx = Math.round((i * (n - 1)) / Math.max(count - 1, 1))
    labels.push({ x: xPos(idx), text: formatTime(props.timestamps[idx]) })
  }
  return labels
})

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  return `${hh}:${mm}:${ss}`
}

function fmtVal(v) {
  if (v == null) return '-'
  const abs = Math.abs(v)
  if (abs >= 1000) return (v / 1000).toFixed(1) + 'k'
  return abs >= 100 ? v.toFixed(0) : v.toFixed(1)
}

function setHover(i) {
  localHover.value = i
  emit('hover', i)
}

function onMove(e) {
  const rect = svgRef.value.getBoundingClientRect()
  const x = ((e.clientX - rect.left) / rect.width) * W
  const n = xCount.value
  if (!n) return
  const ratio = (x - PAD_L) / (W - PAD_L - PAD_R)
  setHover(Math.round(ratio * (n - 1)))
}

const hoverValues = computed(() => {
  if (hoverIndex.value < 0) return []
  return preparedSeries.value
    .filter((s) => s.data && Number.isFinite(s.data[hoverIndex.value]))
    .map((s) => ({ name: s.name, color: s.color, value: s.data[hoverIndex.value] }))
})

const hoverX = computed(() => (hoverIndex.value >= 0 ? xPos(hoverIndex.value) : 0))
</script>

<template>
  <div class="line-chart" :style="{ height: height + 'px' }">
    <svg
      ref="svgRef"
      :viewBox="`0 0 ${W} ${H}`"
      preserveAspectRatio="none"
      @mousemove="onMove"
      @mouseleave="setHover(-1)"
    >
      <defs>
        <linearGradient
          v-for="(s, si) in preparedSeries"
          :key="'g' + si"
          :id="`lc-grad-${si}`"
          x1="0"
          y1="0"
          x2="0"
          y2="1"
        >
          <stop offset="0%" :stop-color="s.color" stop-opacity="0.25" />
          <stop offset="100%" :stop-color="s.color" stop-opacity="0" />
        </linearGradient>
      </defs>

      <!-- 网格 -->
      <g v-for="t in yTicks" :key="t.y">
        <line
          :x1="PAD_L"
          :y1="t.y"
          :x2="W - PAD_R"
          :y2="t.y"
          :stroke="isDark ? '#2A2F45' : '#E4E7ED'"
          stroke-width="1"
          stroke-dasharray="3,3"
        />
        <text :x="PAD_L - 6" :y="t.y + 3" text-anchor="end" font-size="10" :fill="isDark ? '#7A86A0' : '#909399'">{{ fmtVal(t.v) }}</text>
      </g>

      <!-- 面积 + 折线 -->
      <template v-for="(s, si) in preparedSeries" :key="'s' + si">
        <path
          v-for="(seg, j) in areaPoints(s.data)"
          :key="'a' + si + '-' + j"
          :d="`M ${seg}`"
          :fill="`url(#lc-grad-${si})`"
        />
        <path
          v-for="(seg, j) in pointsOf(s.data)"
          :key="'l' + si + '-' + j"
          :d="`M ${seg}`"
          fill="none"
          :stroke="s.color"
          :stroke-width="s.width || 2"
          :stroke-dasharray="s.dash ? '5,3' : 'none'"
          stroke-linejoin="round"
          stroke-linecap="round"
        />
      </template>

      <!-- X轴时间标签 -->
      <text
        v-for="(l, i) in xLabels"
        :key="'x' + i"
        :x="l.x"
        :y="H - 6"
        text-anchor="middle"
        font-size="10"
        :fill="isDark ? '#7A86A0' : '#909399'"
      >{{ l.text }}</text>

      <!-- 悬停指示线 -->
      <g v-if="hoverIndex >= 0">
        <line :x1="hoverX" :y1="PAD_T" :x2="hoverX" :y2="H - PAD_B" :stroke="isDark ? '#3A3F55' : '#C0C4CC'" stroke-width="1" />
        <circle
          v-for="(s, si) in preparedSeries"
          v-show="s.data && Number.isFinite(s.data[hoverIndex])"
          :key="'h' + si"
          :cx="hoverX"
          :cy="yPos(s.data[hoverIndex])"
          r="4"
          :fill="s.color"
          stroke="#fff"
          stroke-width="1.5"
        />
      </g>
    </svg>

    <!-- 悬浮提示 -->
    <div
      v-if="hoverIndex >= 0 && hoverValues.length"
      class="chart-tooltip"
      :style="{ left: (hoverX / W) * 100 + '%' }"
    >
      <div class="tooltip-time">{{ formatTime(timestamps[hoverIndex]) }}</div>
      <div v-for="hv in hoverValues" :key="hv.name" class="tooltip-row">
        <span class="dot" :style="{ background: hv.color }"></span>
        <span class="name">{{ hv.name }}</span>
        <span class="val">{{ hv.value.toFixed(1) }}{{ unit }}</span>
      </div>
    </div>

    <!-- 图例 -->
    <div class="chart-legend" :style="{ color: isDark ? '#7A86A0' : '#909399' }">
      <span v-for="(s, si) in preparedSeries" :key="'l' + si" class="legend-item">
        <span class="legend-line" :style="{ background: s.color }"></span>{{ s.name }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.line-chart {
  position: relative;
  width: 100%;
}
.line-chart svg {
  width: 100%;
  height: calc(100% - 22px);
  display: block;
  cursor: crosshair;
}
.chart-tooltip {
  position: absolute;
  top: 8px;
  transform: translateX(-50%);
  background: rgba(48, 49, 51, 0.92);
  color: #fff;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 12px;
  pointer-events: none;
  white-space: nowrap;
  z-index: 10;
}
.tooltip-time {
  color: #a8abb2;
  margin-bottom: 4px;
}
.tooltip-row {
  display: flex;
  align-items: center;
  gap: 6px;
  line-height: 1.7;
}
.tooltip-row .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.tooltip-row .name {
  color: #cfd4dc;
  margin-right: 8px;
}
.tooltip-row .val {
  font-weight: 600;
}
.chart-legend {
  display: flex;
  gap: 16px;
  justify-content: center;
  padding-top: 4px;
  font-size: 11px;
  color: #909399;
}
.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.legend-line {
  width: 14px;
  height: 3px;
  border-radius: 2px;
}
</style>
