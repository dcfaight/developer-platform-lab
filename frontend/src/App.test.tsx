import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { REFRESH_INTERVAL } from './api/useResource'
import { demoEnvironment, demoOperation, json, paged } from './test/fixtures'

beforeEach(() => window.history.replaceState(null, '', '/'))
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
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

describe('read-only portal', () => {
  it('shows loading and a useful empty state without write actions', async () => {
    let resolve!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((done) => { resolve = done })))
    render(<App />)
    expect(screen.getByRole('status').textContent).toBe('Loading…')
    await act(async () => resolve(json(paged([]))))
    expect(screen.getByRole('heading', { name: 'No environments yet' })).toBeTruthy()
    expect(screen.getByText('POST /api/v1/environments')).toBeTruthy()
    expect(screen.queryByRole('region', { name: 'Status summary for this page' })).toBeNull()
    expect(screen.queryByRole('button', { name: /create|delete/i })).toBeNull()
  })

  it('summarizes only the loaded page, not the inventory total', async () => {
    const items = ['READY', 'REQUESTED', 'PROVISIONING', 'FAILED'].map((status, index) => ({
      ...demoEnvironment, id: `environment-${index}`, name: `Environment ${index}`, status,
    }))
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(paged(items, { totalElements: 24, totalPages: 3 }))))
    render(<App />)
    const summary = await screen.findByRole('region', { name: 'Status summary for this page' })
    expect(summary.textContent).toContain('This page only · 4 environments')
    for (const [label, count] of [['Ready', '1'], ['In progress', '2'], ['Failed', '1']]) {
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
