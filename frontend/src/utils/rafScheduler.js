/**
 * 基于 requestAnimationFrame 的「同一帧合并」调度器。
 *
 * 原理：
 *  - 每次 schedule() 只是把「脏标记」置位，并在首帧注册一个 rAF 回调。
 *  - 同一帧内的后续 schedule() 复用同一个 rAF，不会叠加计算。
 *  - 浏览器在下次重绘前触发回调，此时一次性执行 run()，把 N 次 append 合并为 1 次 rebuildGrid。
 *  - 相比「watchEffect + 微任务(await Promise.then)」方案：
 *      microtask 会在当前宏任务结束后立即执行，若 WS 在同一 tick 内同步推入 100 条，
 *      微任务仍只合并到「当前 tick 末」，但 rAF 能进一步合并到「下一渲染帧」，
 *      与浏览器的帧节奏对齐，最大程度避免掉帧。
 */
export function createRafScheduler(run) {
  let rafId = 0
  let timerId = 0

  function flush() {
    rafId = 0
    timerId = 0
    run()
  }

  return {
    schedule() {
      if (rafId || timerId) return
      // 若浏览器可见，使用 rAF 与帧节奏对齐；后台标签页 rAF 不触发，降级到 setTimeout
      if (typeof requestAnimationFrame === 'function' && document.visibilityState === 'visible') {
        rafId = requestAnimationFrame(flush)
      } else {
        timerId = setTimeout(flush, 16)
      }
    },
    cancel() {
      if (rafId && typeof cancelAnimationFrame === 'function') {
        cancelAnimationFrame(rafId)
      }
      if (timerId) {
        clearTimeout(timerId)
      }
      rafId = 0
      timerId = 0
    },
  }
}
