import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { REFRESH_INTERVAL } from './api/useResource'
import { signIn } from './api/client'
import { setSession } from './api/session'
import { demoEnvironment, demoOperation, json, operatorSession, paged } from './test/fixtures'

beforeEach(() => {
  window.history.replaceState(null, '', '/')
  setSession(operatorSession)
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  window.sessionStorage.clear()
  setSession(null)
})

function mockPortal(operation = demoOperation) {
  const fetchMock = vi.fn(async (url: string) => {
    if (url.includes('/operations?')) return json(paged([operation]))
    if (url.includes(`/environments/${demoEnvironment.id}`)) return json(demoEnvironment)
    return json(paged([demoEnvironment]))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function createdPortal(postResponse: () => Promise<Response>) {
  const createdEnvironment = { ...demoEnvironment, name: 'Interview Env', status: 'REQUESTED',
    latestOperation: { ...demoEnvironment.latestOperation!, status: 'PENDING', progressStep: 'ACCEPTED' } }
  const createdOperation = { ...demoOperation, status: 'PENDING', progressStep: 'ACCEPTED', events: [] }
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'POST') return postResponse()
    if (url.includes(`/environments/${demoEnvironment.id}/operations?`)) return json(paged([createdOperation]))
    if (url.includes(`/environments/${demoEnvironment.id}`)) return json(createdEnvironment)
    if (url.includes('/operations?')) return json(paged([]))
    return json(paged([]))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function stubCreationKey(...keys: string[]) {
  const randomUUID = vi.fn()
  for (const key of keys) randomUUID.mockReturnValueOnce(key)
  vi.stubGlobal('crypto', { randomUUID })
  return randomUUID
}

describe('portal', () => {
  it('uses the Internal Developer Platform brand', () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(paged([]))))
    render(<App />)
    expect(screen.getByRole('link', { name: 'IDP Internal Developer Platform' })).toBeTruthy()
  })

  it('shows loading and a useful empty state without write actions', async () => {
    let resolve!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((done) => { resolve = done })))
    render(<App />)
    expect(screen.getByRole('status').textContent).toBe('Loading…')
    await act(async () => resolve(json(paged([]))))
    expect(screen.getByRole('heading', { name: 'No environments yet' })).toBeTruthy()
    expect(screen.getAllByRole('link', { name: 'Create environment' })).toHaveLength(2)
    expect(screen.queryByRole('region', { name: 'Status summary for this page' })).toBeNull()
    expect(screen.queryByRole('button', { name: /create|delete/i })).toBeNull()
  })

  describe('environment creation', () => {
    beforeEach(() => {
      stubCreationKey('create-key-1', 'create-key-2')
      setSession(operatorSession)
    })

    it('offers an accessible form with approved template, default lifetime, and safety guidance', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(paged([]))))
      render(<App />)
      await userEvent.click(screen.getByRole('link', { name: 'Create environment' }))
      expect(screen.getByRole('heading', { name: 'Create environment' })).toBeTruthy()
      expect(screen.getByLabelText('Environment name')).toBeTruthy()
      expect(screen.getByLabelText('Approved template')).toBeTruthy()
      expect(screen.getByLabelText('Lifetime (hours)')).toHaveProperty('value', '8')
      expect(screen.getByText(/does not mean the environment is ready/)).toBeTruthy()
      expect(screen.getByText(/not automatically deleted/)).toBeTruthy()
    })

    it('trims and submits a valid request with the backend defaults and navigates to persisted history', async () => {
      const fetchMock = createdPortal(async () => json({
        environmentId: demoEnvironment.id, operationId: demoOperation.id,
        environmentStatus: 'REQUESTED', operationStatus: 'PENDING',
      }, 202))
      render(<App />)
      await userEvent.click(screen.getByRole('link', { name: 'Create environment' }))
      await userEvent.type(screen.getByLabelText('Environment name'), '  Interview Env  ')
      await userEvent.click(screen.getByRole('button', { name: 'Create environment' }))
      expect(await screen.findByRole('heading', { name: 'Operation history' })).toBeTruthy()
      expect(window.location.hash).toBe(`#environment/${demoEnvironment.id}`)
      const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
      expect(post).toBeDefined()
      const request = post?.[1]
      if (!request) throw new Error('Expected a creation request.')
      expect(JSON.parse(request.body as string)).toEqual({
        name: 'Interview Env', templateId: 'sample-service', ttlHours: 8,
      })
      expect((request.headers as Record<string, string>)['Idempotency-Key']).toBe('create-key-1')
      expect(screen.getByText('REQUESTED')).toBeTruthy()
      expect(screen.getAllByText('PENDING')).toHaveLength(2)
      expect(window.sessionStorage.getItem('dpl.environment-creation.v1')).toBeNull()
    })

    it.each([
      ['name', '  ', 'Enter an environment name.'],
      ['name', '-starts-wrong', /start with a letter or digit/],
      ['name', 'éclair', /ASCII letters/],
      ['name', 'a'.repeat(64), /1–63/],
      ['ttl', '0', /whole number from 1 to 24/],
      ['ttl', '25', /whole number from 1 to 24/],
      ['ttl', '1.5', /whole number from 1 to 24/],
    ])('validates invalid %s input %s before posting', async (field, value, error) => {
      const fetchMock = vi.fn().mockResolvedValue(json(paged([])))
      vi.stubGlobal('fetch', fetchMock)
      window.history.replaceState(null, '', '/#create')
      render(<App />)
      const input = field === 'name' ? screen.getByLabelText('Environment name') : screen.getByLabelText('Lifetime (hours)')
      await userEvent.clear(input)
      await userEvent.type(input, value)
      await userEvent.click(screen.getByRole('button', { name: 'Create environment' }))
      expect(await screen.findByText(error, { selector: '.field-error' })).toBeTruthy()
      expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
      expect(input.getAttribute('aria-invalid')).toBe('true')
    })

    it.each(['1', '24'])('accepts lifetime boundary %s', async (value) => {
      const fetchMock = createdPortal(async () => json({
        environmentId: demoEnvironment.id, operationId: demoOperation.id,
        environmentStatus: 'REQUESTED', operationStatus: 'PENDING',
      }, 202))
      window.history.replaceState(null, '', '/#create')
      render(<App />)
      const lifetime = screen.getByLabelText('Lifetime (hours)')
      await userEvent.clear(lifetime)
      await userEvent.type(lifetime, value)
      await userEvent.type(screen.getByLabelText('Environment name'), 'Boundary')
      await userEvent.click(screen.getByRole('button', { name: 'Create environment' }))
      await screen.findByRole('heading', { name: 'Operation history' })
      const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
      expect(post).toBeDefined()
      const request = post?.[1]
      if (!request) throw new Error('Expected a creation request.')
      expect(JSON.parse(request.body as string).ttlHours).toBe(Number(value))
    })

    it('shows server field errors and correlation IDs while preserving editable values', async () => {
      const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => init?.method === 'POST'
        ? json({
          code: 'VALIDATION_ERROR', message: 'Request validation failed.', requestId: 'validation-123',
          fieldErrors: [{ field: 'name', message: 'must be unique' }],
        }, 400)
        : json(paged([])))
      vi.stubGlobal('fetch', fetchMock)
      window.history.replaceState(null, '', '/#create')
      render(<App />)
      const name = screen.getByLabelText('Environment name')
      await userEvent.type(name, 'My environment')
      await userEvent.click(screen.getByRole('button', { name: 'Create environment' }))
      expect(await screen.findByText('must be unique')).toBeTruthy()
      expect(screen.getByRole('status').textContent).toContain('validation-123')
      expect(name).toHaveProperty('value', 'My environment')
      expect(name.hasAttribute('disabled')).toBe(false)
    })

    it('prevents rapid double submit and replays the exact pending intent after remount', async () => {
      let rejectFirst!: (error: Error) => void
      const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
        if (init?.method === 'POST') {
          if (fetchMock.mock.calls.filter(([, request]) => request?.method === 'POST').length === 1) {
            return new Promise<Response>((_resolve, reject) => { rejectFirst = reject })
          }
          return json({
            environmentId: demoEnvironment.id, operationId: demoOperation.id,
            environmentStatus: 'REQUESTED', operationStatus: 'PENDING',
          }, 202)
        }
        if (_url.includes(`/environments/${demoEnvironment.id}/operations?`)) return json(paged([demoOperation]))
        if (_url.includes(`/environments/${demoEnvironment.id}`)) return json(demoEnvironment)
        return json(paged([]))
      })
      vi.stubGlobal('fetch', fetchMock)
      window.history.replaceState(null, '', '/#create')
      const { unmount } = render(<App />)
      await userEvent.type(screen.getByLabelText('Environment name'), 'Safe retry')
      const submit = screen.getByRole('button', { name: 'Create environment' })
      fireEvent.click(submit)
      fireEvent.click(submit)
      await waitFor(() => expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1))
      const firstPost = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
      expect(firstPost).toBeDefined()
      const firstRequest = firstPost?.[1]
      await act(async () => rejectFirst(new TypeError('connection lost')))
      expect(await screen.findByRole('button', { name: 'Retry the same request' })).toBeTruthy()
      unmount()
      render(<App />)
      expect(screen.getByLabelText('Environment name')).toHaveProperty('value', 'Safe retry')
      await userEvent.click(screen.getByRole('button', { name: 'Retry the same request' }))
      await screen.findByRole('heading', { name: 'Operation history' })
      const posts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')
      expect(posts).toHaveLength(2)
      const retriedRequest = posts[1]?.[1]
      if (!firstRequest || !retriedRequest) throw new Error('Expected two creation requests.')
      expect(retriedRequest.body).toBe(firstRequest.body)
      expect((retriedRequest.headers as Record<string, string>)['Idempotency-Key'])
        .toBe((firstRequest.headers as Record<string, string>)['Idempotency-Key'])
      expect(retriedRequest.headers).toEqual(firstRequest.headers)
    })

    it('requires explicit confirmation before replacing an uncertain intent with a fresh key', async () => {
      const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => init?.method === 'POST'
        ? json({ code: 'INTERNAL_ERROR', requestId: 'server-500' }, 500)
        : json(paged([])))
      vi.stubGlobal('fetch', fetchMock)
      window.history.replaceState(null, '', '/#create')
      render(<App />)
      await userEvent.type(screen.getByLabelText('Environment name'), 'Maybe exists')
      await userEvent.click(screen.getByRole('button', { name: 'Create environment' }))
      expect(await screen.findByRole('button', { name: 'Retry the same request' })).toBeTruthy()
      await userEvent.click(screen.getByRole('button', { name: 'Start a new request instead' }))
      expect(screen.getByText(/The earlier request may already exist/).textContent).toContain('does not cancel it')
      await userEvent.click(screen.getByRole('button', { name: 'I understand — start a new request' }))
      expect(screen.getByRole('status').textContent).toContain('may already have created')
      expect(screen.getByLabelText('Environment name')).toHaveProperty('value', 'Maybe exists')
      await userEvent.click(screen.getByRole('button', { name: 'Create environment' }))
      await screen.findByRole('button', { name: 'Retry the same request' })
      const posts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')
      expect(posts).toHaveLength(2)
      const firstRequest = posts[0]?.[1]
      const secondRequest = posts[1]?.[1]
      if (!firstRequest || !secondRequest) throw new Error('Expected two creation requests.')
      expect((firstRequest.headers as Record<string, string>)['Idempotency-Key']).toBe('create-key-1')
      expect((secondRequest.headers as Record<string, string>)['Idempotency-Key']).toBe('create-key-2')
    })

    it('supports keyboard submission and reports an idempotency conflict without retrying that key', async () => {
      let posts = 0
      const fetchMock = createdPortal(async () => {
        posts += 1
        return posts === 1
          ? json({ code: 'IDEMPOTENCY_CONFLICT', requestId: 'conflict-123' }, 409)
          : json({
            environmentId: demoEnvironment.id, operationId: demoOperation.id,
            environmentStatus: 'REQUESTED', operationStatus: 'PENDING',
          }, 202)
      })
      window.history.replaceState(null, '', '/#create')
      render(<App />)
      await userEvent.type(screen.getByLabelText('Environment name'), 'Keyboard request')
      await userEvent.keyboard('{Enter}')
      expect(await screen.findByText(/request key belongs to a different request/)).toBeTruthy()
      expect(screen.getByRole('alert').textContent).toContain('conflict-123')
      expect(screen.queryByRole('button', { name: 'Retry the same request' })).toBeNull()
      await userEvent.click(screen.getByRole('button', { name: 'Start a new request instead' }))
      await userEvent.click(screen.getByRole('button', { name: 'I understand — start a new request' }))
      await userEvent.click(screen.getByRole('button', { name: 'Create environment' }))
      await screen.findByRole('heading', { name: 'Operation history' })
      const requests = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')
      expect(requests).toHaveLength(2)
      const firstRequest = requests[0]?.[1]
      const secondRequest = requests[1]?.[1]
      if (!firstRequest || !secondRequest) throw new Error('Expected two creation requests.')
      expect((firstRequest.headers as Record<string, string>)['Idempotency-Key']).toBe('create-key-1')
      expect((secondRequest.headers as Record<string, string>)['Idempotency-Key']).toBe('create-key-2')
    })

    it('keeps same-key retry available when browser session storage is blocked', async () => {
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new DOMException('blocked', 'SecurityError')
      })
      let posts = 0
      const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
        if (init?.method === 'POST') {
          posts += 1
          return posts === 1 ? Promise.reject(new TypeError('connection lost')) : json({
            environmentId: demoEnvironment.id, operationId: demoOperation.id,
            environmentStatus: 'REQUESTED', operationStatus: 'PENDING',
          }, 202)
        }
        if (_url.includes(`/environments/${demoEnvironment.id}/operations?`)) return json(paged([demoOperation]))
        if (_url.includes(`/environments/${demoEnvironment.id}`)) return json(demoEnvironment)
        return json(paged([]))
      })
      vi.stubGlobal('fetch', fetchMock)
      window.history.replaceState(null, '', '/#create')
      render(<App />)
      await userEvent.type(screen.getByLabelText('Environment name'), 'Storage unavailable')
      await userEvent.click(screen.getByRole('button', { name: 'Create environment' }))
      expect(await screen.findByText(/could not save the retry intent/)).toBeTruthy()
      await userEvent.click(screen.getByRole('button', { name: 'Retry the same request' }))
      await screen.findByRole('heading', { name: 'Operation history' })
      const requests = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')
      expect(requests).toHaveLength(2)
      const firstRequest = requests[0]?.[1]
      const secondRequest = requests[1]?.[1]
      if (!firstRequest || !secondRequest) throw new Error('Expected two creation requests.')
      expect(secondRequest.body).toBe(firstRequest.body)
      expect((firstRequest.headers as Record<string, string>)['Idempotency-Key'])
        .toBe((secondRequest.headers as Record<string, string>)['Idempotency-Key'])
    })

    it('does not navigate away from a newer route when a submission completes late', async () => {
      let resolvePost!: (response: Response) => void
      const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => init?.method === 'POST'
        ? new Promise<Response>((resolve) => { resolvePost = resolve })
        : json(paged([])))
      vi.stubGlobal('fetch', fetchMock)
      window.history.replaceState(null, '', '/#create')
      render(<App />)
      await userEvent.type(screen.getByLabelText('Environment name'), 'Late response')
      fireEvent.click(screen.getByRole('button', { name: 'Create environment' }))
      await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(true))
      window.location.hash = '#environments'
      await waitFor(() => expect(screen.getByRole('heading', { name: 'Environments' })).toBeTruthy())
      await act(async () => resolvePost(json({
        environmentId: demoEnvironment.id, operationId: demoOperation.id,
        environmentStatus: 'REQUESTED', operationStatus: 'PENDING',
      }, 202)))
      expect(window.location.hash).toBe('#environments')
      expect(screen.queryByRole('heading', { name: 'Operation history' })).toBeNull()
    })
  })

  it('summarizes only the loaded page, not the inventory total', async () => {
    const items = ['READY', 'REQUESTED', 'PROVISIONING', 'FAILED', 'DELETING', 'DELETE_FAILED'].map((status, index) => ({
      ...demoEnvironment, id: `environment-${index}`, name: `Environment ${index}`, status,
    }))
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(paged(items, { totalElements: 24, totalPages: 3 }))))
    render(<App />)
    const summary = await screen.findByRole('region', { name: 'Status summary for this page' })
    expect(summary.textContent).toContain('This page only · 6 environments')
    for (const [label, count] of [['Ready', '1'], ['In progress', '3'], ['Failed', '2']]) {
      expect(within(summary).getByText(label).nextElementSibling?.textContent).toBe(count)
    }
    expect(screen.getByText('24 total')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Skip to content' }).getAttribute('href')).toBe('#main-content')
  })

  it('lists environment metadata and navigates to durable progress history', async () => {
    const fetchMock = mockPortal()
    render(<App />)
    const link = await screen.findByRole('link', { name: demoEnvironment.name })
    expect(screen.getByRole('region', { name: 'Status summary for this page' }).textContent)
      .toContain('This page only · 1 environment ·')
    expect(screen.getByText(demoEnvironment.namespace)).toBeTruthy()
    expect(screen.getByText(demoEnvironment.templateId)).toBeTruthy()
    expect(screen.getByText('Active operation')).toBeTruthy()
    expect(screen.getByText('RUNNING')).toBeTruthy()
    expect(document.querySelector(`time[datetime="${demoEnvironment.expiresAt}"]`)).toBeTruthy()
    await userEvent.click(link)
    expect(await screen.findByRole('heading', { name: 'Operation history' })).toBeTruthy()
    expect(await screen.findByText(demoOperation.events[0].message)).toBeTruthy()
    expect(screen.getByText('1 recorded event · Oldest first')).toBeTruthy()
    expect(screen.getByText(demoOperation.correlationId!)).toBeTruthy()
    expect(fetchMock.mock.calls.some(([url]) => url.endsWith('/operations?page=0&size=5'))).toBe(true)
    await userEvent.click(screen.getByRole('link', { name: 'Skip to content' }))
    expect(document.activeElement).toBe(screen.getByRole('main'))
    expect(window.location.hash).toBe(`#environment/${demoEnvironment.id}`)
    expect(screen.getByRole('heading', { name: 'Operation history' })).toBeTruthy()
    await userEvent.click(screen.getByRole('link', { name: /All environments/ }))
    expect(await screen.findByRole('link', { name: demoEnvironment.name })).toBeTruthy()
  })

  it('renders lifecycle milestones with elapsed timing and total duration', async () => {
    window.history.replaceState(null, '', `/#environment/${demoEnvironment.id}`)
    const at = (seconds: number) => new Date(Date.parse(demoOperation.createdAt) + seconds * 1000).toISOString()
    const event = (type: string, code: string, seconds: number) => ({ type, code, message: `${code} message`,
      correlationId: demoOperation.correlationId, createdAt: at(seconds) })
    mockPortal({ ...demoOperation, status: 'SUCCEEDED', progressStep: 'READY', completedAt: at(95),
      events: [event('CLAIMED', 'CLAIMED', 1), event('PROGRESS', 'NAMESPACE_CREATED', 2),
        event('PROGRESS', 'WORKLOAD_CREATED', 3), event('SUCCEEDED', 'ENVIRONMENT_READY', 95)] })
    render(<App />)
    const card = await screen.findByRole('article', { name: `Operation ${demoOperation.id}` })
    const titles = [...card.querySelectorAll('.timeline strong')].map((title) => title.textContent)
    expect(titles).toEqual(['CLAIMED', 'NAMESPACE CREATED', 'WORKLOAD CREATED', 'SUCCEEDED'])
    expect(within(card).getByText('WORKLOAD_CREATED')).toBeTruthy()
    expect(within(card).getByText('+3s after acceptance')).toBeTruthy()
    expect(within(card).getByText('+1m 35s after acceptance')).toBeTruthy()
    expect(within(card).getByText('Total duration').nextElementSibling?.textContent).toBe('1m 35s')
  })

  it.each(['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'])(
    'renders %s operations and safely displays sanitized message fields', async (status) => {
      window.history.replaceState(null, '', `/#environment/${demoEnvironment.id}`)
      const message = '<img src=x onerror=alert(1)>'
      mockPortal({ ...demoOperation, status, failureCode: 'KUBERNETES_REQUEST_REJECTED',
        failureMessage: message, events: [] })
      render(<App />)
      const card = await screen.findByRole('article', { name: `Operation ${demoOperation.id}` })
      expect(within(card).getByText(status)).toBeTruthy()
      expect(within(card).getByText(message)).toBeTruthy()
      expect(within(card).getByText(/No progress events yet/)).toBeTruthy()
      expect(within(card).getByText(status === 'FAILED' ? 'Failure summary' : 'Last failure (retry may be pending)'))
        .toBeTruthy()
      expect(card.querySelector('img')).toBeNull()
    },
  )

  it('shows the latest failure and remediation even if operation history is unavailable', async () => {
    window.history.replaceState(null, '', `/#environment/${demoEnvironment.id}`)
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.includes('/operations?')
      ? json({ message: 'History unavailable.' }, 503)
      : json({ ...demoEnvironment, status: 'DELETE_FAILED',
        latestOperation: { ...demoEnvironment.latestOperation, type: 'DELETE_ENVIRONMENT', status: 'FAILED',
          failureCode: 'KUBERNETES_FORBIDDEN', failureMessage: 'Kubernetes access was denied.' } })))
    render(<App />)
    expect(await screen.findByText('Kubernetes access was denied.')).toBeTruthy()
    expect(screen.getByText(/Role\/ClusterRole and bindings/)).toBeTruthy()
    expect(await screen.findByText('History unavailable.')).toBeTruthy()
  })

  it('shows errors and request IDs, then recovers on retry', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json({
      message: 'The request could not be completed.', requestId: 'request-123',
    }, 500)).mockResolvedValue(json(paged([demoEnvironment])))
    vi.stubGlobal('fetch', fetchMock)
    render(<App />)
    expect((await screen.findByRole('alert')).textContent).toContain('request-123')
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('link', { name: demoEnvironment.name })).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('paginates environments and distinguishes missing operations', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) =>
      json(paged([{ ...demoEnvironment, latestOperation: null }],
        { page: url.includes('page=1') ? 1 : 0, totalElements: 11, totalPages: 2 })))
    vi.stubGlobal('fetch', fetchMock)
    render(<App />)
    expect(await screen.findByText('No operations')).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('Page 2 of 2')).toBeTruthy()
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/environments?page=1&size=10')
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Next' }).disabled).toBe(true)
  })

  it('handles a missing environment deep link', async () => {
    window.history.replaceState(null, '', `/#environment/${demoEnvironment.id}`)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ message: 'Environment not found.' }, 404)))
    render(<App />)
    expect((await screen.findByRole('alert')).textContent).toContain('Environment not found.')
    expect(screen.getByRole('link', { name: /All environments/ })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: 'Operation history' })).toBeNull()
  })

  it('paginates operation history and handles an empty history page', async () => {
    window.history.replaceState(null, '', `/#environment/${demoEnvironment.id}`)
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (!url.includes('/operations?')) return json(demoEnvironment)
      return json(paged(url.includes('page=1') ? [] : [demoOperation],
        { page: url.includes('page=1') ? 1 : 0, totalElements: 6, totalPages: 2 }))
    }))
    render(<App />)
    const history = await screen.findByRole('region', { name: 'Operation history' })
    await within(history).findByRole('article')
    await userEvent.click(within(history).getByRole('button', { name: 'Next' }))
    expect(await within(history).findByText('No operations on this page.')).toBeTruthy()
  })

  it('distinguishes an environment with no recorded operations from an empty page', async () => {
    window.history.replaceState(null, '', `/#environment/${demoEnvironment.id}`)
    vi.stubGlobal('fetch', vi.fn(async (url: string) =>
      json(url.includes('/operations?') ? paged([]) : demoEnvironment)))
    render(<App />)
    expect(await screen.findByText('No operations recorded for this environment yet.')).toBeTruthy()
  })

  it('refreshes periodically, keeps stale data on error, and aborts on unmount', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.fn().mockResolvedValueOnce(json(paged([demoEnvironment])))
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValue(json(paged([{ ...demoEnvironment, status: 'READY' }])))
    vi.stubGlobal('fetch', fetchMock)
    const { unmount } = render(<App />)
    await act(async () => {})
    expect(screen.getByText('PROVISIONING')).toBeTruthy()
    await act(async () => { await vi.advanceTimersByTimeAsync(REFRESH_INTERVAL) })
    expect(screen.getByRole('alert').textContent).toContain('showing last loaded data')
    expect(screen.getByRole('link', { name: demoEnvironment.name })).toBeTruthy()
    await act(async () => { await vi.advanceTimersByTimeAsync(REFRESH_INTERVAL) })
    expect(screen.getByText('READY')).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
    const signal = (fetchMock.mock.calls[2] as unknown as [string, RequestInit])[1].signal!
    unmount()
    expect(signal.aborted).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('does not let a late response from a previous page overwrite the current page', async () => {
    let resolve!: (response: Response) => void
    const fetchMock = vi.fn().mockResolvedValueOnce(json(paged([demoEnvironment], { totalPages: 2 })))
      .mockImplementationOnce(() => new Promise<Response>((done) => { resolve = done }))
      .mockResolvedValue(json(paged([{ ...demoEnvironment, name: 'Current page' }])))
    vi.stubGlobal('fetch', fetchMock)
    render(<App />)
    await screen.findByRole('link', { name: demoEnvironment.name })
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByRole('link', { name: 'Current page' })
    await act(async () => resolve(json(paged([{ ...demoEnvironment, name: 'Old response' }]))))
    expect(screen.queryByRole('link', { name: 'Old response' })).toBeNull()
  })
})

