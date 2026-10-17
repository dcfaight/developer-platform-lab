import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvironmentDetail } from './EnvironmentDetail'
import { REFRESH_INTERVAL } from '../../api/useResource'
import type { Environment } from '../../api/client'
import { setSession } from '../../api/session'
import { demoEnvironment, demoOperation, json, operatorSession, paged } from '../../test/fixtures'

const ready: Environment = { ...demoEnvironment, status: 'READY',
  latestOperation: { ...demoEnvironment.latestOperation!, status: 'SUCCEEDED' } }
const acceptance = { environmentId: ready.id, operationId: demoOperation.id,
  environmentStatus: 'DELETING', operationStatus: 'PENDING' }
const originalShowModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal')
const originalClose = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')

beforeAll(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true,
    value(this: HTMLDialogElement) { this.setAttribute('open', ''); this.querySelector('input')?.focus() } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true,
    value(this: HTMLDialogElement) { this.removeAttribute('open') } })
})
afterAll(() => {
  if (originalShowModal) Object.defineProperty(HTMLDialogElement.prototype, 'showModal', originalShowModal)
  else Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal')
  if (originalClose) Object.defineProperty(HTMLDialogElement.prototype, 'close', originalClose)
  else Reflect.deleteProperty(HTMLDialogElement.prototype, 'close')
})
beforeEach(() => setSession(operatorSession))
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  setSession(null)
})

function portal(item = ready, remove: () => Promise<Response> = async () => json(acceptance, 202)) {
  let accepted = false
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'DELETE') {
      const response = await remove()
      if (response.status === 202) accepted = true
      return response
    }
    if (url.includes('/operations?')) {
      const page = url.includes('page=1') ? 1 : 0
      return json(paged([{ ...demoOperation, type: accepted ? 'DELETE_ENVIRONMENT' : 'CREATE_ENVIRONMENT',
        status: accepted ? 'PENDING' : 'SUCCEEDED', events: [] }],
      { page, totalPages: 2, totalElements: 6 }))
    }
    return json(accepted ? { ...item, status: 'DELETING',
      latestOperation: { ...item.latestOperation!, type: 'DELETE_ENVIRONMENT', status: 'PENDING' } } : item)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

async function openConfirmation() {
  await userEvent.click(await screen.findByRole('button', { name: 'Delete environment' }))
  return screen.getByRole('dialog', { name: 'Confirm environment deletion' })
}

async function confirm() {
  await openConfirmation()
  await userEvent.type(screen.getByLabelText('Type namespace/name to confirm'), `${ready.namespace}/${ready.name}`)
}

