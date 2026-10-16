import { authorizationHeader, clearSession, setSession, type Identity } from './session'

export interface OperationSummary {
  id: string
  type: string
  status: string
  progressStep: string | null
}

export interface Environment {
  id: string
  name: string
  namespace: string
  templateId: string
  status: string
  expiresAt: string
  createdAt: string
  updatedAt: string
  latestOperation: OperationSummary | null
}

export interface OperationEvent {
  type: string
  code: string
  message: string
  correlationId: string | null
  createdAt: string
}

export interface Operation extends OperationSummary {
  environmentId: string
  attemptCount: number
  nextAttemptAt: string | null
  leaseExpiresAt: string | null
  startedAt: string | null
  completedAt: string | null
  createdAt: string
  updatedAt: string
  failureCode: string | null
  failureMessage: string | null
  correlationId: string | null
  events: OperationEvent[]
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface CreateEnvironmentRequest {
  name: string
  templateId: 'sample-service'
  ttlHours: number
}

export interface AcceptedEnvironment {
  environmentId: string
  operationId: string
  environmentStatus: 'REQUESTED'
  operationStatus: 'PENDING'
}

export interface AcceptedDeletion {
  environmentId: string
  operationId: string
  environmentStatus: 'DELETING'
  operationStatus: 'PENDING' | 'RUNNING'
}

export interface ApiFieldError {
  field: string
  message: string
}

export class ApiError extends Error {
  readonly requestId?: string
  readonly code?: string
  readonly fieldErrors: ApiFieldError[]
  readonly outcomeUncertain: boolean

  constructor(message: string, requestId?: string, code?: string,
    fieldErrors: ApiFieldError[] = [], outcomeUncertain = false) {
    super(message)
    this.name = 'ApiError'
    this.requestId = requestId
    this.code = code
    this.fieldErrors = fieldErrors
    this.outcomeUncertain = outcomeUncertain
  }
}

type Decoder<T> = (value: unknown) => T
type JsonObject = Record<string, unknown>

function object(value: unknown): JsonObject {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new ApiError('The API returned an unexpected response.')
  }
  return value as JsonObject
}

function text(value: unknown): string {
  if (typeof value !== 'string') throw new ApiError('The API returned an unexpected response.')
  return value
}

function nullableText(value: unknown): string | null {
  return value === null ? null : text(value)
}

function integer(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw new ApiError('The API returned an unexpected response.')
  }
  return value
}

function array<T>(value: unknown, decode: Decoder<T>): T[] {
  if (!Array.isArray(value)) throw new ApiError('The API returned an unexpected response.')
  return value.map(decode)
}

function summary(value: unknown): OperationSummary {
  const item = object(value)
  return { id: text(item.id), type: text(item.type), status: text(item.status),
    progressStep: nullableText(item.progressStep) }
}

export function environment(value: unknown): Environment {
  const item = object(value)
  return {
    id: text(item.id), name: text(item.name), namespace: text(item.namespace),
    templateId: text(item.templateId), status: text(item.status),
    expiresAt: text(item.expiresAt), createdAt: text(item.createdAt), updatedAt: text(item.updatedAt),
    latestOperation: item.latestOperation === null ? null : summary(item.latestOperation),
  }
}

function operation(value: unknown): Operation {
  const item = object(value)
  return {
    ...summary(item), environmentId: text(item.environmentId), attemptCount: integer(item.attemptCount),
    nextAttemptAt: nullableText(item.nextAttemptAt), leaseExpiresAt: nullableText(item.leaseExpiresAt),
    startedAt: nullableText(item.startedAt), completedAt: nullableText(item.completedAt),
    createdAt: text(item.createdAt), updatedAt: text(item.updatedAt),
    failureCode: nullableText(item.failureCode), failureMessage: nullableText(item.failureMessage),
    correlationId: nullableText(item.correlationId),
    events: array(item.events, (value) => {
      const event = object(value)
      return { type: text(event.type), code: text(event.code), message: text(event.message),
        correlationId: nullableText(event.correlationId), createdAt: text(event.createdAt) }
    }),
  }
}

function page<T>(value: unknown, decode: Decoder<T>): Page<T> {
  const item = object(value)
  return { items: array(item.items, decode), page: integer(item.page), size: integer(item.size),
    totalElements: integer(item.totalElements), totalPages: integer(item.totalPages) }
}

export const environmentPage = (value: unknown) => page(value, environment)
export const operationPage = (value: unknown) => page(value, operation)

