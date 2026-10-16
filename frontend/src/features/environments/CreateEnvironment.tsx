import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, createEnvironment, type ApiFieldError, type CreateEnvironmentRequest } from '../../api/client'

const STORAGE_KEY = 'dpl.environment-creation.v1'
const NAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9 _-]{0,62}$/
const KEY_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/

interface CreationIntent {
  key: string
  payload: CreateEnvironmentRequest
  conflict?: boolean
}

function validIntent(value: unknown): CreationIntent | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const item = value as Record<string, unknown>
  if (item.version !== 1 || typeof item.key !== 'string' || !KEY_PATTERN.test(item.key)
    || !item.payload || typeof item.payload !== 'object' || Array.isArray(item.payload)) return null
  const payload = item.payload as Record<string, unknown>
  if (typeof payload.name !== 'string' || !NAME_PATTERN.test(payload.name)
    || payload.templateId !== 'sample-service' || typeof payload.ttlHours !== 'number'
    || !Number.isInteger(payload.ttlHours) || payload.ttlHours < 1 || payload.ttlHours > 24
    || (item.conflict !== undefined && typeof item.conflict !== 'boolean')) return null
  return {
    key: item.key,
    payload: { name: payload.name, templateId: 'sample-service', ttlHours: payload.ttlHours },
    ...(item.conflict ? { conflict: true } : {}),
  }
}

function readIntent(): CreationIntent | null {
  try {
    const stored = window.sessionStorage.getItem(STORAGE_KEY)
    if (!stored) return null
    const parsed = JSON.parse(stored) as unknown
    const intent = validIntent(parsed)
    if (!intent) window.sessionStorage.removeItem(STORAGE_KEY)
    return intent
  } catch {
    return null
  }
}

function persistIntent(intent: CreationIntent): boolean {
  try {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ version: 1, ...intent }))
    return true
  } catch {
    return false
  }
}

function clearIntent(key: string): void {
  try {
    const current = readIntent()
    if (current?.key === key) window.sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // The in-memory intent remains available for this page.
  }
}

function validate(name: string, ttlHours: string): Record<string, string> {
  const errors: Record<string, string> = {}
  const normalizedName = name.trim()
  if (!normalizedName) errors.name = 'Enter an environment name.'
  else if (!NAME_PATTERN.test(normalizedName)) {
    errors.name = 'Use 1–63 ASCII letters, digits, spaces, underscores, or hyphens; start with a letter or digit.'
  }
  if (!/^\d+$/.test(ttlHours) || Number(ttlHours) < 1 || Number(ttlHours) > 24) {
    errors.ttlHours = 'Enter a whole number from 1 to 24.'
  }
  return errors
}

function newIdempotencyKey(): string {
  return window.crypto.randomUUID()
}

function requestIdMessage(error: ApiError): string {
  return error.requestId ? ` Request ID: ${error.requestId}` : ''
}