describe('persisted creation ownership', () => {
  const storageKey = 'dpl.environment-creation.v1'
  const payload = { name: 'Private persisted environment', templateId: 'sample-service', ttlHours: 19, teamId: 'private-team' }
  const otherOperator = { ...operatorSession, identity: { ...operatorSession.identity, username: 'bob' } }

  it.each([null, 'alice', 'bob'])('removes ownerless legacy intents without exposing their payload to %s', (username) => {
    setSession(username === null ? null : username === 'alice' ? operatorSession : otherOperator)
    window.sessionStorage.setItem(storageKey, JSON.stringify({ version: 1, key: 'legacy-ownerless-key', payload }))
    window.history.replaceState(null, '', '/#create')
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    render(<App />)
    expect(screen.getByLabelText('Environment name')).toHaveProperty('value', '')
    expect(screen.getByLabelText('Lifetime (hours)')).toHaveProperty('value', '8')
    expect(screen.getByLabelText('Team')).toHaveProperty('value', '')
    expect(screen.queryByText(/Private persisted environment/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Retry the same request' })).toBeNull()
    expect(window.sessionStorage.getItem(storageKey)).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it.each([null, 'bob', 'Alice'])('restores owned persisted payload only after exact authenticated match, not %s', (username) => {
    setSession(username === null ? null : { ...operatorSession, identity: { ...operatorSession.identity, username } })
    const stored = JSON.stringify({ version: 1, key: 'owned-request-key', username: 'alice', payload })
    window.sessionStorage.setItem(storageKey, stored)
    window.history.replaceState(null, '', '/#create')
    vi.stubGlobal('fetch', vi.fn())
    render(<App />)
    expect(screen.getByLabelText('Environment name')).toHaveProperty('value', '')
    expect(screen.getByLabelText('Lifetime (hours)')).toHaveProperty('value', '8')
    expect(screen.queryByRole('button', { name: 'Retry the same request' })).toBeNull()
    expect(screen.queryByText(/Private persisted environment/)).toBeNull()
    act(() => setSession(operatorSession))
    expect(screen.getByLabelText('Environment name')).toHaveProperty('value', payload.name)
    expect(screen.getByLabelText('Lifetime (hours)')).toHaveProperty('value', '19')
    expect(screen.getByRole('button', { name: 'Retry the same request' })).toBeTruthy()
    expect(window.sessionStorage.getItem(storageKey)).toBe(stored)
    act(() => setSession(otherOperator))
    expect(screen.getByLabelText('Environment name')).toHaveProperty('value', '')
    expect(screen.queryByRole('button', { name: 'Retry the same request' })).toBeNull()
    act(() => setSession(null))
    expect(screen.getByLabelText('Environment name')).toHaveProperty('value', '')
    expect(screen.queryByText(/Private persisted environment/)).toBeNull()
  })
})

function identityPortal(me: () => Promise<Response>, post?: () => Promise<Response>) {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/v1/me') return me()
      if (init?.method === 'POST' && post) return post()
      if (url.includes(`/environments/${demoEnvironment.id}/operations?`)) return json(paged([demoOperation]))
      if (url.includes(`/environments/${demoEnvironment.id}`)) return json(demoEnvironment)
      return json(paged([]))
    })

    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
}

describe('team access and session isolation', () => {
      const developer = {
        ...operatorSession,
        identity: { username: 'developer', roles: ['DEVELOPER'], teams: ['team-one', 'team-two'] },
      }

      it('does not load any inventory while signed out', () => {
        setSession(null)
        const fetchMock = vi.fn()
        vi.stubGlobal('fetch', fetchMock)
        render(<App />)
        expect(screen.getByText(/Sign in above to view environments/)).toBeTruthy()
        expect(fetchMock).not.toHaveBeenCalled()
      })

      it.each(['team-one', 'team-two'])('requires and submits the selected developer team: %s', async (team) => {
        setSession(developer)
        window.history.replaceState(null, '', '/#create')
        stubCreationKey('team-request-key')
        const fetchMock = createdPortal(async () => json({
          environmentId: demoEnvironment.id, operationId: demoOperation.id,
          environmentStatus: 'REQUESTED', operationStatus: 'PENDING',
        }, 202))
        render(<App />)
        await userEvent.type(screen.getByLabelText('Environment name'), 'Team environment')
        await userEvent.click(screen.getByRole('button', { name: 'Create environment' }))
        expect(screen.getByText('Choose one of your teams.', { selector: '.field-error' })).toBeTruthy()
        expect(fetchMock).not.toHaveBeenCalled()
        await userEvent.selectOptions(screen.getByLabelText('Team'), team)
        await userEvent.click(screen.getByRole('button', { name: 'Create environment' }))
        await screen.findByRole('heading', { name: 'Operation history' })
        const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
        expect(JSON.parse(post![1]!.body as string)).toEqual({
          name: 'Team environment', templateId: 'sample-service', ttlHours: 8, teamId: team,
        })
      })

      it('shows all returned memberships without sending a team filter or global metrics request', async () => {
        setSession(developer)
        const fetchMock = vi.fn().mockResolvedValue(json(paged([
          { ...demoEnvironment, teamId: 'team-one', createdBy: 'developer' },
          { ...demoEnvironment, id: demoOperation.id, name: 'Second team environment', teamId: 'team-two', createdBy: 'colleague' },
        ])))
        vi.stubGlobal('fetch', fetchMock)
        render(<App />)
        await screen.findByRole('link', { name: 'Second team environment' })
        expect(screen.getAllByText('team-one').length).toBeGreaterThan(0)
        expect(screen.getAllByText('team-two').length).toBeGreaterThan(0)
        expect(screen.getByText('colleague')).toBeTruthy()
        expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['/api/v1/environments?page=0&size=10'])
      })

      it('lets an operator explicitly create a team-owned environment', async () => {
        window.history.replaceState(null, '', '/#create')
        stubCreationKey('operator-team-key')
        const fetchMock = createdPortal(async () => json({
          environmentId: demoEnvironment.id, operationId: demoOperation.id,
          environmentStatus: 'REQUESTED', operationStatus: 'PENDING',
        }, 202))
        render(<App />)
        await userEvent.type(screen.getByLabelText('Environment name'), 'Operator team environment')
        await userEvent.type(screen.getByLabelText('Other team ID'), 'team-other')
        await userEvent.click(screen.getByRole('button', { name: 'Create environment' }))
        await screen.findByRole('heading', { name: 'Operation history' })
        const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
        expect(JSON.parse(post![1]!.body as string).teamId).toBe('team-other')
      })

      it.each(['inventory', 'detail'])('immediately clears loaded %s and history on account switches', async (view) => {
        if (view === 'detail') window.history.replaceState(null, '', `/#environment/${demoEnvironment.id}`)
        mockPortal()
        render(<App />)
        if (view === 'detail') await screen.findByText(demoOperation.events[0].message)
        else await screen.findByRole('link', { name: demoEnvironment.name })
        vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => {})))
        act(() => setSession(developer))
        expect(screen.queryByText(demoEnvironment.name)).toBeNull()
        expect(screen.queryByText(demoOperation.events[0].message)).toBeNull()
        expect(screen.queryByRole('heading', { name: 'Operation history' })).toBeNull()
        expect(screen.queryByRole('alert')).toBeNull()
        expect(screen.getByRole('status').textContent).toBe('Loading…')
      })

      it('clears loaded inventory and errors as soon as a failing sign-in begins', async () => {
        let fail!: (response: Response) => void
        const fetchMock = vi.fn(async (url: string) => url === '/api/v1/me'
          ? new Promise<Response>((resolve) => { fail = resolve })
          : json(paged([demoEnvironment])))
        vi.stubGlobal('fetch', fetchMock)
        render(<App />)
        await screen.findByRole('link', { name: demoEnvironment.name })
        let pending!: Promise<unknown>
        act(() => { pending = signIn('bob', 'incorrect').catch(() => undefined) })
        expect(screen.queryByText(demoEnvironment.name)).toBeNull()
        expect(screen.queryByRole('alert')).toBeNull()
        await act(async () => { fail(json({}, 401)); await pending })
        expect(screen.queryByText(demoEnvironment.name)).toBeNull()
      })

      it.each([200, 403])('ignores stale inventory responses after an account switch (HTTP %s)', async (status) => {
        let resolve!: (response: Response) => void
        const fetchMock = vi.fn()
          .mockImplementationOnce(() => new Promise<Response>((done) => { resolve = done }))
          .mockResolvedValue(json(paged([])))
        vi.stubGlobal('fetch', fetchMock)
        render(<App />)
        act(() => setSession(developer))
        await screen.findByRole('heading', { name: 'No environments yet' })
        await act(async () => resolve(json(status === 200 ? paged([demoEnvironment]) : { message: 'Private old error' }, status)))
        expect(screen.queryByText(demoEnvironment.name)).toBeNull()
        expect(screen.queryByText('Private old error')).toBeNull()
        expect(screen.queryByRole('alert')).toBeNull()
      })

      it('clears previously loaded API errors on sign-out', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ message: 'Old account error' }, 403)))
        render(<App />)
        await screen.findByRole('alert')
        await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))
        expect(screen.queryByText(/Old account error/)).toBeNull()
        expect(screen.queryByRole('alert')).toBeNull()
      })

      it.each([202, 401])('ignores stale creation results after an account switch (HTTP %s)', async (status) => {
        window.history.replaceState(null, '', '/#create')
        stubCreationKey('stale-creation-key')
        let resolve!: (response: Response) => void
        createdPortal(() => new Promise<Response>((done) => { resolve = done }))
        render(<App />)
        await userEvent.type(screen.getByLabelText('Environment name'), 'Old private draft')
        await userEvent.click(screen.getByRole('button', { name: 'Create environment' }))
        act(() => setSession(developer))
        expect(screen.getByLabelText('Environment name')).toHaveProperty('value', '')
        expect(screen.queryByRole('button', { name: 'Retry the same request' })).toBeNull()
        await act(async () => resolve(json(status === 202 ? {
          environmentId: demoEnvironment.id, operationId: demoOperation.id,
          environmentStatus: 'REQUESTED', operationStatus: 'PENDING',
        } : { code: 'UNAUTHENTICATED' }, status)))
        expect(window.location.hash).toBe('#create')
        expect(screen.getByText(/Signed in as/).textContent).toContain('developer')
        expect(screen.queryByRole('alert')).toBeNull()
        expect(screen.queryByText(/Old private draft/)).toBeNull()
      })
    })
