import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, createEnvironment, type ApiFieldError, type CreateEnvironmentRequest } from '../../api/client'
import { canCreate, getSession, getSessionVersion, isOperator, useSession, useSessionVersion } from '../../api/session'

const STORAGE_KEY = 'dpl.environment-creation.v1'
const NAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9 _-]{0,62}$/
const KEY_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/

interface CreationIntent {
  key: string
  payload: CreateEnvironmentRequest
  username?: string
  conflict?: boolean
}

interface CreationDraft {
  name: string
  ttlHours: string
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
    || (payload.teamId !== undefined && (typeof payload.teamId !== 'string' || !payload.teamId.trim()))
    || (item.username !== undefined && typeof item.username !== 'string')
    || (item.conflict !== undefined && typeof item.conflict !== 'boolean')) return null
  return {
    key: item.key,
    payload: { name: payload.name, templateId: 'sample-service', ttlHours: payload.ttlHours,
      ...(typeof payload.teamId === 'string' ? { teamId: payload.teamId } : {}) },
    ...(typeof item.username === 'string' ? { username: item.username } : {}),
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
    if (intent?.username && intent.username !== getSession()?.identity.username) return null
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
  const session = useSession()
  const version = useSessionVersion()
  const [memoryIntent, setMemoryIntent] = useState<CreationIntent | null>(null)
  const [drafts, setDrafts] = useState<{
    session: typeof session
    anonymous?: CreationDraft
    initial?: CreationDraft
  }>({ session })

  if (drafts.session !== session) {
    setDrafts({
      session,
      anonymous: session ? undefined : drafts.anonymous,
      initial: session && !drafts.session ? drafts.anonymous : undefined,
    })
  }

  return <CreationForm key={version}
    initialIntent={memoryIntent?.username === session?.identity.username ? memoryIntent : null}
    initialDraft={session ? drafts.initial : drafts.anonymous}
    onIntentChange={setMemoryIntent}
    onDraftChange={session ? undefined : (anonymous) => setDrafts((current) => ({ ...current, anonymous }))} />
}

