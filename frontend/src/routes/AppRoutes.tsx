import { useEffect, useRef } from 'react'
import {
  Outlet,
  Route,
  Routes,
  useLocation,
  useNavigate,
  type Location,
} from 'react-router'
import { Header } from '../features/auth/Header'
import { VerifyEmail } from '../features/auth/VerifyEmail'
import { CartPage } from '../features/cart/CartPage'
import { BookDetail } from '../features/catalog/BookDetail'
import { setSessionExpiredHandler } from '../lib/http'
import { HomePage } from './HomePage'
import { LoginPage } from './LoginPage'
import { RegisterPage } from './RegisterPage'
import { RequireAuth } from './RequireAuth'
import type { LoginRedirectState } from '../types/routing'

// FE-10: the React Router v7 route table. The paths are the ones the
// committed code already promises: "/" (home), "/login", "/register"
// (user-approved, FE-09) and "/verify-email?token=…" (plan D-01), plus the
// guarded "/cart". FE-11 mounted the browse page at "/"; FE-13 mounts the
// FR-07 detail slot at "/books/:id" — the exact path the list cards
// navigate to. FE-14/FE-15 fill the remaining slots.

/**
 * App shell: header + routed main. Also the router mount point ADR-011
 * assigns for the session-expired handler: the layout lives inside
 * AuthProvider, so its effect (children first) installs the navigation
 * handler BEFORE AuthProvider wraps it — expiry then marks the store, shows
 * LoginPage's notice, and navigates with a return path, in one chain.
 * The live pathname is read from a ref (updated in an effect, never during
 * render) so the mount-only handler never captures a stale location.
 */
function Layout() {
  const navigate = useNavigate()
  const location = useLocation()
  const pathRef = useRef<Location>(location)

  useEffect(() => {
    pathRef.current = location
  }, [location])

  useEffect(() => {
    const onSessionExpired = () => {
      const { pathname } = pathRef.current
      const state: LoginRedirectState | null =
        pathname === '/login' ? null : { from: pathname }
      navigate(
        { pathname: '/login', search: 'reason=session-expired' },
        { replace: true, state },
      )
    }
    const previous = setSessionExpiredHandler(onSessionExpired)
    return () => {
      setSessionExpiredHandler(previous)
    }
  }, [navigate])

  return (
    <>
      <Header />
      <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-8">
        <Outlet />
      </main>
    </>
  )
}

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<HomePage />} />
        <Route path="books/:id" element={<BookDetail />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="verify-email" element={<VerifyEmail />} />
        <Route
          path="cart"
          element={
            <RequireAuth>
              <CartPage />
            </RequireAuth>
          }
        />
      </Route>
    </Routes>
  )
}