describe('safe environment deletion', () => {
  it.each(['READY', 'FAILED', 'DELETE_FAILED'])('offers deletion for %s with no active operation', async (status) => {
    portal({ ...ready, status, latestOperation: null })
    render(<EnvironmentDetail id={ready.id} />)
    expect((await screen.findByRole<HTMLButtonElement>('button', { name: 'Delete environment' })).disabled).toBe(false)
  })

  it.each([
    ['REQUESTED', 'SUCCEEDED'], ['PROVISIONING', 'SUCCEEDED'], ['DELETING', 'SUCCEEDED'],
    ['DELETED', 'SUCCEEDED'], ['UNKNOWN', 'SUCCEEDED'], ['READY', 'PENDING'],
    ['FAILED', 'RUNNING'], ['DELETE_FAILED', 'RUNNING'],
  ])('blocks deletion for environment %s / operation %s', async (status, operationStatus) => {
    const fetchMock = portal({ ...ready, status,
      latestOperation: { ...ready.latestOperation!, status: operationStatus } })
    render(<EnvironmentDetail id={ready.id} />)
    const button = await screen.findByRole<HTMLButtonElement>('button', { name: 'Delete environment' })
    expect(button.disabled).toBe(true)
    fireEvent.click(button)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false)
  })

  it('requires exact namespace/name, explains consequences, and cancels without deleting', async () => {
    const fetchMock = portal()
    render(<EnvironmentDetail id={ready.id} />)
    const dialog = await openConfirmation()
    expect(within(dialog).getByText(/cannot be undone/)).toBeTruthy()
    const input = screen.getByLabelText('Type namespace/name to confirm')
    expect(document.activeElement).toBe(input)
    const submit = within(dialog).getByRole<HTMLButtonElement>('button', { name: 'Confirm deletion' })
    expect(submit.disabled).toBe(true)
    await userEvent.type(input, ready.name)
    expect(submit.disabled).toBe(true)
    fireEvent.submit(input.closest('form')!)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Delete environment' }))
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false)
    await openConfirmation()
    expect(screen.getByLabelText('Type namespace/name to confirm')).toHaveProperty('value', '')
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('blocks double submission, then immediately refreshes detail and newest history', async () => {
    let resolve!: (response: Response) => void
    const fetchMock = portal(ready, () => new Promise<Response>((done) => { resolve = done }))
    render(<EnvironmentDetail id={ready.id} />)
    const history = await screen.findByRole('region', { name: 'Operation history' })
    await within(history).findByRole('article')
    await userEvent.click(within(history).getByRole('button', { name: 'Next' }))
    await within(history).findByText('Page 2 of 2')
    await confirm()
    const submit = screen.getByRole('button', { name: 'Confirm deletion' })
    fireEvent.click(submit)
    fireEvent.click(submit)
    fireEvent.submit(submit.closest('form')!)
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(1)
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Cancel' }).disabled).toBe(true)
    expect(screen.getByLabelText('Type namespace/name to confirm')).toHaveProperty('disabled', true)
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))
    expect(screen.getByRole('dialog')).toBeTruthy()
    const beforeAcceptance = fetchMock.mock.calls.length
    await act(async () => resolve(json(acceptance, 202)))
    expect(await screen.findByText(/Deletion accepted/)).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
    await screen.findByRole('heading', { name: 'DELETE ENVIRONMENT' })
    expect(screen.getByText('Page 1 of 2')).toBeTruthy()
    const refreshed = fetchMock.mock.calls.slice(beforeAcceptance).map(([url]) => url)
    expect(refreshed).toContain(`/api/v1/environments/${ready.id}`)
    expect(refreshed).toContain(`/api/v1/environments/${ready.id}/operations?page=0&size=5`)
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Delete environment' }).disabled).toBe(true)
    const deletion = fetchMock.mock.calls.find(([, init]) => init?.method === 'DELETE')!
    expect(deletion[1]?.body).toBeUndefined()
    expect(deletion[1]?.headers).toEqual({ Accept: 'application/json', Authorization: operatorSession.authorization })
  })

  it('keeps polling after accepted refresh and aborts all timers on unmount', async () => {
    vi.useFakeTimers()
    const fetchMock = portal()
    const view = render(<EnvironmentDetail id={ready.id} />)
    await act(async () => {})
    fireEvent.click(screen.getByRole('button', { name: 'Delete environment' }))
    fireEvent.change(screen.getByLabelText('Type namespace/name to confirm'),
      { target: { value: `${ready.namespace}/${ready.name}` } })
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Confirm deletion' })))
    expect(screen.getByText(/Deletion accepted/)).toBeTruthy()
    const beforePolling = fetchMock.mock.calls.length
    await act(async () => { await vi.advanceTimersByTimeAsync(REFRESH_INTERVAL) })
    expect(fetchMock.mock.calls.length).toBe(beforePolling + 2)
    view.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it.each([409, 404, 500])('shows sanitized HTTP %s errors and request IDs', async (status) => {
    const code = status === 409 ? 'ENVIRONMENT_STATE_CONFLICT' : status === 404 ? 'NOT_FOUND' : 'INTERNAL_ERROR'
    portal(ready, async () => json({
      message: 'private diagnostic', code, requestId: 'delete-request-123',
    }, status))
    render(<EnvironmentDetail id={ready.id} />)
    await confirm()
    await userEvent.click(screen.getByRole('button', { name: 'Confirm deletion' }))
    const error = await screen.findByRole('alert')
    expect(error.textContent).toContain('delete-request-123')
    expect(error.textContent).toContain(`Error code: ${code}`)
    expect(error.textContent).not.toContain('private diagnostic')
    expect(error.textContent).toContain(status >= 500 ? 'could not confirm deletion' : `HTTP ${status}`)
    expect(screen.getByLabelText('Type namespace/name to confirm')).toHaveProperty('value', `${ready.namespace}/${ready.name}`)
  })

  it('allows safe explicit same-environment retry after network uncertainty', async () => {
    let calls = 0
    const fetchMock = portal(ready, async () => {
      if (++calls === 1) throw new TypeError('private network diagnostic')
      return json(acceptance, 202)
    })
    render(<EnvironmentDetail id={ready.id} />)
    await confirm()
    await userEvent.click(screen.getByRole('button', { name: 'Confirm deletion' }))
    expect((await screen.findByRole('alert')).textContent).toContain('may have been accepted')
    expect(screen.getByRole('alert').textContent).not.toContain('private network diagnostic')
    const retry = await screen.findByRole<HTMLButtonElement>('button', { name: 'Retry deletion' })
    await waitFor(() => expect(retry.disabled).toBe(false))
    await userEvent.click(retry)
    await screen.findByText(/Deletion accepted/)
    const requests = fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE')
    expect(requests).toHaveLength(2)
    expect(requests[0][0]).toBe(requests[1][0])
    expect(requests[0][1]?.headers).toEqual(requests[1][1]?.headers)
  })

  it.each(['unmount', 'id change'])('aborts pending deletion on %s and ignores late acceptance', async (navigation) => {
    let resolve!: (response: Response) => void
    const fetchMock = portal(ready, () => new Promise<Response>((done) => { resolve = done }))
    const view = render(<EnvironmentDetail id={ready.id} />)
    await confirm()
    fireEvent.click(screen.getByRole('button', { name: 'Confirm deletion' }))
    const signal = fetchMock.mock.calls.find(([, init]) => init?.method === 'DELETE')![1]?.signal
    if (navigation === 'unmount') view.unmount()
    else view.rerender(<EnvironmentDetail id="11111111-1111-4111-8111-111111111111" />)
    expect(signal?.aborted).toBe(true)
    await act(async () => resolve(json(acceptance, 202)))
    expect(screen.queryByText(/Deletion accepted/)).toBeNull()
  })
})

