import { useEffect, useState } from 'react'
import { EnvironmentDetail } from './features/environments/EnvironmentDetail'
import { CreateEnvironment } from './features/environments/CreateEnvironment'
import { EnvironmentList } from './features/environments/EnvironmentList'
import { AccountPanel } from './features/session/AccountPanel'
import { useSession, useSessionVersion } from './api/session'
import './App.css'

function App() {
  const [hash, setHash] = useState(window.location.hash)
  const session = useSession()
  const version = useSessionVersion()
  useEffect(() => {
    const navigate = () => setHash(window.location.hash)
    window.addEventListener('hashchange', navigate)
    return () => window.removeEventListener('hashchange', navigate)
  }, [])
  const match = /^#environment\/([a-f0-9-]{36})$/i.exec(hash)
  const creating = hash === '#create'
  return <div className="app-shell">
    <a className="skip-link" href="#main-content" onClick={(event) => {
      event.preventDefault()
      document.getElementById('main-content')?.focus()
    }}>Skip to content</a>
    <header className="site-header"><a href="#environments" className="brand">IDP <span>Internal Developer Platform</span></a>
      <nav aria-label="Primary navigation"><a className="create-link" href="#create">Create environment</a>
        <span className="read-only">Trusted local portal</span></nav></header>
    <main id="main-content" tabIndex={-1}>
      <div className="intro"><p className="eyebrow">Internal Developer Platform</p>
        <h1>Environment control room</h1>
        <p>Follow environment readiness, operation progress, and failures in one place.</p>
        <p className="muted">Status refreshes every 10 seconds. All times are shown in your local timezone.</p>
      </div>
      <AccountPanel />
      {creating ? <CreateEnvironment />
        : !session ? <p className="notice">Sign in above to view environments and operation history.</p>
          : match ? <EnvironmentDetail key={`${version}-${match[1]}`} id={match[1]} />
            : <Inventory key={version} />}
    </main>
    <footer>Trusted local use only · Team developer or operator sign-in required for changes · Not production hardened</footer>
  </div>
}

function Inventory() {
  const [page, setPage] = useState(0)
  return <EnvironmentList page={page} onPageChange={setPage} />
}

export default App
