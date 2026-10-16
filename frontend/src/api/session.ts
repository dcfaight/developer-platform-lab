import { useSyncExternalStore } from 'react'

export interface Identity {
  username: string
  roles: string[]
}

export interface Session {
  identity: Identity
  authorization: string
}

// Credentials live only in this page's memory: never in localStorage, sessionStorage, or cookies.
let current: Session | null = null
const listeners = new Set<() => void>()

function emit() {
  for (const listener of listeners) listener()
}

export function getSession(): Session | null {
  return current
}

export function setSession(session: Session | null): void {
  current = session
  emit()
}

export function clearSession(): void {
  if (current) setSession(null)
}

export function isOperator(session: Session | null): boolean {
  return !!session?.identity.roles.includes('OPERATOR')
}

export function authorizationHeader(): Record<string, string> {
  return current ? { Authorization: current.authorization } : {}
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

export function useSession(): Session | null {
  return useSyncExternalStore(subscribe, getSession, getSession)
}
