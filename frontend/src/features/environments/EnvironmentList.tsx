import { environmentPage } from '../../api/client'
import { useResource } from '../../api/useResource'
import { Pagination } from '../../components/Pagination'
import { ResourceState } from '../../components/ResourceState'
import { Status, Timestamp } from '../../components/Status'

export function EnvironmentList({ page, onPageChange }: {
  page: number
  onPageChange: (page: number) => void
}) {
  const resource = useResource(`/environments?page=${page}&size=10`, environmentPage)
  const data = resource.data

  return <section className="panel" aria-labelledby="environments-heading">
    <div className="section-heading">
      <div><p className="eyebrow">Environment inventory</p><h2 id="environments-heading">Environments</h2></div>
      {data && <span className="count">{data.totalElements} total</span>}
    </div>
    <ResourceState {...resource} hasData={!!data} />
    {data && <>
      {data.items.length > 0 && <section className="inventory-summary" aria-label="Status summary for this page">
        <p className="muted">This page only · {data.items.length} {data.items.length === 1 ? 'environment' : 'environments'} · Last recorded readiness, not live health</p>
        <dl className="summary-grid">
          <div><dt>Ready</dt><dd>{data.items.filter((item) => item.status === 'READY').length}</dd></div>
          <div><dt>In progress</dt><dd>{data.items.filter((item) => ['REQUESTED', 'PROVISIONING'].includes(item.status)).length}</dd></div>
          <div><dt>Failed</dt><dd>{data.items.filter((item) => item.status === 'FAILED').length}</dd></div>
        </dl>
      </section>}
      {data.items.length === 0
        ? <div className="empty"><h3>{data.totalElements ? 'No environments on this page' : 'No environments yet'}</h3>
          {data.totalElements
            ? <p>Use Previous to return to earlier results.</p>
            : <><p>Use the <a href="#create">Create environment</a> form to request the approved local sample.</p>
              <p>Acceptance is not readiness. Enable the backend worker and local kind cluster to see provisioning progress.</p></>}</div>
        : <div className="table-scroll"><table>
          <caption className="sr-only">Environments and their latest operation status</caption>
          <thead><tr><th scope="col">Environment</th><th scope="col">Status</th><th scope="col">Template / namespace</th>
            <th scope="col">Created / expires</th><th scope="col">Latest operation</th></tr></thead>
          <tbody>{data.items.map((item) => <tr key={item.id}>
            <td><a className="environment-link" href={`#environment/${encodeURIComponent(item.id)}`}>{item.name}</a></td>
            <td><Status value={item.status} /></td>
            <td><span>{item.templateId}</span><code>{item.namespace}</code></td>
            <td><span>Created <Timestamp value={item.createdAt} /></span>
              <span>Expires <Timestamp value={item.expiresAt} /></span></td>
            <td>{item.latestOperation ? <>
              <span>{['PENDING', 'RUNNING'].includes(item.latestOperation.status) ? 'Active operation' : 'Latest operation'}</span>
              <Status value={item.latestOperation.status} />
              <small>{item.latestOperation.progressStep?.replaceAll('_', ' ') ?? 'Awaiting progress'}</small>
            </> : <span>No operations</span>}</td>
          </tr>)}</tbody>
        </table></div>}
      <Pagination page={page} totalPages={data.totalPages} onChange={onPageChange} label="Environment pages" />
    </>}
  </section>
}