describe('deletion authorization', () => {
  it('disables deletion for a read-only cleanup account', async () => {
    setSession({ ...operatorSession, identity: { username: 'cleanup-bot', roles: ['CLEANUP'], teams: [] } })
    const fetchMock = portal()
    render(<EnvironmentDetail id={ready.id} />)
    const button = await screen.findByRole('button', { name: 'Delete environment' })
    expect(button).toHaveProperty('disabled', true)
    expect(screen.getByText(/Requires a team DEVELOPER or OPERATOR sign-in/)).toBeTruthy()
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false)
    expect(screen.queryByRole('heading', { name: 'Operation history' })).toBeNull()
    expect(screen.getByText(/Operation history is unavailable for this account/)).toBeTruthy()
    expect(fetchMock.mock.calls.some(([url]) => url.includes('/operations'))).toBe(false)
  })

  it.each([
    [401, /Sign in again/],
    [403, /not permitted to delete this environment/],
  ])('explains HTTP %s rejection with its request ID', async (status, message) => {
    portal(ready, async () => json({ code: status === 401 ? 'UNAUTHENTICATED' : 'FORBIDDEN',
      message: '<b>untrusted</b>', requestId: `request-${status}` }, status))
    render(<EnvironmentDetail id={ready.id} />)
    await confirm()
    await userEvent.click(screen.getByRole('button', { name: 'Confirm deletion' }))
    if (status === 401) {
      await waitFor(() => expect(screen.queryByRole('heading', { name: ready.name })).toBeNull())
      expect(screen.queryByRole('dialog')).toBeNull()
      expect(screen.queryByRole('alert')).toBeNull()
      return
    }
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toMatch(message)
    expect(alert.textContent).toContain(`request-${status}`)
    expect(alert.textContent).not.toContain('untrusted')
  })

  it.each([
    ['team-one', false],
    ['team-two', false],
    ['team-other', true],
    [null, true],
  ])('allows developers to delete only member-owned environments: %s', async (teamId, disabled) => {
    setSession({ ...operatorSession,
      identity: { username: 'developer', roles: ['DEVELOPER'], teams: ['team-one', 'team-two'] } })
    portal({ ...ready, teamId })
    render(<EnvironmentDetail id={ready.id} />)
    expect(await screen.findByRole('button', { name: 'Delete environment' })).toHaveProperty('disabled', disabled)
  })
})
