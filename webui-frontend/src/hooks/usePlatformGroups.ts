import { useEffect, useState } from 'react'
import { listPlatformGroups, type PlatformGroupDirectory } from '../api/platformGroups'

/** 弹窗打开期间串行轮询目录；关闭后忽略迟到响应，选择值始终绑定连接代际。 */
export function usePlatformGroups(open: boolean) {
  const [data, setData] = useState<PlatformGroupDirectory | null>(null)
  const [error, setError] = useState('')
  const [selection, setSelection] = useState<{generation: string, values: string[]}>({generation: '', values: []})
  useEffect(() => {
    if (!open) return
    let active = true
    let timer: ReturnType<typeof setTimeout>
    /** 每个请求完成后再排下一次，避免慢响应乱序覆盖账号或群状态。 */
    const poll = async () => {
      try {
        const next = await listPlatformGroups()
        if (active) {
          setData(next); setError('')
          // 一旦观察到退群便移除选择，后来重新入群也必须重新勾选。
          setSelection((current) => ({generation: next.generation, values: current.generation === next.generation
            ? current.values.filter((subject) => next.items.some((item) => item.subject === subject && item.joined)) : []}))
        }
      } catch {
        if (active) setError('群列表加载失败，请稍后重试')
      } finally {
        if (active) timer = setTimeout(() => void poll(), 3000)
      }
    }
    // 每次重新打开先重新确认平台，避免沿用关闭期间发生变化的账号。
    timer = setTimeout(() => { setData(null); setSelection({generation: '', values: []}); void poll() }, 0)
    return () => { active = false; clearTimeout(timer) }
  }, [open])
  const selected = data?.generation === selection.generation
    ? selection.values.filter((subject) => data.items.some((item) => item.subject === subject && item.joined)) : []
  /** 勾选只接受当前目录中的群，退群和账号切换自动使旧选择失效。 */
  const select = (values: string[]) => setSelection({generation: data?.generation || '', values: values.slice(0, 100)})
  return {data, error, selected, select, ready: !!data && !error && (!data.enabled || !!data.generation)}
}

export type PlatformGroupsState = ReturnType<typeof usePlatformGroups>
