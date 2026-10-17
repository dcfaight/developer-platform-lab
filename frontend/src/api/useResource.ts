import { useEffect, useState } from 'react'
import { ApiError, read } from './client'
import { getSessionVersion, useSession, useSessionVersion } from './session'

export const REFRESH_INTERVAL = 10_000

interface Resource<T> {
  path: string
  version: number
  data?: T
  error?: ApiError
  busy: boolean
  checkedAt?: Date
}

export function useResource<T>(path: string, decode: (value: unknown) => T) {
  const session = useSession()
  const version = useSessionVersion()
  const [state, setState] = useState<Resource<T>>({ path, version, busy: !!session })
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    if (!session) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    async function load() {
      setState((previous) => ({ ...(previous.path === path && previous.version === version ? previous : { path, version }), busy: true }))
      try {
        const data = await read(path, decode, controller.signal)
        if (!controller.signal.aborted && getSessionVersion() === version) {
          setState({ path, version, data, busy: false, checkedAt: new Date() })
        }
      } catch (error) {
        if (!controller.signal.aborted && getSessionVersion() === version) {
          setState((previous) => ({ ...previous, busy: false,
            error: error instanceof ApiError ? error : new ApiError('Unable to load status.') }))
        }
      } finally {
        if (!controller.signal.aborted && getSessionVersion() === version) timer = setTimeout(load, REFRESH_INTERVAL)
      }
    }
    void load()
    return () => {
      controller.abort()
      clearTimeout(timer)
    }
  }, [path, decode, revision, session, version])

  const current: Resource<T> = session && state.path === path && state.version === version
    ? state : { path, version, busy: !!session }
  return { ...current,
    refresh: () => setRevision((value) => value + 1) }
}
