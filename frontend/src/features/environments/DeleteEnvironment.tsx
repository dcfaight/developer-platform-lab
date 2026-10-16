import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, deleteEnvironment } from '../../api/client'
import type { AcceptedDeletion, Environment } from '../../api/client'

export function DeleteEnvironment({ item, unavailable, onRefresh }: {
  item: Environment
  unavailable: boolean
  onRefresh: () => void
}) {
  const [open, setOpen] = useState(false)
  const [confirmation, setConfirmation] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError>()
  const [accepted, setAccepted] = useState<AcceptedDeletion>()
  const controller = useRef<AbortController | null>(null)
  const dialog = useRef<HTMLDialogElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const identity = `${item.namespace}/${item.name}`
  const eligible = ['READY', 'FAILED', 'DELETE_FAILED'].includes(item.status)
    && !['PENDING', 'RUNNING'].includes(item.latestOperation?.status ?? '')
  const retryFailedDeletion = item.status === 'DELETE_FAILED'
    && item.latestOperation?.id === accepted?.operationId && item.latestOperation?.status === 'FAILED'
  const disabled = unavailable || !eligible || busy || (!!accepted && !retryFailedDeletion)

  useEffect(() => () => controller.current?.abort(), [])
  useEffect(() => {
    if (open) dialog.current?.showModal()
  }, [open])

  function close() {
    dialog.current?.close()
    setOpen(false)
    trigger.current?.focus()
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (controller.current || disabled || confirmation !== identity) return
    const current = new AbortController()
    controller.current = current
    setBusy(true)
    setError(undefined)
    try {
      const result = await deleteEnvironment(item.id, current.signal)
      if (current.signal.aborted) return
      setAccepted(result)
      close()
      onRefresh()
    } catch (failure) {
      if (current.signal.aborted) return
      setError(failure instanceof ApiError ? failure : new ApiError('Unable to confirm deletion. Refresh status before retrying.'))
      onRefresh()
    } finally {
      if (!current.signal.aborted) {
        controller.current = null
        setBusy(false)
      }
    }
  }

  return <div className="delete-environment">
    <button ref={trigger} type="button" disabled={disabled} aria-describedby="delete-guidance"
      onClick={() => {
        setConfirmation('')
        setError(undefined)
        setAccepted(undefined)
        setOpen(true)
      }}>Delete environment</button>
    <p id="delete-guidance" className="muted">
      Deletion removes this environment's resources and cannot be undone.
      {' '}Available only for READY, FAILED, or DELETE_FAILED environments with no pending or running operation.
    </p>
    {accepted && <p role="status" className="submission-status">
      Deletion accepted ({accepted.operationStatus}). This does not mean cleanup is complete.
      {' '}Operation: <code>{accepted.operationId}</code>. Follow operation history below.
    </p>}
    {open && <dialog ref={dialog} className="delete-dialog" aria-labelledby="delete-heading"
      aria-describedby="delete-warning" onCancel={(event) => {
        event.preventDefault()
        if (!busy) close()
      }}>
      <h2 id="delete-heading">Confirm environment deletion</h2>
      <p id="delete-warning">Permanently delete <strong>{item.name}</strong> in namespace <code>{item.namespace}</code>?
        {' '}This cannot be undone. Acceptance starts asynchronous cleanup; it does not confirm completion.</p>
      <form aria-busy={busy} onSubmit={(event) => { void submit(event) }}>
        <div className="form-field">
          <label htmlFor="delete-confirmation">Type namespace/name to confirm</label>
          <p id="delete-identity"><code>{identity}</code></p>
          <input id="delete-confirmation" aria-describedby="delete-identity" autoFocus
            autoComplete="off" value={confirmation} disabled={busy}
            onChange={(event) => setConfirmation(event.target.value)} />
        </div>
        {error && <div role="alert" className="notice">
          <p>{error.message}</p>
          {error.code && <p>Error code: <code>{error.code}</code></p>}
          {error.requestId && <p>Request ID: <code>{error.requestId}</code></p>}
          {error.outcomeUncertain && <p>Retries target only this environment; the server serializes deletion by environment ID.
            If status is already DELETING, cancel this dialog and follow operation history.</p>}
        </div>}
        {!eligible && <p role="status">Deletion is unavailable for the current status. Cancel and follow operation history.</p>}
        <div className="delete-actions">
          <button type="button" disabled={busy} onClick={close}>Cancel</button>
          <button type="submit" disabled={disabled || confirmation !== identity}>
            {busy ? 'Submitting deletion…' : error?.outcomeUncertain ? 'Retry deletion' : 'Confirm deletion'}
          </button>
        </div>
      </form>
    </dialog>}
  </div>
}
