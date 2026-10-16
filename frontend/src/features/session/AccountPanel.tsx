import { useState, type FormEvent } from 'react'
import { ApiError, signIn } from '../../api/client'
import { clearSession, useSession } from '../../api/session'

export function AccountPanel() {
  const session = useSession()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError>()

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setError(undefined)
    try {
      await signIn(username.trim(), password)
      setUsername('')
    } catch (failure) {
      setError(failure instanceof ApiError ? failure : new ApiError('Sign-in could not be completed.'))
    } finally {
      setPassword('')
      setBusy(false)
    }
  }

  if (session) {
    return <section className="panel account-panel" aria-label="Account">
      <p>Signed in as <strong>{session.identity.username}</strong>
        {' '}({session.identity.roles.join(', ') || 'no roles'}).
        {' '}Credentials are kept only in this tab's memory; reloading signs you out.</p>
      <button type="button" onClick={clearSession}>Sign out</button>
    </section>
  }

  return <section className="panel account-panel" aria-label="Account">
    <p>Browsing is open on this trusted local portal. Creating and deleting environments requires an OPERATOR sign-in.</p>
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
    {error && <p role="alert" className="submission-message">{error.message}
      {error.requestId && <> Request ID: <code>{error.requestId}</code></>}</p>}
  </section>
}
