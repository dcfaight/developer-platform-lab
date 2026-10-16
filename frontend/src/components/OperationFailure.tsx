import type { OperationSummary } from '../api/client'

const hints: Record<string, string> = {
  KUBERNETES_AUTHENTICATION_FAILED: 'Check the worker service account token or kubeconfig credentials and whether they have expired.',
  KUBERNETES_FORBIDDEN: 'Check the worker service account Role/ClusterRole and bindings for the required namespace and Deployment permissions.',
  KUBERNETES_REQUEST_REJECTED: 'Check Kubernetes admission policies, resource quotas, and the requested resource specification.',
  KUBERNETES_UNAVAILABLE: 'Check Kubernetes API connectivity and cluster health. The worker may retry transient failures.',
  READINESS_TIMEOUT: 'Check Deployment and Pod status, image pulls, readiness probes, scheduling, and namespace events.',
  DELETION_TIMEOUT: 'Check namespace termination, remaining resources, and finalizers before retrying deletion.',
  KUBERNETES_OWNERSHIP_CONFLICT: 'Check the resource ownership labels and annotations. Do not delete or adopt resources owned by another environment.',
  KUBERNETES_VALIDATION_FAILED: 'Check the persisted environment namespace and Kubernetes resource identity.',
  ATTEMPTS_EXHAUSTED: 'Check earlier progress events and worker logs for the last failure before taking further action.',
}

export function OperationFailure({ item }: { item: OperationSummary }) {
  if (!item.failureCode && !item.failureMessage) return null
  return <div className="notice error">
    <strong>{item.status === 'FAILED' ? 'Failure summary' : 'Last failure (retry may be pending)'}</strong>
    <code>{item.failureCode}</code><p>{item.failureMessage}</p>
    <p><strong>What to check: </strong>{item.failureCode && Object.hasOwn(hints, item.failureCode)
      ? hints[item.failureCode]
      : 'Check worker logs using the operation ID and request correlation ID. Do not share credentials or tokens.'}</p>
  </div>
}
