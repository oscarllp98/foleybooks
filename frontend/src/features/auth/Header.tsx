import { Link } from 'react-router'
import { useAuth } from '../../hooks/useAuth'

// FE-09 (FR-05): the site header. It renders the ONE auth state the session
// store exposes (ADR-012) and carries the single verb every signed-in user
// needs from anywhere: Sign out. It lives in features/auth/ because, like
// LoginForm, it consumes useAuth — components/ stays purely presentational
// (AGENTS.md §3). The header navigates nothing imperatively (C1): links are
// declarative targets, and /login + /register are the user-approved paths
// FE-10's route table must mount.
//
// FR-05/LC-09 semantics live in AuthProvider.logout: the local session ends
// synchronously before the revoke POST settles, so clicking Sign out flips
// this header to its anonymous actions immediately, and a failed revoke
// still leaves a signed-out UI. Nothing is re-implemented here.

export function Header() {
  const { user, isAuthenticated, logout } = useAuth()

  return (
    <header className="border-b border-neutral-200 bg-white">
      <div className="mx-auto flex max-w-5xl items-center gap-6 px-4 py-3">
        <Link
          to="/"
          className="text-xl font-bold tracking-tight text-neutral-900"
        >
          Foley Books
        </Link>

        <nav aria-label="Primary" className="ml-auto flex items-center gap-4">
          {isAuthenticated ? (
            <>
              {/* ADR-011: after a reload the credential survives but the
                  identity is unknown until the next token pair — a generic
                  greeting must not be mistaken for a signed-out state. */}
              <span className="text-sm text-neutral-700">
                {user === null ? 'Signed in' : `Signed in as ${user.email}`}
              </span>
              <button
                type="button"
                onClick={() => {
                  void logout()
                }}
                className="rounded-md border border-neutral-300 px-3 py-1.5 text-sm font-medium text-neutral-700 hover:bg-neutral-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-700"
              >
                Sign out
              </button>
            </>
          ) : (
            <>
              <Link
                to="/login"
                className="text-sm font-medium text-neutral-700 hover:text-neutral-900"
              >
                Sign in
              </Link>
              <Link
                to="/register"
                className="rounded-md bg-neutral-900 px-3 py-1.5 text-sm font-medium text-white hover:bg-neutral-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-700"
              >
                Create account
              </Link>
            </>
          )}
        </nav>
      </div>
    </header>
  )
}
