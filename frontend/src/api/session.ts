import { useSyncExternalStore } from 'react'

export interface Identity {
  username: string
  roles: string[]
  teams: string[]
}

export interface Session {
  identity: Identity
  authorization: string
}

// Credentials live only in this page's memory: never in localStorage, sessionStorage, or cookies.
let current: Session | null = null
let version = 0
const listeners = new Set<() => void>()

function emit() {
  for (const listener of listeners) listener()
}

export function getSession(): Session | null {
  return current
}

export function setSession(session: Session | null): void {
  version++
  current = session
  emit()
}

export function clearSession(): void {
  setSession(null)
}

export const getSessionVersion = () => version

export function useSessionVersion(): number {
  return useSyncExternalStore(subscribe, getSessionVersion, getSessionVersion)
}

export function isOperator(session: Session | null): boolean {
  return !!session?.identity.roles.includes('OPERATOR')
}

export function canCreate(session: Session | null): boolean {
  return isOperator(session) || !!session?.identity.roles.includes('DEVELOPER')
}

export function canManage(session: Session | null, teamId: string | null): boolean {
  return isOperator(session) || (!!teamId && !!session?.identity.roles.includes('DEVELOPER')
    && session.identity.teams.includes(teamId))
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