function CreationForm({ initialIntent, initialDraft, onIntentChange, onDraftChange }: {
  initialIntent: CreationIntent | null
  initialDraft?: CreationDraft
  onIntentChange: (intent: CreationIntent | null) => void
  onDraftChange?: (draft: CreationDraft) => void
}) {
  const [restoredIntent] = useState(() => initialIntent ?? readIntent())
  const [intent, setIntent] = useState<CreationIntent | null>(restoredIntent)
  const [name, setName] = useState(restoredIntent?.payload.name ?? initialDraft?.name ?? '')
  const [ttlHours, setTtlHours] = useState(String(restoredIntent?.payload.ttlHours ?? initialDraft?.ttlHours ?? 8))
  const [teamId, setTeamId] = useState(restoredIntent?.payload.teamId ?? '')
  const [submitting, setSubmitting] = useState(false)
  const [uncertain, setUncertain] = useState(restoredIntent !== null)
  const [startingNew, setStartingNew] = useState(false)
  const [priorMayExist, setPriorMayExist] = useState(false)
  const [storageUnavailable, setStorageUnavailable] = useState(false)
  const [message, setMessage] = useState(restoredIntent
    ? 'A previous request may already have been accepted. Retry it with the same request key, or explicitly start a new request.'
    : '')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const submittingRef = useRef(false)
  const mountedRef = useRef(true)
  const session = useSession()
  const authorized = canCreate(session)
  const operator = isOperator(session)
  const teams = session?.identity.teams ?? []

  useEffect(() => {
    mountedRef.current = true
    return () => { mountedRef.current = false }
  }, [])

  const locked = submitting || (!!intent && uncertain)
  const errorsFor = (field: string) => fieldErrors[field]

  function saveIntent(activeIntent: CreationIntent): boolean {
    onIntentChange(activeIntent)
    return persistIntent(activeIntent)
  }

  async function submit(activeIntent: CreationIntent) {
    if (submittingRef.current || !authorized) return
    if (!operator && (!activeIntent.payload.teamId || !teams.includes(activeIntent.payload.teamId))) {
      setMessage('Choose one of your teams. Start a new request if this saved request belongs to another team.')
      return
    }
    const version = getSessionVersion()
    submittingRef.current = true
    setSubmitting(true)
    setMessage('Submitting environment request…')
    setFieldErrors({})
    try {
      const accepted = await createEnvironment(activeIntent.payload, activeIntent.key)
      if (getSessionVersion() !== version) return
      clearIntent(activeIntent.key)
      onIntentChange(null)
      if (mountedRef.current && window.location.hash === '#create') {
        window.location.hash = `#environment/${accepted.environmentId}`
      }
    } catch (caught) {
      if (getSessionVersion() !== version) return
      const error = caught instanceof ApiError
        ? caught
        : new ApiError('The request could not be confirmed. The environment may have been accepted; retry the same request safely.', undefined, undefined, [], true)
      if (error.code === 'UNAUTHENTICATED' || error.code === 'FORBIDDEN') {
        // Rejected before processing: keep the same key so the request can be safely retried after sign-in.
        saveIntent(activeIntent)
        if (mountedRef.current) {
          setIntent(activeIntent)
          setUncertain(true)
          setMessage(`${error.message}${requestIdMessage(error)}`)
        }
      } else if (error.code === 'IDEMPOTENCY_CONFLICT') {
        const conflict = { ...activeIntent, conflict: true }
        saveIntent(conflict)
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
        onIntentChange(null)
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
    if (submittingRef.current || !authorized) return
    if (intent) {
      if (!intent.conflict) void submit(intent)
      return
    }
    const errors = validate(name, ttlHours)
    if (!operator && (!teamId || !teams.includes(teamId))) errors.teamId = 'Choose one of your teams.'
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
      ...(teamId.trim() ? { teamId: teamId.trim() } : {}),
    }
    const nextIntent = { key, payload: request, username: session!.identity.username }
    setIntent(nextIntent)
    setStorageUnavailable(!saveIntent(nextIntent))
    void submit(nextIntent)
  }

  function startNewRequest() {
    if (submitting) return
    if (!startingNew) {
      setStartingNew(true)
      return
    }
    if (intent) clearIntent(intent.key)
    onIntentChange(null)
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

    {!authorized && <div className="notice" role="status">
      {session
        ? `Signed in as ${session.identity.username}, which cannot create environments. A DEVELOPER or OPERATOR account is required.`
        : 'Sign in with a DEVELOPER or OPERATOR account above to create environments.'}
    </div>}
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
      {!intent.conflict && <button type="button" onClick={() => void submit(intent)} disabled={submitting || !authorized}>
        Retry the same request
      </button>}
      <button type="button" onClick={startNewRequest} disabled={submitting}>
        {startingNew ? 'I understand — start a new request' : 'Start a new request instead'}
      </button>
      {startingNew && <p role="alert">The earlier request may already exist. This choice does not cancel it. Confirm below only if you want a new key and request.</p>}
    </div>}

    <form onSubmit={handleSubmit} noValidate aria-busy={submitting}>
      <div className="form-field">
        <label htmlFor="environment-team">Team</label>
        <select id="environment-team" name="teamId" value={intent && uncertain ? intent.payload.teamId ?? '' : teamId}
          disabled={locked} onChange={(event) => {
            setTeamId(event.target.value)
            setFieldErrors((current) => ({ ...current, teamId: '' }))
          }} aria-invalid={!!errorsFor('teamId')} aria-describedby={errorsFor('teamId') ? 'team-error' : 'team-help'}>
          <option value="">{operator ? 'Operator-managed (no team)' : 'Select a team'}</option>
          {Array.from(new Set([...teams, ...(operator && teamId ? [teamId] : [])])).map((team) =>
            <option key={team} value={team}>{team}</option>)}
        </select>
        {operator && <input aria-label="Other team ID" value={intent && uncertain ? intent.payload.teamId ?? '' : teamId}
          disabled={locked} onChange={(event) => setTeamId(event.target.value)} />}
        <small id="team-help">{operator
          ? 'Select a team, enter another team ID, or leave blank for an operator-managed environment.'
          : 'Select one of your memberships. All your teams appear in the inventory.'}</small>
        {errorsFor('teamId') && <small className="field-error" id="team-error">{errorsFor('teamId')}</small>}
      </div>
      <div className="form-field">
        <label htmlFor="environment-name">Environment name</label>
        <input id="environment-name" name="name" type="text" autoComplete="off"
          value={intent && uncertain ? intent.payload.name : name}
          onChange={(event) => {
            setName(event.target.value)
            onDraftChange?.({ name: event.target.value, ttlHours })
            setFieldErrors((current) => ({ ...current, name: '' }))
          }}
          aria-invalid={!!errorsFor('name')} aria-describedby={errorsFor('name') ? 'name-error' : 'name-help'}
          disabled={locked} />
        <small id="name-help">1–63 ASCII letters, digits, spaces, underscores, or hyphens; starts with a letter or digit.</small>
        {errorsFor('name') && <small className="field-error" id="name-error">{errorsFor('name')}</small>}
      </div>
      <div className="form-field">
        <label htmlFor="environment-template">Approved template</label>
        <select id="environment-template" name="templateId" value="sample-service" disabled
          aria-invalid={!!errorsFor('templateId')}
          aria-describedby={errorsFor('templateId') ? 'template-error' : undefined}>
          <option value="sample-service">sample-service</option>
        </select>
        <small>Only the fixed, approved sample is available.</small>
        {errorsFor('templateId') && <small className="field-error" id="template-error">{errorsFor('templateId')}</small>}
      </div>
      <div className="form-field">
        <label htmlFor="environment-ttl">Lifetime (hours)</label>
        <input id="environment-ttl" name="ttlHours" type="number" min="1" max="24" step="1"
          value={intent && uncertain ? String(intent.payload.ttlHours) : ttlHours}
          onChange={(event) => {
            setTtlHours(event.target.value)
            onDraftChange?.({ name, ttlHours: event.target.value })
            setFieldErrors((current) => ({ ...current, ttlHours: '' }))
          }}
          aria-invalid={!!errorsFor('ttlHours')} aria-describedby={errorsFor('ttlHours') ? 'ttl-error' : 'ttl-help'}
          disabled={locked} />
        <small id="ttl-help">Enter a whole number from 1 to 24. This does not schedule cleanup.</small>
        {errorsFor('ttlHours') && <small className="field-error" id="ttl-error">{errorsFor('ttlHours')}</small>}
      </div>
      <button type="submit" disabled={submitting || (!!intent && uncertain) || !authorized}>
        {submitting ? 'Submitting…' : 'Create environment'}
      </button>
    </form>
  </section>
}
