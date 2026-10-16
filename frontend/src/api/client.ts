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

export class ApiError extends Error {
  readonly requestId?: string

  constructor(message: string, requestId?: string) {
    super(message)
    this.name = 'ApiError'
    this.requestId = requestId
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
