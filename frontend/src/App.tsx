import { useEffect, useState } from 'react'
import { EnvironmentDetail } from './features/environments/EnvironmentDetail'
import { EnvironmentList } from './features/environments/EnvironmentList'
import './App.css'

function App() {
  const [hash, setHash] = useState(window.location.hash)
  const [page, setPage] = useState(0)
  useEffect(() => {
    const navigate = () => setHash(window.location.hash)
    window.addEventListener('hashchange', navigate)
    return () => window.removeEventListener('hashchange', navigate)
  }, [])
  const match = /^#environment\/([a-f0-9-]{36})$/i.exec(hash)
  return <div className="app-shell">
    <header className="site-header"><a href="#environments" className="brand">DPL <span>Developer Platform Lab</span></a>
      <span className="read-only">Read-only portal</span></header>
    <main>
      <div className="intro"><p className="eyebrow">Local development platform</p>
        <h1>Environment control room</h1>
        <p>Follow environment readiness, provisioning progress, and failures in one place.</p>
        <p className="muted">Status refreshes every 10 seconds. All times are shown in your local timezone.</p>
      </div>
      {match ? <EnvironmentDetail key={match[1]} id={match[1]} />
        : <EnvironmentList page={page} onPageChange={setPage} />}
    </main>
    <footer>Work-in-progress portfolio blueprint · Trusted local use only · No create, delete, or authentication controls</footer>
  </div>
}

export default App