export function CreateEnvironment() {
  const [intent, setIntent] = useState<CreationIntent | null>(readIntent)
  const [name, setName] = useState(() => readIntent()?.payload.name ?? '')
  const [ttlHours, setTtlHours] = useState(() => String(readIntent()?.payload.ttlHours ?? 8))
  const [submitting, setSubmitting] = useState(false)
  const [uncertain, setUncertain] = useState(() => readIntent() !== null)
  const [startingNew, setStartingNew] = useState(false)
  const [priorMayExist, setPriorMayExist] = useState(false)
  const [storageUnavailable, setStorageUnavailable] = useState(false)
  const [message, setMessage] = useState(() => readIntent()
    ? 'A previous request may already have been accepted. Retry it with the same request key, or explicitly start a new request.'
    : '')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const submittingRef = useRef(false)
  const mountedRef = useRef(true)

  useEffect(() => () => { mountedRef.current = false }, [])

  const locked = submitting || (!!intent && uncertain)
  const errorsFor = (field: string) => fieldErrors[field]

  async function submit(activeIntent: CreationIntent) {
    if (submittingRef.current) return
    submittingRef.current = true
    setSubmitting(true)
    setMessage('Submitting environment request…')
    setFieldErrors({})
    try {
      const accepted = await createEnvironment(activeIntent.payload, activeIntent.key)
      clearIntent(activeIntent.key)
      if (mountedRef.current && window.location.hash === '#create') {
        window.location.hash = `#environment/${accepted.environmentId}`
      }
    } catch (caught) {
      const error = caught instanceof ApiError
        ? caught
        : new ApiError('The request could not be confirmed. The environment may have been accepted; retry the same request safely.', undefined, undefined, [], true)
      if (error.code === 'IDEMPOTENCY_CONFLICT') {
        const conflict = { ...activeIntent, conflict: true }
        persistIntent(conflict)
        if (mountedRef.current) {
          setIntent(conflict)
          setUncertain(true)
          setMessage(`${error.message}${requestIdMessage(error)}`)
        }
      } else if (error.outcomeUncertain) {
        if (mountedRef.current) {
          setIntent(activeIntent)
          setUncertain(true)
          setMessage(`${error.message}${requestIdMessage(error)}`)
        }
      } else {
        clearIntent(activeIntent.key)
        if (mountedRef.current) {
          setIntent(null)
          setUncertain(false)
          setMessage(`${error.message}${requestIdMessage(error)}`)
          setFieldErrors(Object.fromEntries(error.fieldErrors.map(({ field, message: detail }: ApiFieldError) =>
            [field, detail])))
        }
      }
    } finally {
      submittingRef.current = false
      if (mountedRef.current) setSubmitting(false)
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submittingRef.current) return
    if (intent) {
      if (!intent.conflict) void submit(intent)
      return
    }
    const errors = validate(name, ttlHours)
    setFieldErrors(errors)
    if (Object.keys(errors).length) {
      setMessage('Check the highlighted fields.')
      return
    }

    let key: string
    try {
      key = newIdempotencyKey()
    } catch {
      setMessage('A secure request key is unavailable in this browser. Use a current browser on localhost or HTTPS.')
      return
    }
    const request: CreateEnvironmentRequest = {
      name: name.trim(),
      templateId: 'sample-service',
      ttlHours: Number(ttlHours),
    }
    const nextIntent = { key, payload: request }
    setIntent(nextIntent)
    setStorageUnavailable(!persistIntent(nextIntent))
    void submit(nextIntent)
  }

  function startNewRequest() {
    if (submitting) return
    if (!startingNew) {
      setStartingNew(true)
      return
    }
    if (intent) clearIntent(intent.key)
    setIntent(null)
    setUncertain(false)
    setStartingNew(false)
    setPriorMayExist(true)
    setMessage('')
    setFieldErrors({})
  }

  return <section className="panel create-panel" aria-labelledby="create-heading">
    <a className="back-link" href="#environments">← All environments</a>
    <div className="section-heading"><div><p className="eyebrow">Self-service request</p>
      <h2 id="create-heading">Create environment</h2></div></div>
    <p>Request a local environment using the approved sample service. Acceptance records a request; it does not mean the environment is ready.</p>
    <p className="muted">Lifetime is metadata only. Expired environments are not automatically deleted.</p>

    {priorMayExist && <div className="notice" role="status">
      The earlier request was not cancelled and may already have created an environment. Starting a new request does not guarantee the earlier environment is absent.
    </div>}
    {storageUnavailable && <p className="storage-warning" role="status">
      This browser could not save the retry intent. Keep this page open to retry; refreshing may lose the key needed for a safe replay.
    </p>}
    {message && <p className={submitting ? 'submission-status' : 'submission-message'}
      role={submitting ? 'status' : (intent ? 'alert' : 'status')} aria-live="polite">{message}</p>}

    {intent && uncertain && <div className="retry-intent">
      <p>{intent.conflict
        ? 'This key conflicts with a different request. Retrying it cannot resolve the conflict.'
        : `Recoverable request: ${intent.payload.name}, ${intent.payload.ttlHours} hour${intent.payload.ttlHours === 1 ? '' : 's'}.`}
      </p>
      {!intent.conflict && <button type="button" onClick={() => void submit(intent)} disabled={submitting}>
        Retry the same request
      </button>}
      <button type="button" onClick={startNewRequest} disabled={submitting}>
        {startingNew ? 'I understand — start a new request' : 'Start a new request instead'}
      </button>
      {startingNew && <p role="alert">The earlier request may already exist. This choice does not cancel it. Confirm below only if you want a new key and request.</p>}
    </div>}

    <form onSubmit={handleSubmit} noValidate aria-busy={submitting}>
      <div className="form-field">
        <label htmlFor="environment-name">Environment name</label>
        <input id="environment-name" name="name" type="text" autoComplete="off"
          value={intent && uncertain ? intent.payload.name : name}
          onChange={(event) => { setName(event.target.value); setFieldErrors((current) => ({ ...current, name: '' })) }}
          aria-invalid={!!errorsFor('name')} aria-describedby={errorsFor('name') ? 'name-error' : 'name-help'}
          disabled={locked} />
        <small id="name-help">1–63 ASCII letters, digits, spaces, underscores, or hyphens; starts with a letter or digit.</small>
        {errorsFor('name') && <small className="field-error" id="name-error">{errorsFor('name')}</small>}
      </div>
      <div className="form-field">
        <label htmlFor="environment-template">Approved template</label>
        <select id="environment-template" name="templateId" value="sample-service" disabled>
          <option value="sample-service">sample-service</option>
        </select>
        <small>Only the fixed, approved sample is available.</small>
      </div>
      <div className="form-field">
        <label htmlFor="environment-ttl">Lifetime (hours)</label>
        <input id="environment-ttl" name="ttlHours" type="number" min="1" max="24" step="1"
          value={intent && uncertain ? String(intent.payload.ttlHours) : ttlHours}
          onChange={(event) => { setTtlHours(event.target.value); setFieldErrors((current) => ({ ...current, ttlHours: '' })) }}
          aria-invalid={!!errorsFor('ttlHours')} aria-describedby={errorsFor('ttlHours') ? 'ttl-error' : 'ttl-help'}
          disabled={locked} />
        <small id="ttl-help">Enter a whole number from 1 to 24. This does not schedule cleanup.</small>
        {errorsFor('ttlHours') && <small className="field-error" id="ttl-error">{errorsFor('ttlHours')}</small>}
      </div>
      <button type="submit" disabled={submitting || (!!intent && uncertain)}>
        {submitting ? 'Submitting…' : 'Create environment'}
      </button>
    </form>
  </section>
}
