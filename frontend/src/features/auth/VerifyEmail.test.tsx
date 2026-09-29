import {
  AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { VerifyEmail } from './VerifyEmail'
import { http } from '../../lib/http'
import { clearTokens } from '../../lib/tokens'

// FE-07 tests drive the whole client stack (page → api/auth → shared axios
// instance) through a stubbed adapter, pinning both the D-01 wire contract
// (token read from ?token=, POSTed to /auth/confirm) and the FR-02 state
// machine (CONFIRMED / ALREADY_CONFIRMED / 410 / anything-else).

interface SentRequest {
  url: string
  body: Record<string, unknown>
}

type Responder = (request: SentRequest) => [number, unknown]

const TOKEN = 'a'.repeat(64)

const CONFIRMED_BODY = {
  outcome: 'CONFIRMED',
  message: 'Email confirmed. You can log in now.',
}
const ALREADY_CONFIRMED_BODY = {
  outcome: 'ALREADY_CONFIRMED',
  message: 'This email was already confirmed. You can log in now.',
}
const EXPIRED_410_BODY = {
  type: 'urn:foley-books:problem:expired-confirmation-token',
  title: 'Confirmation link has expired',
  status: 410,
  detail: 'This confirmation link has expired. You can request a new one.',
  instance: '/api/v1/auth/confirm',
  traceId: 'c3d4e5f6',
  resendHint: 'Request a new link with POST /api/v1/auth/resend.',
}
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
    const request = { url: config.url ?? '', body }
    sent.push(request)
    const [status, data] = responder(request)
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

// FE-10 mounted /verify-email in the route table and the success CTA became
// a <Link>, so the page renders inside a router now — as in production. The
// D-01 token rides the router's own URL: initialEntries carries ?token=…,
// which is exactly what the page reads through useSearchParams.
function renderVerifyEmail(token: string | null = TOKEN): void {
  render(
    <MemoryRouter
      initialEntries={[
        token === null ? '/verify-email' : `/verify-email?token=${token}`,
      ]}
    >
      <VerifyEmail />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  clearTokens()
})

describe('VerifyEmail', () => {
  it('mount_whenLinkCarriesValidToken_postsConfirmOnceAndShowsConfirmedState', async () => {
    // FR-02/D-01: the page POSTs the query token — it never renders (C24) —
    // and a live link verifies the account with the server's success copy.
    const sent = installStub(() => [200, CONFIRMED_BODY])
    renderVerifyEmail()

    expect(await screen.findByText('Email confirmed')).toBeInTheDocument()
    expect(
      screen.getByText('Email confirmed. You can log in now.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to login' })).toHaveAttribute(
      'href',
      '/login',
    )
    expect(sent).toEqual([{ url: '/auth/confirm', body: { token: TOKEN } }])
    expect(screen.queryByRole('alert')).toBeNull()
    // C24, enforced: the raw token travelled in the URL and the POST body,
    // but it must never reach the rendered DOM.
    expect(document.body.textContent).not.toContain(TOKEN)
  })

  it('mount_whenTokenAlreadyUsed_showsIdempotentAlreadyConfirmedSuccess', async () => {
    // LC-03/LC-23: re-opening a spent link is a success, not an error — the
    // outcome discriminator picks the "already confirmed" heading.
    installStub(() => [200, ALREADY_CONFIRMED_BODY])
    renderVerifyEmail()

    expect(await screen.findByText('Already confirmed')).toBeInTheDocument()
    expect(
      screen.getByText('This email was already confirmed. You can log in now.'),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: 'Go to login' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('mount_whenLinkExpired_showsExpiredStateWithResendOffer', async () => {
    // LC-02: the 410 renders the server's user-facing detail and the inline
    // resend panel; the machine-readable resendHint (an API pointer, not
    // user copy) never leaks to the screen.
    installStub(() => [410, EXPIRED_410_BODY])
    renderVerifyEmail()

    expect(
      await screen.findByText('Confirmation link is not valid'),
    ).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(
      'This confirmation link has expired. You can request a new one.',
    )
    expect(
      screen.queryByText('Request a new link with POST /api/v1/auth/resend.'),
    ).toBeNull()
    expect(
      screen.getByRole('heading', { name: 'Request a new confirmation link' }),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toBeInTheDocument()
    // C24 again in the failure rendering: still no token on screen.
    expect(document.body.textContent).not.toContain(TOKEN)
  })

  it('mount_whenLinkCarriesNoToken_showsInvalidStateAndNeverPosts', async () => {
    // An empty token fails the zod mirror before the network: there is no
    // useful POST, and the invalid-link state covers it (LC-02's recovery).
    const sent = installStub(() => [200, CONFIRMED_BODY])
    renderVerifyEmail(null)

    expect(
      await screen.findByText('Confirmation link is not valid'),
    ).toBeInTheDocument()
    expect(sent).toHaveLength(0)
  })

  it('mount_whileConfirmPending_showsAnnouncedBusyState', async () => {
    // NFR-03: the automatic confirmation on arrival is visible and announced.
    let settle: ((response: AxiosResponse) => void) | undefined
    let requestConfig: InternalAxiosRequestConfig | undefined
    http.defaults.adapter = (config: InternalAxiosRequestConfig) => {
      requestConfig = config
      return new Promise<AxiosResponse>((resolve) => {
        settle = (response) => resolve(response)
      })
    }
    renderVerifyEmail()

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Confirming your email…',
    )

    settle?.({
      status: 200,
      statusText: '',
      headers: {},
      config: requestConfig as InternalAxiosRequestConfig,
      data: CONFIRMED_BODY,
    })
    expect(await screen.findByText('Email confirmed')).toBeInTheDocument()
  })

  it('mount_whenConfirmFailsUnexpectedly_showsGenericErrorAndRetryReposts', async () => {
    // NFR-06: an unexpected failure stays generic + traceable, and the retry
    // action re-POSTs the same token (the link is still live server-side).
    let responses = 0
    const sent = installStub(() => {
      responses += 1
      return responses === 1
        ? [
            500,
            {
              type: 'urn:foley-books:problem:error',
              title: 'Internal server error',
              status: 500,
              detail: 'Unexpected failure.',
              traceId: 'f6a7b8c9',
            },
          ]
        : [200, CONFIRMED_BODY]
    })
    renderVerifyEmail()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(
      'We could not confirm your email. Please try again.',
    )
    expect(alert).toHaveTextContent('Reference: f6a7b8c9')

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('Email confirmed')).toBeInTheDocument()
    expect(sent).toHaveLength(2)
  })

  it('resendFromExpiredLinkState_submitsEmailAndShowsGenericMessage', async () => {
    // FR-02 recovery loop: expired link → resend from the same page → the
    // fixed 202 copy (LC-04's wait guidance rides it verbatim).
    const sent = installStub((request) =>
      request.url === '/auth/confirm'
        ? [410, EXPIRED_410_BODY]
        : [202, RESEND_GENERIC_202],
    )
    const user = userEvent.setup()
    renderVerifyEmail()

    await screen.findByText('Confirmation link is not valid')
    await user.type(screen.getByLabelText('Email'), ' Reader@Example.com ')
    await user.click(screen.getByRole('button', { name: 'Send new link' }))

    expect(await screen.findByText('Check your inbox')).toBeInTheDocument()
    expect(screen.getByText(RESEND_GENERIC_202.message)).toBeInTheDocument()
    expect(sent).toEqual([
      { url: '/auth/confirm', body: { token: TOKEN } },
      { url: '/auth/resend', body: { email: 'reader@example.com' } },
    ])
  })
})
