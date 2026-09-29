import {
  AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { AppRoutes } from './AppRoutes'
import { AuthProvider } from '../features/auth/AuthProvider'
import { http, setSessionExpiredHandler } from '../lib/http'
import { clearTokens, markSessionExpired, setTokens } from '../lib/tokens'

// FE-10 tests (LC-27, LC-07): the route table rendered over the REAL
// AuthProvider and the shared axios instance, exactly as main.tsx mounts
// them (AuthProvider > router > Layout). That is the only way to pin the
// two seams this task owns: guard → login redirect → replayed return path,
// and the store's session-expired flag / interceptor navigation landing on
// LoginPage's notice.

interface SentRequest {
  url: string
}

type Responder = (url: string) => [number, unknown]

const TOKEN_PAIR = {
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: { id: 'u-1', email: 'reader@example.com', role: 'CUSTOMER' },
} as const

const EXPIRED_NOTICE = 'Your session has expired'

function installStub(responder: Responder): SentRequest[] {
  const sent: SentRequest[] = []
  http.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
    const url = config.url ?? ''
    sent.push({ url })
    const [status, data] = responder(url)
    const response: AxiosResponse = {
      status,
      statusText: '',
      headers: {},
      config,
      data,
    }
    if (status >= 400) {
      throw new AxiosError(
        `Request failed with status code ${status}`,
        AxiosError.ERR_BAD_REQUEST,
        config,
        {},
        response,
      )
    }
    return response
  }
  return sent
}

function renderRoute(path: string): void {
  render(
    <AuthProvider>
      <MemoryRouter initialEntries={[path]}>
        <AppRoutes />
      </MemoryRouter>
    </AuthProvider>,
  )
}

async function fillCredentialsAndSubmit(): Promise<void> {
  const user = userEvent.setup()
  const form = screen.getByRole('form', { name: 'Sign in' })
  await user.type(within(form).getByLabelText('Email'), 'reader@example.com')
  await user.type(within(form).getByLabelText('Password'), 'Bookworm7')
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
}

beforeEach(() => {
  // Same store normalization as the AuthProvider/Header suites:
  // setTokens-then-clearTokens also resets the LC-07 flag that clearTokens
  // alone deliberately preserves.
  setTokens({ accessToken: 'reset', refreshToken: 'reset' })
  clearTokens()
  setSessionExpiredHandler(null)
  localStorage.clear()
  installStub(() => [200, {}])
})

describe('RequireAuth guard (LC-27)', () => {
  it('requireAuth_whenAnonymousOpensCart_redirectsToLoginForm', () => {
    renderRoute('/cart')

    expect(screen.getByRole('form', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Your cart' })).toBeNull()
  })

  it('requireAuth_whenCredentialPresent_rendersCartWithoutRedirect', () => {
    setTokens({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      user: TOKEN_PAIR.user,
    })

    renderRoute('/cart')

    expect(
      screen.getByRole('heading', { name: 'Your cart' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: 'Sign in' })).toBeNull()
  })

  it('cartGuard_whenGuardedVisitorLogsIn_landsOnCartAgain', async () => {
    // LC-27 end to end: "anonymous opens the cart URL directly → redirected
    // to login; after logging in, the cart is available". The return path
    // rode the router state from RequireAuth to LoginPage.
    installStub((url) =>
      url === '/auth/login' ? [200, TOKEN_PAIR] : [404, {}],
    )
    renderRoute('/cart')

    await fillCredentialsAndSubmit()

    expect(
      await screen.findByRole('heading', { name: 'Your cart' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument()
  })
})

describe('LoginPage session-expired notice (LC-07)', () => {
  it('loginPage_whenReachedViaHardRedirectParam_showsNoticeAboveForm', () => {
    // The pre-wiring fallback (lib/http.ts) reloads the app at
    // /login?reason=session-expired — the search param is the only signal
    // that survives a full reload, so the notice must read it (ADR-011).
    renderRoute('/login?reason=session-expired')

    expect(screen.getByRole('status')).toHaveTextContent(EXPIRED_NOTICE)
    expect(screen.getByRole('form', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('loginPage_whenStoreFlaggedExpiry_showsNoticeWithoutParam', () => {
    // Client-side path: the interceptor already marked the store when the
    // refresh credential was rejected; no URL plumbing needed.
    markSessionExpired()

    renderRoute('/login')

    expect(screen.getByRole('status')).toHaveTextContent(EXPIRED_NOTICE)
  })

  it('loginPage_whenNeverSignedIn_showsNoExpiryNotice', () => {
    renderRoute('/login')

    expect(screen.queryByText(EXPIRED_NOTICE)).toBeNull()
    expect(screen.getByRole('form', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('interceptor_whenRefreshDiesMidCartSession_redirectsToNoticeAndReloginReturnsToCart', async () => {
    // LC-07's full client-side arc, through the ADR-011 handler chain:
    // guarded page → 401 → single-flight refresh rejected → store marked +
    // Layout's navigate lands on /login with the notice and the /cart
    // return path → re-login replays it.
    setTokens({
      accessToken: 'expired-access',
      refreshToken: 'stale-refresh',
      user: TOKEN_PAIR.user,
    })
    const sent = installStub((url) => {
      if (url === '/auth/refresh') return [401, { status: 401 }]
      if (url === '/auth/login') return [200, TOKEN_PAIR]
      return [401, { status: 401 }]
    })
    renderRoute('/cart')
    expect(
      screen.getByRole('heading', { name: 'Your cart' }),
    ).toBeInTheDocument()

    await act(async () => {
      await http.get('/cart').catch(() => undefined)
    })

    expect(sent.map((request) => request.url)).toContain('/auth/refresh')
    expect(await screen.findByRole('status')).toHaveTextContent(EXPIRED_NOTICE)
    expect(screen.getByRole('form', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Your cart' })).toBeNull()

    await fillCredentialsAndSubmit()

    expect(
      await screen.findByRole('heading', { name: 'Your cart' }),
    ).toBeInTheDocument()
  })
})

describe('Route table', () => {
  it('routeTable_whenRegisterPath_rendersRegistrationForm', () => {
    renderRoute('/register')

    // The header also offers a "Create account" link — role distinguishes
    // it from the form's submit button.
    expect(
      screen.getByRole('button', { name: 'Create account' }),
    ).toBeInTheDocument()
  })

  it('routeTable_whenVerifyEmailPathWithoutToken_showsInvalidLinkState', async () => {
    // D-01 route slot wired to FE-07's page; the absent ?token= search param
    // renders the invalid-link state without touching the network.
    renderRoute('/verify-email')

    expect(
      await screen.findByText('Confirmation link is not valid'),
    ).toBeInTheDocument()
  })
})
