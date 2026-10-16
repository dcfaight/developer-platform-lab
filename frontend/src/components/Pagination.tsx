export function Pagination({ page, totalPages, onChange, label }: {
  page: number
  totalPages: number
  onChange: (page: number) => void
  label: string
}) {
  return <nav className="pagination" aria-label={label}>
    <button disabled={page === 0} onClick={() => onChange(page - 1)}>Previous</button>
    <span>Page {page + 1} of {Math.max(1, totalPages)}</span>
    <button disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>Next</button>
  </nav>
}