export async function read<T>(path: string, decode: Decoder<T>, signal?: AbortSignal): Promise<T> {
  let response: Response
  try {
    response = await fetch(`/api/v1${path}`, { signal, headers: { Accept: 'application/json' } })
  } catch (error) {
    if (signal?.aborted) throw error
    throw new ApiError('Cannot reach the API. Check that the backend is running and try again.')
  }
  if (!response.ok) {
    let message = `The API request failed (HTTP ${response.status}).`
    let requestId = response.headers.get('X-Request-Id') ?? undefined
    try {
      const body = object(await response.json())
      if (typeof body.message === 'string') message = body.message
      if (typeof body.requestId === 'string') requestId = body.requestId
    } catch {
      // Proxy errors may not have the backend's JSON error shape.
    }
    throw new ApiError(message, requestId)
  }
  let body: unknown
  try {
    body = await response.json()
  } catch {
    throw new ApiError('The API returned an unexpected response.')
  }
  return decode(body)
}

const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i
const ALLOWED_FIELDS = new Set(['name', 'templateId', 'ttlHours'])
const CREATION_TIMEOUT_MS = 15_000

function fieldErrors(value: unknown): ApiFieldError[] {
  if (!Array.isArray(value)) return []
  return value.flatMap((item) => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return []
    const field = (item as JsonObject).field
    const message = (item as JsonObject).message
    if (typeof field !== 'string' || !ALLOWED_FIELDS.has(field) || typeof message !== 'string') return []
    return [{ field, message: message.slice(0, 250) }]
  })
}

function requestIdentifier(value: unknown): string | undefined {
  return typeof value === 'string' && /^[A-Za-z0-9._:-]{1,128}$/.test(value) ? value : undefined
}

function acceptedEnvironment(value: unknown): AcceptedEnvironment {
  const item = object(value)
  if (typeof item.environmentId !== 'string' || !UUID.test(item.environmentId)
    || typeof item.operationId !== 'string' || !UUID.test(item.operationId)
    || item.environmentStatus !== 'REQUESTED' || item.operationStatus !== 'PENDING') {
    throw new ApiError('The API returned an unexpected creation response.')
  }
  return {
    environmentId: item.environmentId,
    operationId: item.operationId,
    environmentStatus: 'REQUESTED',
    operationStatus: 'PENDING',
  }
}

function authorizationError(status: number, requestId: string | undefined, action: string): ApiError | undefined {
  if (status === 401) {
    clearSession()
    return new ApiError(`Sign in again: the API did not accept your credentials, so the ${action} was not accepted.`,
      requestId, 'UNAUTHENTICATED')
  }
  if (status === 403) {
    return new ApiError(`Your account is not permitted to ${action === 'deletion' ? 'delete' : 'create'} environments. An OPERATOR account is required.`,
      requestId, 'FORBIDDEN')
  }
  return undefined
}

function basicCredential(username: string, password: string): string {
  const bytes = new TextEncoder().encode(`${username}:${password}`)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return `Basic ${btoa(binary)}`
}

export async function signIn(username: string, password: string): Promise<Identity> {
  if (!/^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$/.test(username) || !password) {
    throw new ApiError('Enter a valid username and password.')
  }
  const authorization = basicCredential(username, password)
  let response: Response
  try {
    response = await fetch('/api/v1/me', { headers: { Accept: 'application/json', Authorization: authorization } })
  } catch {
    throw new ApiError('Cannot reach the API. Check that the backend is running and try again.')
  }
  const requestId = requestIdentifier(response.headers.get('X-Request-Id'))
  if (response.status === 401) throw new ApiError('Sign-in failed. Check the username and password.', requestId, 'UNAUTHENTICATED')
  if (!response.ok) throw new ApiError(`Sign-in could not be completed (HTTP ${response.status}).`, requestId)
  let identity: Identity
  try {
    const body = object(await response.json())
    const roles = array(body.roles, text)
    const confirmed = text(body.username)
    // Account lookup ignores case; keep the configured spelling the audit log records.
    if (confirmed.toLowerCase() !== username.toLowerCase()) throw new Error('Unexpected identity')
    identity = { username: confirmed, roles }
  } catch {
    throw new ApiError('The API returned an unexpected sign-in response.', requestId)
  }
  setSession({ identity, authorization })
  return identity
}

