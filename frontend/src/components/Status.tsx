export function Status({ value }: { value: string }) {
  const tone = ['READY', 'SUCCEEDED'].includes(value) ? 'success'
    : value === 'FAILED' ? 'danger'
    : ['RUNNING', 'PROVISIONING'].includes(value) ? 'active' : 'neutral'
  return <span className={`status status-${tone}`}>{value.replaceAll('_', ' ')}</span>
}

export function Timestamp({ value }: { value: string | null }) {
  if (!value) return <span>—</span>
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return <span>Unknown timestamp</span>
  return <time dateTime={value} title={value}>{date.toLocaleString()}</time>
}
