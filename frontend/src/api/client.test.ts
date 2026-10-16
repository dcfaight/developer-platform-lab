import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, environment, environmentPage, operationPage, read } from './client'
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
})
