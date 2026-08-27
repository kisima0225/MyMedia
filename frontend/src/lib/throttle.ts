export interface Throttle<A extends unknown[]> {
  call(...args: A): void
  /** 立刻补发挂起的调用。页面隐藏或组件卸载时必须调它。 */
  flush(): void
  cancel(): void
}

/**
 * 前沿触发 + 尾部补发的节流。
 *
 * `<video>` 的 `timeupdate` 每秒烧 4 次；不压的话一部两小时的片子会往
 * 服务器打近三万次进度写入。压到 5 秒一次，最多丢 5 秒进度——
 * 一个没人会察觉的误差，换掉 99.98% 的请求。
 */
export function createThrottle<A extends unknown[]>(
  fn: (...args: A) => void,
  intervalMs: number,
): Throttle<A> {
  let last = 0
  let pending: A | null = null
  let timer: ReturnType<typeof setTimeout> | null = null

  const run = (args: A) => {
    last = Date.now()
    pending = null
    fn(...args)
  }

  return {
    call(...args: A) {
      const now = Date.now()
      if (now - last >= intervalMs) {
        if (timer) { clearTimeout(timer); timer = null }
        run(args)
        return
      }
      pending = args
      timer ??= setTimeout(() => {
        timer = null
        if (pending) run(pending)
      }, intervalMs - (now - last))
    },
    flush() {
      if (timer) { clearTimeout(timer); timer = null }
      if (pending) run(pending)
    },
    cancel() {
      if (timer) { clearTimeout(timer); timer = null }
      pending = null
    },
  }
}
