import type { ApiError } from '../api/client'

export function ResourceState({ busy, error, hasData, checkedAt, refresh }: {
  busy: boolean
  error?: ApiError
  hasData: boolean
  checkedAt?: Date
  refresh: () => void
}) {
  return <>
    <div className="refresh-bar">
      <span role="status">{busy ? (hasData ? 'Refreshing…' : 'Loading…')
        : checkedAt ? `Last refreshed ${checkedAt.toLocaleTimeString()}` : 'No status loaded'}</span>
      <button onClick={refresh} disabled={busy}>{error ? 'Try again' : 'Refresh'}</button>
    </div>
    {error && <div className="notice error" role="alert">
      <strong>{hasData ? 'Refresh failed — showing last loaded data. ' : 'Unable to load status. '}</strong>
      {error.message}
      {error.requestId && <small>Request ID: {error.requestId}</small>}
    </div>}
  </>
}
