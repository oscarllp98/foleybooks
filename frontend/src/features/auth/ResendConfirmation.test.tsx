import {
  AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { ResendConfirmation } from './ResendConfirmation'
import { http } from '../../lib/http'
import { clearTokens } from '../../lib/tokens'

// FE-07 tests drive the whole client stack (form → api/auth → shared axios
// instance) through a stubbed adapter — the technique RegisterForm.test
// established — so the wire contract (path + normalized body) is pinned too.

interface SentRequest {
  url: string
  body: Record<string, unknown>
}

type Responder = (body: Record<string, unknown>) => [number, unknown]

// The fixed generic 202 every resend branch returns (LC-20) — verbatim from
// ResendResponse.GENERIC_MESSAGE, including the LC-04 wait-a-minute guidance.
const GENERIC_202 = {
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

async function fillAndSubmit(email: string): Promise<void> {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Email'), email)
  await user.click(screen.getByRole('button', { name: 'Send new link' }))
}

beforeEach(() => {
  clearTokens()
})

describe('ResendConfirmation', () => {
  it('submit_whenEmailValid_postsNormalizedPayloadAndShowsGenericMessage', async () => {
    // FR-02/LC-20: one success state, the server's fixed generic message,
    // displayed verbatim — it may not be reworded per branch.
    const sent = installStub(() => [202, GENERIC_202])
    render(<ResendConfirmation />)

    await fillAndSubmit('  Reader@Example.com ')

    expect(await screen.findByText('Check your inbox')).toBeInTheDocument()
    expect(screen.getByText(GENERIC_202.message)).toBeInTheDocument()
    expect(sent).toEqual([
      { url: '/auth/resend', body: { email: 'reader@example.com' } },
    ])
    expect(screen.queryByRole('button', { name: 'Send new link' })).toBeNull()
  })

  it('submit_whenThrottled_showsTheSameWaitMessageAsAFreshSend', async () => {
    // LC-04/NFR-01: the throttle answer is the same byte-identical 202 as a
    // fresh send, so the user learns to wait only from that constant copy —
    // the form renders no state that could distinguish the two branches.
    const sent = installStub(() => [202, GENERIC_202])
    render(<ResendConfirmation />)

    await fillAndSubmit('reader@example.com')

    expect(await screen.findByText('Check your inbox')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(
      'If you do not see it, please try again in a minute.',
    )
    // Same fixed message as the fresh-send test above: one body for both
    // branches, and nothing else — no email echo, no per-branch copy.
    expect(screen.getByRole('status')).toHaveTextContent(GENERIC_202.message)
    expect(sent).toHaveLength(1)
  })

  it('submit_whenEmailMalformed_showsInlineRuleErrorAndDoesNotPost', async () => {
    // FR-02 inline validation: the zod mirror's message equals the backend's.
    const sent = installStub(() => [202, GENERIC_202])
    render(<ResendConfirmation />)

    await fillAndSubmit('not-an-email')

    expect(
      await screen.findByText('must be a well-formed email address'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAttribute(
      'aria-invalid',
      'true',
    )
    expect(sent).toHaveLength(0)
  })

  it('submit_whenRequestPending_showsBusyStateAndCannotDoublePost', async () => {
    // NFR-03: the in-flight submission is visible (busy button) and announced
    // (role="status"); a second activation never posts a duplicate resend.
    const user = userEvent.setup()
    const sent: SentRequest[] = []
    let settle: ((response: AxiosResponse) => void) | undefined
    http.defaults.adapter = (config: InternalAxiosRequestConfig) => {
      sent.push({ url: config.url ?? '', body: {} })
      return new Promise<AxiosResponse>((resolve) => {
        settle = (response) => resolve(response)
      })
    }
    render(<ResendConfirmation />)

    await user.type(screen.getByLabelText('Email'), 'reader@example.com')
    await user.click(screen.getByRole('button', { name: 'Send new link' }))

    const busy = await screen.findByRole('button', { name: 'Sending link…' })
    expect(busy).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('Sending a new link…')

    await user.click(busy)
    expect(sent).toHaveLength(1)

    settle?.({
      status: 202,
      statusText: '',
      headers: {},
      config: {} as InternalAxiosRequestConfig,
      data: GENERIC_202,
    })
    expect(await screen.findByText('Check your inbox')).toBeInTheDocument()
  })

  it('serverError_whenProblemDetailReportsEmail_mapsMessageInlineOnThatField', async () => {
    // NFR-06/D-15: a 400 errors[] entry lands in the email field's inline
    // slot, not in the shared ErrorState banner.
    installStub(() => [
      400,
      {
        type: 'urn:foley-books:problem:validation',
        title: 'Validation failed',
        status: 400,
        detail: 'One or more fields are invalid.',
        instance: '/api/v1/auth/resend',
        traceId: 'a1b2c3d4',
        errors: [{ field: 'email', message: 'must be at most 320 characters' }],
      },
    ])
    render(<ResendConfirmation />)

    await fillAndSubmit('reader@example.com')

    expect(
      await screen.findByText('must be at most 320 characters'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAttribute(
      'aria-invalid',
      'true',
    )
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('serverError_whenFailureCarriesTraceId_showsGenericMessageWithReference', async () => {
    // NFR-06: unexpected failures stay generic but traceable. The message
    // reveals nothing about the account — same as every other failure.
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
    render(<ResendConfirmation />)

    await fillAndSubmit('reader@example.com')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(
      'We could not send a new link. Please try again.',
    )
    expect(alert).toHaveTextContent('Reference: f6a7b8c9')
    expect(screen.getByRole('button', { name: 'Send new link' })).toBeEnabled()
  })
})
