/**
 * 固定容量的环形缓冲区（Ring Buffer）
 * - 预分配 capacity 个槽位，内存只分配一次，运行期零 GC 压力
 * - push 时若已满则覆盖最旧元素（head 前移），无需 shift 搬移整段数组
 * - 数据始终保持「时间升序」语义：head 指向最旧，tail 前一个为最新
 */
export class RingBuffer {
  /**
   * @param {number} capacity 最大容量（按所选范围的数据点数预估算定）
   * @param {(a:any,b:any)=>number} [compare] 排序比较器，默认按 .t 升序
   */
  constructor(capacity, compare = (a, b) => a.t - b.t) {
    this.capacity = capacity
    this.compare = compare
    this.buf = new Array(capacity)
    this.head = 0 // 最旧元素下标
    this.size = 0 // 当前元素个数
  }

  get length() {
    return this.size
  }

  isFull() {
    return this.size === this.capacity
  }

  clear() {
    this.head = 0
    this.size = 0
  }

  /**
   * 追加一个元素。若缓冲区已满，则覆盖最旧的元素（自动丢弃超出窗口的过期点）。
   * 相比原 pointsQueue 的「push + trimQueue(slice)」：
   *   - 原方案：每次 O(n) 的 slice 复制
   *   - 环形：O(1) 写入 + O(1) 过期淘汰，且恒定内存占用
   */
  push(item) {
    if (this.size < this.capacity) {
      const tail = (this.head + this.size) % this.capacity
      this.buf[tail] = item
      this.size++
    } else {
      // 已满：覆盖 head 位置，head 前移一位（最旧点被自然淘汰）
      this.buf[this.head] = item
      this.head = (this.head + 1) % this.capacity
    }
  }

  /** 按 compare 升序导出一份数组快照（buildGrid 需要升序输入）。 */
  toSortedArray() {
    const out = new Array(this.size)
    for (let i = 0; i < this.size; i++) {
      out[i] = this.buf[(this.head + i) % this.capacity]
    }
    // 推送理论上有序，但断线重连后乱序到达时仍需排序兜底
    out.sort(this.compare)
    return out
  }

  /** 导出升序快照（别名，与 toSortedArray 行为一致）。 */
  toArray() {
    return this.toSortedArray()
  }

  /** 返回最新元素（tail 前一个）。 */
  last() {
    if (this.size === 0) return undefined
    const tail = (this.head + this.size - 1) % this.capacity
    return this.buf[tail]
  }
}
