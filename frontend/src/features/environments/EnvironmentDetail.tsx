import { useState } from 'react'
import { environment, operationPage } from '../../api/client'
import type { Operation } from '../../api/client'
import { useResource } from '../../api/useResource'
import { Pagination } from '../../components/Pagination'
import { ResourceState } from '../../components/ResourceState'
import { Status, Timestamp } from '../../components/Status'
import { DeleteEnvironment } from './DeleteEnvironment'

function OperationCard({ item }: { item: Operation }) {
  return <article className="operation" aria-label={`Operation ${item.id}`}>
    <div className="section-heading">
      <div><h3>{item.type.replaceAll('_', ' ')}</h3><code>{item.id}</code></div>
      <Status value={item.status} />
    </div>
    <p className="progress-step">Current step: <strong>{item.progressStep?.replaceAll('_', ' ') ?? 'Awaiting progress'}</strong>
      {' · '}{item.attemptCount} {item.attemptCount === 1 ? 'attempt' : 'attempts'}</p>
    {(item.failureCode || item.failureMessage) && <div className="notice error">
      <strong>{item.status === 'FAILED' ? 'Failure summary' : 'Last failure (retry may be pending)'}</strong>
      <code>{item.failureCode}</code><p>{item.failureMessage}</p>
    </div>}
    <dl className="metadata">
      <div><dt>Created</dt><dd><Timestamp value={item.createdAt} /></dd></div>
      <div><dt>Started</dt><dd><Timestamp value={item.startedAt} /></dd></div>
      <div><dt>Updated</dt><dd><Timestamp value={item.updatedAt} /></dd></div>
      <div><dt>Completed</dt><dd><Timestamp value={item.completedAt} /></dd></div>
      {['PENDING', 'RUNNING'].includes(item.status) && <>
        <div><dt>Next attempt</dt><dd><Timestamp value={item.nextAttemptAt} /></dd></div>
        <div><dt>Lease expires</dt><dd><Timestamp value={item.leaseExpiresAt} /></dd></div>
      </>}
      <div className="wide"><dt>Request correlation</dt><dd><code>{item.correlationId ?? 'Not available'}</code></dd></div>
    </dl>
    <h4>Progress history</h4>
    {item.events.length > 0 && <p className="muted">{item.events.length} recorded {item.events.length === 1 ? 'event' : 'events'} · Oldest first</p>}
    {item.events.length === 0 ? <p className="muted">No progress events yet. The operation may be waiting for a worker.</p>
      : <ol className="timeline">{item.events.map((event, index) => <li key={`${event.createdAt}-${index}`}>
        <div><strong>{event.type.replaceAll('_', ' ')}</strong><Timestamp value={event.createdAt} /></div>
        <p>{event.message}</p><code>{event.code}</code>
        {event.correlationId && <small>Request: {event.correlationId}</small>}
      </li>)}</ol>}
  </article>
}

function OperationHistory({ id }: { id: string }) {
  const [page, setPage] = useState(0)
  const resource = useResource(`/environments/${encodeURIComponent(id)}/operations?page=${page}&size=5`, operationPage)
  return <section className="panel" aria-labelledby="operations-heading">
    <div className="section-heading"><div><p className="eyebrow">Operation observability</p>
      <h2 id="operations-heading">Operation history</h2></div>
      {resource.data && <span className="count">{resource.data.totalElements} total</span>}
    </div>
    <p className="muted">Newest operation first. Progress reflects recorded worker steps, not a percentage estimate.</p>
    <ResourceState {...resource} hasData={!!resource.data} />
    {resource.data && <>
      {resource.data.items.length === 0 ? <p className="empty">{resource.data.totalElements
        ? 'No operations on this page.' : 'No operations recorded for this environment yet.'}</p>
        : resource.data.items.map((item) => <OperationCard key={item.id} item={item} />)}
      <Pagination page={page} totalPages={resource.data.totalPages} onChange={setPage} label="Operation pages" />
    </>}
  </section>
}

export function EnvironmentDetail({ id }: { id: string }) {
  const [historyRevision, setHistoryRevision] = useState(0)
  const resource = useResource(`/environments/${encodeURIComponent(id)}`, environment)
  const item = resource.data
  return <>
    <a className="back-link" href="#environments">← All environments</a>
    <section className="panel" aria-labelledby="detail-heading">
      <div className="section-heading">
        <div><p className="eyebrow">Environment detail</p><h2 id="detail-heading">{item?.name ?? 'Environment'}</h2></div>
        {item && <Status value={item.status} />}
      </div>
      <ResourceState {...resource} hasData={!!item} />
      {item && <>
        <dl className="metadata">
          <div className="wide"><dt>Environment ID</dt><dd><code>{item.id}</code></dd></div>
          <div><dt>Template</dt><dd>{item.templateId}</dd></div>
          <div><dt>Namespace</dt><dd><code>{item.namespace}</code></dd></div>
          <div><dt>Created</dt><dd><Timestamp value={item.createdAt} /></dd></div>
          <div><dt>Updated</dt><dd><Timestamp value={item.updatedAt} /></dd></div>
          <div><dt>Expires</dt><dd><Timestamp value={item.expiresAt} /></dd></div>
          <div><dt>Current / latest operation</dt><dd>{item.latestOperation
            ? <><Status value={item.latestOperation.status} /><code>{item.latestOperation.id}</code></>
            : 'No operations'}</dd></div>
        </dl>
        <p className="muted">Expiry is metadata only; automatic cleanup is not implemented.</p>
        <DeleteEnvironment key={id} item={item} unavailable={resource.busy || !!resource.error}
          onRefresh={() => {
            resource.refresh()
            setHistoryRevision((value) => value + 1)
          }} />
      </>}
    </section>
    {item && <OperationHistory key={`${id}-${historyRevision}`} id={id} />}
  </>
}
