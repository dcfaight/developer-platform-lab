import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, createEnvironment, environment, environmentPage, operationPage, read } from './client'
import { demoEnvironment, demoOperation, json, paged } from '../test/fixtures'

afterEach(() => vi.unstubAllGlobals())

describe('read-only API boundary', () => {
  it('uses same-origin GET reads with an abort signal and decodes pagination', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(paged([demoEnvironment])))
    vi.stubGlobal('fetch', fetchMock)
    const signal = new AbortController().signal
    expect(await read('/environments?page=0&size=10', environmentPage, signal))
      .toEqual(paged([demoEnvironment]))
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/environments?page=0&size=10', {
      signal, headers: { Accept: 'application/json' },
    })
    expect(operationPage(paged([demoOperation])).items[0]).toEqual(demoOperation)
  })

  it('handles absent operation summaries and nullable lifecycle fields', () => {
    expect(environment({ ...demoEnvironment, latestOperation: null }).latestOperation).toBeNull()
    expect(operationPage(paged([{ ...demoOperation, startedAt: null, progressStep: null, events: [] }]))
      .items[0].startedAt).toBeNull()
  })

  it('preserves structured backend errors and request identifiers', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      code: 'NOT_FOUND', message: 'Environment not found.', requestId: 'request-123',
    }, 404)))
    await expect(read('/environments/missing', environment)).rejects.toMatchObject({
      message: 'Environment not found.', requestId: 'request-123', name: 'ApiError',
    })
  })

  it('does not display raw HTML from a failed proxy', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>proxy diagnostics</html>', {
      status: 502, headers: { 'X-Request-Id': 'proxy-request' },
    })))
    await expect(read('/environments', environmentPage)).rejects.toMatchObject({
      message: 'The API request failed (HTTP 502).', requestId: 'proxy-request',
    })
  })

  it('reports network failure and preserves cancellation', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('offline'))
    vi.stubGlobal('fetch', fetchMock)
    await expect(read('/environments', environmentPage)).rejects.toThrow('Cannot reach the API')
    const controller = new AbortController()
    controller.abort()
    const error = new DOMException('Aborted', 'AbortError')
    fetchMock.mockRejectedValue(error)
    await expect(read('/environments', environmentPage, controller.signal)).rejects.toBe(error)
  })

  it.each([null, {}, { ...paged([]), items: [null] }, { ...paged([]), totalPages: -1 }])(
    'rejects malformed success payloads: %j', async (payload) => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(payload)))
      await expect(read('/environments', environmentPage)).rejects.toBeInstanceOf(ApiError)
    },
  )

  it('rejects invalid JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('not JSON')))
    await expect(read('/environments', environmentPage)).rejects.toThrow('unexpected response')
  })

  it('posts a typed request to the same-origin API with its stable idempotency key', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({
      environmentId: demoEnvironment.id, operationId: demoOperation.id,
      environmentStatus: 'REQUESTED', operationStatus: 'PENDING',
    }, 202))
    vi.stubGlobal('fetch', fetchMock)
    const payload = { name: 'demo', templateId: 'sample-service' as const, ttlHours: 8 }
    expect(await createEnvironment(payload, 'request-key-123')).toEqual({
      environmentId: demoEnvironment.id, operationId: demoOperation.id,
      environmentStatus: 'REQUESTED', operationStatus: 'PENDING',
    })
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/environments', expect.objectContaining({
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': 'request-key-123',
      },
      body: JSON.stringify(payload),
    }))
  })

  it('returns structured field validation errors without trusting response messages', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      code: 'VALIDATION_ERROR', message: '<script>internal</script>', requestId: 'request-123',
      fieldErrors: [{ field: 'name', message: 'must start with a letter or digit' }, { field: 'internal', message: 'secret' }],
    }, 400)))
    await expect(createEnvironment({ name: '', templateId: 'sample-service', ttlHours: 8 }, 'request-key-123'))
      .rejects.toMatchObject({
        message: 'Some fields need attention.', requestId: 'request-123', code: 'VALIDATION_ERROR',
        fieldErrors: [{ field: 'name', message: 'must start with a letter or digit' }],
        outcomeUncertain: false,
      })
  })

  it('sanitizes server errors and marks uncertain outcomes for same-key retry', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      code: 'INTERNAL_ERROR', message: 'database password leaked', requestId: 'request-500',
    }, 500)))
    await expect(createEnvironment({ name: 'demo', templateId: 'sample-service', ttlHours: 8 }, 'request-key-123'))
      .rejects.toMatchObject({
        message: expect.stringContaining('may have been accepted'),
        requestId: 'request-500', outcomeUncertain: true,
      })
    await expect(createEnvironment({ name: 'demo', templateId: 'sample-service', ttlHours: 8 }, 'request-key-123'))
      .rejects.not.toThrow('database password leaked')
  })

  it('treats malformed acceptance and idempotency conflict as recoverable-key outcomes', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ environmentId: 'not-a-uuid' }, 202))
      .mockResolvedValueOnce(json({
        code: 'IDEMPOTENCY_CONFLICT', message: 'untrusted message', requestId: 'request-conflict',
      }, 409)))
    const payload = { name: 'demo', templateId: 'sample-service' as const, ttlHours: 8 }
    await expect(createEnvironment(payload, 'same-key')).rejects.toMatchObject({
      message: 'The API returned an unexpected creation response.', outcomeUncertain: true,
    })
    await expect(createEnvironment(payload, 'same-key')).rejects.toMatchObject({
      message: expect.stringContaining('earlier request may already exist'),
      requestId: 'request-conflict', code: 'IDEMPOTENCY_CONFLICT',
    })
  })
})
