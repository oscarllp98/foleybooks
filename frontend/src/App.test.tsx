import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import App from './App'
import { AuthProvider } from './features/auth/AuthProvider'
import { setSessionExpiredHandler } from './lib/http'
import { clearTokens, setTokens } from './lib/tokens'

// FE-10 turned App into the routed shell (BrowserRouter > AppRoutes under
// main.tsx's AuthProvider). These smoke the exact production composition —
// jsdom boots at "/", which is BrowserRouter's entry — while the route
// semantics themselves live in routes/AppRoutes.test.tsx.

function renderApp(): void {
  render(
    <AuthProvider>
      <App />
    </AuthProvider>,
  )
}

beforeEach(() => {
  setTokens({ accessToken: 'reset', refreshToken: 'reset' })
  clearTokens()
  setSessionExpiredHandler(null)
  localStorage.clear()
  window.history.replaceState(null, '', '/')
})

describe('App', () => {
  it('app_whenBooted_rendersHeaderAndHomeHero', () => {
    renderApp()

    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: /foley books/i }),
    ).toBeInTheDocument()
    expect(
      screen.getByText('Your next favourite story is a search away.'),
    ).toBeInTheDocument()
  })

  it('app_whenSignInLinkClicked_navigatesClientSideToLoginForm', async () => {
    const user = userEvent.setup()
    renderApp()

    await user.click(screen.getByRole('link', { name: 'Sign in' }))

    expect(screen.getByRole('form', { name: 'Sign in' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/login')
  })
})