describe('operator sign-in', () => {
  beforeEach(() => setSession(null))
  async function signInThroughPortal(username = 'alice', password = 'test-only-alice-pass') {
    await userEvent.type(screen.getByLabelText('Username'), username)
    await userEvent.type(screen.getByLabelText('Password'), password)
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
  }

  it('requires sign-in and holds credentials only in memory', async () => {
    const fetchMock = identityPortal(async () => json(operatorSession.identity))
    window.history.replaceState(null, '', '/#create')
    render(<App />)
    expect(screen.getByText(/Sign in with a DEVELOPER or OPERATOR account/)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Create environment' })).toHaveProperty('disabled', true)
    await userEvent.type(screen.getByLabelText('Environment name'), 'Draft before sign-in')
    await signInThroughPortal()
    expect(await screen.findByText(/Signed in as/)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Create environment' })).toHaveProperty('disabled', false)
    const me = fetchMock.mock.calls.find(([url]) => url === '/api/v1/me')
    expect((me?.[1]?.headers as Record<string, string> | undefined)?.Authorization).toBe(operatorSession.authorization)
    expect(screen.getByLabelText('Environment name')).toBeTruthy()
    expect(screen.getByLabelText('Environment name')).toHaveProperty('value', 'Draft before sign-in')
    expect(window.sessionStorage.length).toBe(0)
    expect(window.localStorage.length).toBe(0)
    expect(document.cookie).toBe('')
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))
    expect(screen.getByRole('button', { name: 'Create environment' })).toHaveProperty('disabled', true)
  })

  it('reports rejected credentials without signing in', async () => {
    identityPortal(async () => json({ code: 'UNAUTHENTICATED', message: 'x', requestId: 'request-401' }, 401))
    render(<App />)
    await signInThroughPortal('alice', 'wrong')
    expect((await screen.findByRole('alert')).textContent).toMatch(/Sign-in failed/)
    expect(screen.queryByRole('button', { name: 'Sign out' })).toBeNull()
  })

  it('explains that a signed-in account without the operator role cannot create', async () => {
    identityPortal(async () => json({ username: 'cleanup-bot', roles: ['CLEANUP'], teams: [] }))
    window.history.replaceState(null, '', '/#create')
    render(<App />)
    await signInThroughPortal('cleanup-bot', 'test-only-cleanup-bot-pass')
    expect(await screen.findByText(/cleanup-bot, which cannot create environments/)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Create environment' })).toHaveProperty('disabled', true)
  })

  it.each([false, true])('keeps the same idempotency key after reauthentication (storage blocked: %s)', async (blocked) => {
    if (blocked) vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Storage blocked') })
    stubCreationKey('create-key-1', 'create-key-2')
    setSession(operatorSession)
    let posts = 0
    const fetchMock = identityPortal(async () => json(operatorSession.identity), async () => {
      posts += 1
      return posts === 1
        ? json({ code: 'UNAUTHENTICATED', message: 'x', requestId: 'request-401' }, 401)
        : json({ environmentId: demoEnvironment.id, operationId: demoOperation.id,
          environmentStatus: 'REQUESTED', operationStatus: 'PENDING' }, 202)
    })
    window.history.replaceState(null, '', '/#create')
    render(<App />)
    await userEvent.type(screen.getByLabelText('Environment name'), 'After sign-in')
    await userEvent.click(screen.getByRole('button', { name: 'Create environment' }))
    await screen.findByRole('button', { name: 'Sign in' })
    expect(screen.queryByText(/request-401/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Retry the same request' })).toBeNull()
    await signInThroughPortal()
    await screen.findByText(/Signed in as/)
    await userEvent.click(screen.getByRole('button', { name: 'Retry the same request' }))
    await screen.findByRole('heading', { name: 'Operation history' })
    const requests = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')
    expect(requests).toHaveLength(2)
    expect(requests.map(([, init]) => (init?.headers as Record<string, string> | undefined)?.['Idempotency-Key']))
      .toEqual(['create-key-1', 'create-key-1'])
    expect(requests[1]?.[1]?.body).toBe(requests[0]?.[1]?.body)
  })
})
