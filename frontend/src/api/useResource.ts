import { useEffect, useState } from 'react'
import { ApiError, read } from './client'

export const REFRESH_INTERVAL = 10_000

interface Resource<T> {
  path: string
  data?: T
  error?: ApiError
  busy: boolean
  checkedAt?: Date
}

export function useResource<T>(path: string, decode: (value: unknown) => T) {
  const [state, setState] = useState<Resource<T>>({ path, busy: true })
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    async function load() {
      setState((previous) => ({ ...(previous.path === path ? previous : { path }), busy: true }))
      try {
        const data = await read(path, decode, controller.signal)
        if (!controller.signal.aborted) {
          setState({ path, data, busy: false, checkedAt: new Date() })
        }
      } catch (error) {
        if (!controller.signal.aborted) {
          setState((previous) => ({ ...previous, busy: false,
            error: error instanceof ApiError ? error : new ApiError('Unable to load status.') }))
        }
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(load, REFRESH_INTERVAL)
      }
    }
    void load()
    return () => {
      controller.abort()
      clearTimeout(timer)
    }
  }, [path, decode, revision])

  const current: Resource<T> = state.path === path ? state : { path, busy: true }
  return { ...current,
    refresh: () => setRevision((value) => value + 1) }
}