export async function deleteEnvironment(id: string, signal?: AbortSignal): Promise<AcceptedDeletion> {
  const controller = new AbortController()
  const abort = () => controller.abort()
  signal?.addEventListener('abort', abort, { once: true })
  if (signal?.aborted) controller.abort()
  const timeout = setTimeout(abort, CREATION_TIMEOUT_MS)
  let requestId: string | undefined
  try {
    let response: Response
    try {
      response = await fetch(`/api/v1/environments/${encodeURIComponent(id)}`, {
        method: 'DELETE', signal: controller.signal, headers: { Accept: 'application/json', ...authorizationHeader() },
      })
    } catch (error) {
      if (signal?.aborted) throw error
      throw new ApiError('The connection was interrupted or timed out. Deletion may have been accepted. Check the status or retry deletion for this same environment safely.',
        undefined, undefined, [], true)
    }
    requestId = requestIdentifier(response.headers.get('X-Request-Id'))
    if (!response.ok) {
      let body: JsonObject = {}
      try {
        body = object(await response.json())
      } catch {
        // Never expose server or proxy diagnostic bodies.
      }
      requestId = requestIdentifier(body.requestId) ?? requestId
      const code = typeof body.code === 'string' && /^[A-Z0-9_]{1,80}$/.test(body.code) ? body.code : undefined
      const denied = authorizationError(response.status, requestId, 'deletion')
      if (denied) throw denied
      throw new ApiError(response.status >= 500
        ? 'The server could not confirm deletion. It may have been accepted. Check the status or retry deletion for this same environment safely.'
        : `Deletion was rejected (HTTP ${response.status}). Refresh status before trying again.`,
      requestId, code, [], response.status >= 500)
    }
    try {
      const item = object(await response.json())
      if (response.status !== 202 || item.environmentId !== id
        || typeof item.operationId !== 'string' || !UUID.test(item.operationId)
        || item.environmentStatus !== 'DELETING'
        || (item.operationStatus !== 'PENDING' && item.operationStatus !== 'RUNNING')) {
        throw new Error('Invalid deletion acceptance')
      }
      return { environmentId: id, operationId: item.operationId,
        environmentStatus: 'DELETING', operationStatus: item.operationStatus }
    } catch {
      throw new ApiError('The API returned an unexpected deletion response. Deletion may have been accepted. Check the status or retry deletion for this same environment safely.',
        requestId, undefined, [], true)
    }
  } finally {
    clearTimeout(timeout)
    signal?.removeEventListener('abort', abort)
  }
}

export async function createEnvironment(
  request: CreateEnvironmentRequest,
  idempotencyKey: string,
): Promise<AcceptedEnvironment> {
  const controller = new AbortController()
  let timedOut = false
  const timeout = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, CREATION_TIMEOUT_MS)

  try {
    let response: Response
    try {
      response = await fetch('/api/v1/environments', {
        method: 'POST',
        signal: controller.signal,
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'Idempotency-Key': idempotencyKey,
          ...authorizationHeader(),
        },
        body: JSON.stringify(request),
      })
    } catch {
      throw new ApiError(timedOut
        ? 'The request timed out. The environment may have been accepted; retry the same request safely.'
        : 'The connection was interrupted. The environment may have been accepted; retry the same request safely.',
      undefined, undefined, [], true)
    }

    const headerRequestId = requestIdentifier(response.headers.get('X-Request-Id'))
    if (!response.ok) {
      let body: JsonObject = {}
      try {
        body = object(await response.json())
      } catch {
        // Do not expose proxy or server diagnostic bodies.
      }
      const code = typeof body.code === 'string' ? body.code : undefined
      const requestId = requestIdentifier(body.requestId) ?? headerRequestId
      const errors = fieldErrors(body.fieldErrors)
      const denied = authorizationError(response.status, requestId, 'request')
      if (denied) throw denied
      if (response.status === 400 && code === 'VALIDATION_ERROR') {
        throw new ApiError('Some fields need attention.', requestId, code, errors)
      }
      if (response.status === 409 && code === 'IDEMPOTENCY_CONFLICT') {
        throw new ApiError('This request key belongs to a different request. The earlier request may already exist.',
          requestId, code)
      }
      throw new ApiError(response.status >= 500
        ? 'The server could not confirm the result. The environment may have been accepted; retry the same request safely.'
        : `The request was rejected (HTTP ${response.status}).`,
      requestId, code, [], response.status >= 500)
    }

    if (response.status !== 202) {
      throw new ApiError('The API returned an unexpected creation response.',
        headerRequestId, undefined, [], true)
    }
    let body: unknown
    try {
      body = await response.json()
    } catch {
      throw new ApiError('The API returned an unexpected creation response.',
        headerRequestId, undefined, [], true)
    }
    try {
      return acceptedEnvironment(body)
    } catch {
      throw new ApiError('The API returned an unexpected creation response.',
        headerRequestId, undefined, [], true)
    }
  } finally {
    clearTimeout(timeout)
  }
}
