import { useState, type FormEvent } from 'react'
import { ApiError, signIn } from '../../api/client'
import { clearSession, useSession, useSessionVersion } from '../../api/session'

export function AccountPanel() {
  const session = useSession()
  const version = useSessionVersion()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<{ version: number, failure: ApiError }>()

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setError(undefined)
    try {
      await signIn(username.trim(), password)
      setUsername('')
    } catch (failure) {
      if (failure instanceof ApiError && failure.code !== 'SESSION_CHANGED') {
        setError({ version: version + 1, failure })
      }
    } finally {
      setPassword('')
      setBusy(false)
    }
  }

  if (session) {
    return <section className="panel account-panel" aria-label="Account">
      <p>Signed in as <strong>{session.identity.username}</strong>
        {' '}({session.identity.roles.join(', ') || 'no roles'}).
        {' '}Teams: {session.identity.teams.join(', ') || 'none'}.
        {' '}Credentials are kept only in this tab's memory; reloading signs you out.</p>
      <button type="button" onClick={clearSession}>Sign out</button>
    </section>
  }

  return <section className="panel account-panel" aria-label="Account">
    <p>Sign in to browse this trusted local portal. DEVELOPER accounts manage their teams' environments;
      OPERATOR accounts manage all environments. CLEANUP accounts have read-only portal access.</p>
    <form onSubmit={(event) => { void submit(event) }} aria-busy={busy}>
      <div className="form-field">
        <label htmlFor="account-username">Username</label>
        <input id="account-username" autoComplete="username" value={username} disabled={busy}
          onChange={(event) => setUsername(event.target.value)} />
      </div>
      <div className="form-field">
        <label htmlFor="account-password">Password</label>
        <input id="account-password" type="password" autoComplete="current-password" value={password}
          disabled={busy} onChange={(event) => setPassword(event.target.value)} />
      </div>
      <button type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
    </form>
    {error?.version === version && <p role="alert" className="submission-message">{error.failure.message}
      {error.failure.requestId && <> Request ID: <code>{error.failure.requestId}</code></>}</p>}
  </section>
}
