import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { Header } from './Header'
import { AuthProvider } from './AuthProvider'
import { setSessionExpiredHandler } from '../../lib/http'
import { clearTokens, setTokens } from '../../lib/tokens'
import { installRecordingAdapter } from '../../test/recordingAdapter'

// FE-09 tests (plan §6.5 "header logout test", FR-05): the header is driven
// through the real AuthProvider over the ONE session store (ADR-012), so the
// asserted behavior is exactly what the user sees — which actions are on
// offer in each auth state, and what Sign out leaves behind. The store is
// seeded directly instead of logging in: the login loop is LoginForm's and
// AuthProvider's contract; here the seam under test is state → rendering and
// click → revoke.

const USER = {
  id: 'u-1',
  email: 'reader@example.com',
  role: 'CUSTOMER',
} as const

function renderHeader(): void {
  render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/']}>
        <Header />
      </MemoryRouter>
    </AuthProvider>,
  )
}

function signInWithIdentity(): void {
  setTokens({
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    user: USER,
  })
}

beforeEach(() => {
  // Same store normalization as the AuthProvider/LoginForm suites:
  // setTokens-then-clearTokens also resets the LC-07 flag that clearTokens
  // alone deliberately preserves.
  setTokens({ accessToken: 'reset', refreshToken: 'reset' })
  clearTokens()
  setSessionExpiredHandler(null)
  localStorage.clear()
})

describe('Header', () => {
  it('header_whenAnonymous_offersSignInAndCreateAccountAndNoSignOut', () => {
    renderHeader()

    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Foley Books' })).toHaveAttribute(
      'href',
      '/',
    )
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute(
      'href',
      '/login',
    )
    expect(
      screen.getByRole('link', { name: 'Create account' }),
    ).toHaveAttribute('href', '/register')
    expect(screen.queryByRole('button', { name: 'Sign out' })).toBeNull()
  })

  it('header_whenSignedIn_greetsByEmailAndOffersSignOutWithoutSignInLinks', () => {
    signInWithIdentity()
    renderHeader()

    expect(
      screen.getByText('Signed in as reader@example.com'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Sign in' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Create account' })).toBeNull()
  })

  it('header_whenCredentialSurvivedReloadWithoutIdentity_greetsGenericallyAndKeepsSignOut', () => {
    // ADR-011 reload state: the session is alive (FR-04, user story 4) but
    // no identity is known yet — the header must read as signed-in anyway.
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    renderHeader()

    expect(screen.getByText('Signed in')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Sign in' })).toBeNull()
  })

  it('signOut_whenClicked_revokesCurrentCredentialAndFlipsHeaderToAnonymousActions', async () => {
    // FR-05: the click ends the local session AND posts the current
    // refresh credential to /auth/logout (server-side revoke, D-05).
    const requests = installRecordingAdapter(() => [204, undefined])
    signInWithIdentity()
    const user = userEvent.setup()
    renderHeader()

    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(
      await screen.findByRole('link', { name: 'Sign in' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sign out' })).toBeNull()
    expect(screen.queryByText('Signed in as reader@example.com')).toBeNull()
    expect(requests).toEqual([
      {
        method: 'POST',
        url: '/auth/logout',
        params: undefined,
        body: { refreshToken: 'refresh-1' },
      },
    ])
  })

  it('signOut_whenRevokeRequestFails_stillShowsSignedOutHeader', async () => {
    // FR-05: the store ends the local session synchronously before the
    // revoke settles, so a failed revoke cannot leave a half-logged-in
    // client — the header flips to its signed-out actions regardless.
    installRecordingAdapter(() => {
      throw new Error('unreachable')
    })
    signInWithIdentity()
    const user = userEvent.setup()
    renderHeader()

    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(
      await screen.findByRole('link', { name: 'Create account' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sign out' })).toBeNull()
  })
})
