import {
  AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from './AuthProvider'
import { LoginForm } from './LoginForm'
import { useAuth } from '../../hooks/useAuth'
import { http, setSessionExpiredHandler } from '../../lib/http'
import { clearTokens, getRefreshToken, setTokens } from '../../lib/tokens'

// FE-08 tests drive the same full client stack as RegisterForm's
// (form → useAuth → AuthProvider → api/auth → shared axios instance) through
// a stubbed adapter, but through the REAL context: the LoginResult → notice
// mapping (LC-05/LC-06) is exactly the seam between the two units, and only
// an integrated render can pin it.

interface SentRequest {
  url: string
  body: Record<string, unknown>
}

type Responder = (body: Record<string, unknown>) => [number, unknown]

const TOKEN_PAIR = {
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: { id: 'u-1', email: 'reader@example.com', role: 'CUSTOMER' },
} as const

// LC-20's fixed generic 202, verbatim from ResendConfirmation.test.
const RESEND_GENERIC_202 = {
  message:
    'If this address has a pending confirmation, a new link is on its way. ' +
    'If you do not see it, please try again in a minute.',
}

function installStub(responder: Responder): SentRequest[] {
  const sent: SentRequest[] = []
  http.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
    const body = JSON.parse(String(config.data ?? '{}')) as Record<
      string,
      unknown
    >
    sent.push({ url: config.url ?? '', body })
    const [status, data] = responder(body)
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

function renderLogin(
  options: { onLoggedIn?: () => void; withProbe?: boolean } = {},
): void {
  const { onLoggedIn, withProbe = false } = options
  render(
    <AuthProvider>
      <LoginForm onLoggedIn={onLoggedIn} />
      {withProbe ? <AuthProbe /> : null}
    </AuthProvider>,
  )
}

/** Proves the session actually exists in the store, not just that the
 *  callback fired. */
function AuthProbe() {
  const { isAuthenticated, user } = useAuth()
  return (
    <p>
      {isAuthenticated ? `signed-in:${user?.email ?? 'unknown'}` : 'signed-out'}
    </p>
  )
}

async function fillAndSubmit(
  email: string,
  password: string,
  buttonName = 'Sign in',
): Promise<void> {
  // Scoped to the login form: once the LC-05 notice mounts, the embedded
  // ResendConfirmation panel renders a second "Email" label on the page.
  const user = userEvent.setup()
  const form = screen.getByRole('form', { name: 'Sign in' })
  await user.type(within(form).getByLabelText('Email'), email)
  await user.type(within(form).getByLabelText('Password'), password)
  await user.click(screen.getByRole('button', { name: buttonName }))
}

beforeEach(() => {
  // Same store reset as AuthProvider.test: setTokens-then-clearTokens also
  // clears the LC-07 flag that clearTokens alone deliberately preserves.
  setTokens({ accessToken: 'reset', refreshToken: 'reset' })
  clearTokens()
  setSessionExpiredHandler(null)
  localStorage.clear()
})

describe('LoginForm', () => {
  it('submit_whenCredentialsAccepted_postsNormalizedPayloadStartsSessionAndNotifiesParent', async () => {
    // FR-03: the normalized wire payload (email trim+lowercase, password
    // verbatim, LC-15/LC-26) and a live session through the ONE store
    // (ADR-012) — the form itself never touches tokens.
    const sent = installStub(() => [200, TOKEN_PAIR])
    const onLoggedIn = vi.fn()
    renderLogin({ onLoggedIn, withProbe: true })

    await fillAndSubmit('  Reader@Example.com ', 'Bookworm7')

    expect(onLoggedIn).toHaveBeenCalledTimes(1)
    expect(
      await screen.findByText('signed-in:reader@example.com'),
    ).toBeInTheDocument()
    expect(sent).toEqual([
      {
        url: '/auth/login',
        body: { email: 'reader@example.com', password: 'Bookworm7' },
      },
    ])
    expect(getRefreshToken()).toBe('refresh-1')
  })

  it('submit_whenEmailMalformed_showsInlineRuleErrorAndDoesNotPost', async () => {
    // FR-03/NFR-03: the zod mirror catches shape problems client-side; a
    // malformed address never reaches the network.
    const sent = installStub(() => [200, TOKEN_PAIR])
    renderLogin()

    await fillAndSubmit('not-an-email', 'Bookworm7')

    expect(
      await screen.findByText('must be a well-formed email address'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAttribute(
      'aria-invalid',
      'true',
    )
    expect(sent).toHaveLength(0)
  })

  it('submit_whenPasswordBlank_showsInlineRequiredErrorAndDoesNotPost', async () => {
    // The login schema is structurally-only: blank is the one thing it may
    // reject (an empty password is not a credential at all).
    const sent = installStub(() => [200, TOKEN_PAIR])
    renderLogin()

    await fillAndSubmit('reader@example.com', '   ')

    expect(await screen.findByText('must not be blank')).toBeInTheDocument()
    expect(sent).toHaveLength(0)
  })

  it('submit_whenPasswordFailsComplexity_postsAnywayBecauseLoginHasNoPasswordRules', async () => {
    // LC-06 (FR-03): no complexity or length feedback at login — that would
    // be a shape oracle. A weak-but-present password must reach the server
    // and come back as the same generic 401 flavor as any wrong password.
    const sent = installStub(() => [401, { status: 401 }])
    renderLogin()

    await fillAndSubmit('reader@example.com', 'short')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The email or password is incorrect.',
    )
    expect(sent).toEqual([
      {
        url: '/auth/login',
        body: { email: 'reader@example.com', password: 'short' },
      },
    ])
    expect(screen.queryByText(/at least 8/)).toBeNull()
    expect(screen.queryByText(/letter and one digit/)).toBeNull()
  })

  it('login_whenCredentialsRejected_showsGenericErrorWithoutFieldErrorsOrSession', async () => {
    // LC-06: one generic message, no field highlighted, no session started,
    // and no refresh attempt (a failed login is not an expired session).
    installStub(() => [401, { status: 401 }])
    renderLogin({ withProbe: true })

    await fillAndSubmit('reader@example.com', 'Bookworm7')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The email or password is incorrect.',
    )
    expect(screen.getByLabelText('Email')).toHaveAttribute(
      'aria-invalid',
      'false',
    )
    expect(screen.getByLabelText('Password')).toHaveAttribute(
      'aria-invalid',
      'false',
    )
    expect(screen.getByText('signed-out')).toBeInTheDocument()
    expect(getRefreshToken()).toBeNull()
  })

  it('login_whenRepeatedWithSameCredentials_showsByteIdenticalGenericError', async () => {
    // LC-06: "repeated wrong passwords → identical generic errors". One
    // responder for every attempt, so the two alerts can only be equal if
    // the form adds no per-attempt variation.
    installStub(() => [401, { status: 401 }])
    const user = userEvent.setup()
    renderLogin()

    const loginForm = screen.getByRole('form', { name: 'Sign in' })
    await user.type(
      within(loginForm).getByLabelText('Email'),
      'reader@example.com',
    )
    await user.type(within(loginForm).getByLabelText('Password'), 'Bookworm7')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    const first = await screen.findByRole('alert')
    expect(first).toHaveTextContent('The email or password is incorrect.')

    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    const second = await screen.findByRole('alert')
    expect(second).toHaveTextContent('The email or password is incorrect.')
    // The stale notice is replaced, never stacked.
    expect(screen.getAllByRole('alert')).toHaveLength(1)
  })

  it('login_whenAccountUnverified_showsConfirmYourEmailNoticeWithInlineResendPanel', async () => {
    // LC-05: the 403 flavor is guidance, not failure — a status announcement
    // and the FE-07 resend panel embedded inline (VerifyEmail's LC-02
    // pattern), never the credential-error alert and never a link to an
    // undeclared route.
    // Login answers 403, the later resend post answers the fixed generic
    // 202 (LC-20). Branching on request order, not body shape.
    let requests = 0
    const sent = installStub(
      () =>
        requests++ === 0
          ? [403, { status: 403 }]
          : [202, { message: RESEND_GENERIC_202.message }],
    )
    const user = userEvent.setup()
    renderLogin()

    await fillAndSubmit('reader@example.com', 'Bookworm7')

    const notice = await screen.findByRole('status')
    expect(notice).toHaveTextContent('Confirm your email first')
    expect(screen.queryByRole('link')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(getRefreshToken()).toBeNull()

    // FR-02 recovery loop: an unverified user verifies without ever logging
    // in — the panel renders its own form (the login <form> never nests it).
    const panel = screen.getByRole('form', {
      name: 'Request a new confirmation link',
    })
    await user.type(within(panel).getByLabelText('Email'), 'reader@example.com')
    await user.click(
      within(panel).getByRole('button', { name: 'Send new link' }),
    )

    // On success ResendConfirmation replaces its form with the generic
    // "check your inbox" status (LC-20) inside the same notice section.
    expect(
      await screen.findByText(RESEND_GENERIC_202.message),
    ).toBeInTheDocument()
    expect(sent).toHaveLength(2)
    expect(sent[1]).toEqual({
      url: '/auth/resend',
      body: { email: 'reader@example.com' },
    })
  })

  it('login_whenFailureCarriesTraceId_showsGenericMessageWithReference', async () => {
    // NFR-06: unexpected failures (5xx/network) stay generic but traceable,
    // and the form stays usable — FR-03 never mixes transport failure into
    // the credential message.
    installStub(() => [
      500,
      {
        type: 'urn:foley-books:problem:error',
        title: 'Internal server error',
        status: 500,
        detail: 'Unexpected failure.',
        traceId: 'f6a7b8c9',
      },
    ])
    renderLogin()

    await fillAndSubmit('reader@example.com', 'Bookworm7')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(
      'We could not sign you in. Please try again.',
    )
    expect(alert).toHaveTextContent('Reference: f6a7b8c9')
    expect(alert).not.toHaveTextContent('The email or password is incorrect.')
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled()
  })

  it('login_whenRequestPending_showsBusyStateAndCannotDoublePost', async () => {
    // NFR-03: the in-flight submission is visible (busy button) and announced
    // (role="status"); a second activation never posts a duplicate login.
    const user = userEvent.setup()
    const sent: SentRequest[] = []
    let settle: ((response: AxiosResponse) => void) | undefined
    http.defaults.adapter = (config: InternalAxiosRequestConfig) => {
      sent.push({ url: config.url ?? '', body: {} })
      return new Promise<AxiosResponse>((resolve) => {
        settle = (response) => resolve(response)
      })
    }
    const onLoggedIn = vi.fn()
    renderLogin({ onLoggedIn })

    await user.type(screen.getByLabelText('Email'), 'reader@example.com')
    await user.type(screen.getByLabelText('Password'), 'Bookworm7')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    const busy = await screen.findByRole('button', { name: 'Signing in…' })
    expect(busy).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('Signing in…')

    await user.click(busy)
    expect(sent).toHaveLength(1)

    settle?.({
      status: 200,
      statusText: '',
      headers: {},
      config: {} as InternalAxiosRequestConfig,
      data: TOKEN_PAIR,
    })
    // The settled 200 starts the session silently: no error surface appears
    // and the pending announcement is gone — success is the parent's cue
    // (onLoggedIn), not a message on this form.
    await waitFor(() => expect(onLoggedIn).toHaveBeenCalled())
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('serverError_whenProblemDetailReportsField_mapsMessageInlineOnThatField', async () => {
    // NFR-06/D-15: a 400 errors[] entry lands in the field's inline slot, not
    // in a form-level banner. 'Bookworm7' passes the structural schema, so
    // the displayed message can only have come from the server mapping.
    installStub(() => [
      400,
      {
        type: 'urn:foley-books:problem:validation',
        title: 'Validation failed',
        status: 400,
        detail: 'One or more fields are invalid.',
        instance: '/api/v1/auth/login',
        traceId: 'a1b2c3d4',
        errors: [{ field: 'password', message: 'must be at most 72 bytes' }],
      },
    ])
    renderLogin()

    await fillAndSubmit('reader@example.com', 'Bookworm7')

    expect(
      await screen.findByText('must be at most 72 bytes'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toHaveAttribute(
      'aria-invalid',
      'true',
    )
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
