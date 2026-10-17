import type { Environment, Operation, Page } from '../api/client'

export const demoEnvironment: Environment = {
  id: '99f42e18-bab9-40ad-83d1-85bc3f6378c0',
  name: 'Demo environment',
  namespace: 'env-99f42e18bab940ad83d185bc3f6378c0',
  templateId: 'sample-service',
  teamId: null,
  createdBy: null,
  status: 'PROVISIONING',
  createdAt: '2026-10-16T05:00:00Z',
  updatedAt: '2026-10-16T05:01:00Z',
  expiresAt: '2026-10-16T13:00:00Z',
  latestOperation: {
    id: '27f56e9b-382a-4373-a26c-c4dab824d127',
    type: 'CREATE_ENVIRONMENT',
    status: 'RUNNING',
    progressStep: 'WAITING',
    failureCode: null,
    failureMessage: null,
  },
}

export const demoOperation: Operation = {
  ...demoEnvironment.latestOperation!,
  environmentId: demoEnvironment.id,
  attemptCount: 1,
  nextAttemptAt: demoEnvironment.createdAt,
  leaseExpiresAt: null,
  startedAt: demoEnvironment.createdAt,
  completedAt: null,
  createdAt: demoEnvironment.createdAt,
  updatedAt: demoEnvironment.updatedAt,
  failureCode: null,
  failureMessage: null,
  correlationId: '94257154-484e-4c67-aed2-8958854c7e6a',
  events: [{
    type: 'PROVISIONED',
    code: 'WAITING',
    message: 'Resources were provisioned; waiting for readiness.',
    correlationId: '94257154-484e-4c67-aed2-8958854c7e6a',
    createdAt: demoEnvironment.updatedAt,
  }],
}

export function paged<T>(items: T[], overrides: Partial<Page<T>> = {}): Page<T> {
  return { items, page: 0, size: 10, totalElements: items.length,
    totalPages: items.length ? 1 : 0, ...overrides }
}

export function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
}

export const operatorSession = {
  identity: { username: 'alice', roles: ['OPERATOR'], teams: [] },
  authorization: 'Basic YWxpY2U6dGVzdC1vbmx5LWFsaWNlLXBhc3M=',
}
