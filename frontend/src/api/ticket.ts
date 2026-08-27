export interface Ticket {
  readonly ticket: string
  /** epoch 毫秒 */
  readonly expiresAt: number
}

/**
 * 媒体票据的本地缓存。
 *
 * 三件事：**过期前主动续签**（不等 401）、**并发合流**（一帧里十几张封面
 * 同时要票据，只能签一次）、**失败不缓存**（一次网络抖动不该让播放器
 * 永久拿不到票）。
 */
export class TicketCache {
  private current: Ticket | null = null
  private inflight: Promise<Ticket> | null = null

  constructor(
    private readonly fetcher: () => Promise<Ticket>,
    /** 距过期还剩这么久就续签。默认 30 秒 */
    private readonly marginMs = 30_000,
  ) {}

  async get(now: number = Date.now()): Promise<string> {
    if (this.current && this.current.expiresAt - now > this.marginMs) {
      return this.current.ticket
    }
    if (!this.inflight) {
      // 失败时把 inflight 清掉，否则一次失败会被后续所有调用共享
      this.inflight = this.fetcher().finally(() => {
        this.inflight = null
      })
    }
    const pending = this.inflight
    const issued = await pending
    this.current = issued
    return issued.ticket
  }

  invalidate(): void {
    this.current = null
  }
}
