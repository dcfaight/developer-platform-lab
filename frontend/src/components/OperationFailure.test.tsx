import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { OperationFailure } from './OperationFailure'
import { demoOperation } from '../test/fixtures'

it.each([
  ['KUBERNETES_AUTHENTICATION_FAILED', /service account token or kubeconfig credentials/],
  ['KUBERNETES_FORBIDDEN', /Role\/ClusterRole and bindings/],
  ['KUBERNETES_REQUEST_REJECTED', /admission policies, resource quotas/],
  ['KUBERNETES_UNAVAILABLE', /API connectivity and cluster health/],
  ['READINESS_TIMEOUT', /Deployment and Pod status/],
  ['DELETION_TIMEOUT', /namespace termination/],
  ['INTERNAL_ERROR', /worker logs using the operation ID/],
  ['FUTURE_ERROR', /worker logs using the operation ID/],
  ['constructor', /worker logs using the operation ID/],
])('shows a failure reason and remediation for %s', (failureCode, hint) => {
  render(<OperationFailure item={{ ...demoOperation, status: 'FAILED', failureCode,
    failureMessage: 'A safe failure reason.' }} />)
  expect(screen.getByText('Failure summary')).toBeTruthy()
  expect(screen.getByText(failureCode)).toBeTruthy()
  expect(screen.getByText('A safe failure reason.')).toBeTruthy()
  expect(screen.getByText(hint)).toBeTruthy()
})

it('leaves successful operations without failure details unchanged', () => {
  const { container } = render(<OperationFailure item={{ ...demoOperation, status: 'SUCCEEDED' }} />)
  expect(container.textContent).toBe('')
})
