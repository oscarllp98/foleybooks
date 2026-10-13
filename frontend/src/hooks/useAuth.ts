import { useContext } from 'react'
import {
  AuthContext,
  type AuthContextValue,
} from '../features/auth/AuthContext'

/**
 * FE-04: the app-facing door to the session store. Throws outside
 * AuthProvider — a missing provider is a wiring bug, not a runtime state.
 */
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (context === null) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}
