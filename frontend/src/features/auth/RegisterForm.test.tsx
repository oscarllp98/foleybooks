import {
  AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { RegisterForm } from './RegisterForm'
import { http } from '../../lib/http'
import { clearTokens } from '../../lib/tokens'

// FE-06 tests drive the whole client stack (form → api/auth → shared axios
// instance) through a stubbed adapter — the technique AuthProvider.test
// established — so the wire contract (path + normalized body) is pinned too.

interface SentRequest {
  url: string
  body: Record<string, unknown>
}

type Responder = (body: Record<string, unknown>) => [number, unknown]

const SUCCESS_BODY = {
  email: 'reader@example.com',
  status: 'UNVERIFIED',
  message: 'Confirmation is on its way. Check your inbox (and spam folder).',
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

async function fillAndSubmit(email: string, password: string): Promise<void> {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Email'), email)
  await user.type(screen.getByLabelText('Password'), password)
  await user.click(screen.getByRole('button', { name: 'Create account' }))
}

beforeEach(() => {
  clearTokens()
})

describe('RegisterForm', () => {
  it('submit_whenFieldsValid_postsNormalizedPayloadAndShowsConfirmationState', async () => {
    // FR-01: same response for new and duplicate emails (LC-01) — the only
    // post-success state is "check your inbox" with the server message.
    const sent = installStub(() => [201, SUCCESS_BODY])
    render(<RegisterForm />)

    await fillAndSubmit('  Reader@Example.com ', 'Bookworm7')

    expect(await screen.findByText('Check your inbox')).toBeInTheDocument()
    expect(
      screen.getByText(
        'Confirmation is on its way. Check your inbox (and spam folder).',
      ),
    ).toBeInTheDocument()
    expect(screen.getByText('reader@example.com')).toBeInTheDocument()
    expect(sent).toEqual([
      {
        url: '/auth/register',
        body: { email: 'reader@example.com', password: 'Bookworm7' },
      },
    ])
    expect(screen.queryByRole('button', { name: 'Create account' })).toBeNull()
  })

  it('submit_whenEmailMalformed_showsInlineRuleErrorAndDoesNotPost', async () => {
    // FR-01 inline validation: the zod mirror's message equals the backend's.
    const sent = installStub(() => [201, SUCCESS_BODY])
    render(<RegisterForm />)

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

  it('submit_whenPasswordFailsComplexity_showsInlineRuleErrorAndDoesNotPost', async () => {
    // FR-01: at least one letter and one digit, min 8 chars — same @Pattern
    // message the 400 ProblemDetail would carry.
    const sent = installStub(() => [201, SUCCESS_BODY])
    render(<RegisterForm />)

    await fillAndSubmit('reader@example.com', 'bookworms')

    expect(
      await screen.findByText('must contain at least one letter and one digit'),
    ).toBeInTheDocument()
    expect(sent).toHaveLength(0)
  })

  it('submit_whenRequestPending_showsBusyStateAndCannotDoublePost', async () => {
    // NFR-03: the in-flight submission is visible (busy button) and announced
    // (role="status"); a second activation never posts a duplicate register.
    const user = userEvent.setup()
    const sent: SentRequest[] = []
    let settle: ((response: AxiosResponse) => void) | undefined
    http.defaults.adapter = (config: InternalAxiosRequestConfig) => {
      sent.push({ url: config.url ?? '', body: {} })
      return new Promise<AxiosResponse>((resolve) => {
        settle = (response) => resolve(response)
      })
    }
    render(<RegisterForm />)

    await user.type(screen.getByLabelText('Email'), 'reader@example.com')
    await user.type(screen.getByLabelText('Password'), 'Bookworm7')
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    const busy = await screen.findByRole('button', {
      name: 'Creating account…',
    })
    expect(busy).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('Creating account…')

    await user.click(busy)
    expect(sent).toHaveLength(1)

    settle?.({
      status: 201,
      statusText: '',
      headers: {},
      config: {} as InternalAxiosRequestConfig,
      data: SUCCESS_BODY,
    })
    expect(await screen.findByText('Check your inbox')).toBeInTheDocument()
  })

  it('serverError_whenProblemDetailReportsField_mapsMessageInlineOnThatField', async () => {
    // NFR-06/D-15: a 400 errors[] entry lands in the field's inline slot, not
    // in the shared ErrorState banner. 'Bookworm7' passes zod, so the
    // displayed message can only have come from the server mapping.
    installStub(() => [
      400,
      {
        type: 'urn:foley-books:problem:validation',
        title: 'Validation failed',
        status: 400,
        detail: 'One or more fields are invalid.',
        instance: '/api/v1/auth/register',
        traceId: 'a1b2c3d4',
        errors: [{ field: 'password', message: 'must be at most 72 bytes' }],
      },
    ])
    render(<RegisterForm />)

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

  it('serverError_whenFailureCarriesTraceId_showsGenericMessageWithReference', async () => {
    // NFR-06: unexpected failures stay generic but traceable.
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
    render(<RegisterForm />)

    await fillAndSubmit('reader@example.com', 'Bookworm7')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(
      'We could not create your account. Please try again.',
    )
    expect(alert).toHaveTextContent('Reference: f6a7b8c9')
    // LC-18: the form stays usable — nothing is lost, retry is possible.
    expect(screen.getByRole('button', { name: 'Create account' })).toBeEnabled()
  })
})
