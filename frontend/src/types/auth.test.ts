import { describe, expect, it } from 'vitest'
import { type z } from 'zod'
import {
  confirmRequestSchema,
  loginRequestSchema,
  refreshRequestSchema,
  registerRequestSchema,
} from './auth'

function firstIssue(
  schema: z.ZodType,
  value: unknown,
  path: string,
): string | undefined {
  const result = schema.safeParse(value)
  if (result.success) return undefined
  return result.error.issues.find((issue) => issue.path[0] === path)?.message
}

describe('registerRequestSchema (FR-01 mirror)', () => {
  it('register_whenValid_acceptsAndNormalizesEmailKeepingPasswordVerbatim', () => {
    const result = registerRequestSchema.safeParse({
      email: '  Admin@FooBooks.com ',
      password: '  passw0rd  ',
    })

    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.email).toBe('admin@foobooks.com')
      expect(result.data.password).toBe('  passw0rd  ')
    }
  })

  it('register_whenEmailBlank_rejectsAsNotBlank', () => {
    expect(
      firstIssue(
        registerRequestSchema,
        { email: '   ', password: 'passw0rd1' },
        'email',
      ),
    ).toBe('must not be blank')
  })

  it('register_whenEmailMalformed_rejectsAsWellFormed', () => {
    expect(
      firstIssue(
        registerRequestSchema,
        { email: 'not-an-email', password: 'passw0rd1' },
        'email',
      ),
    ).toBe('must be a well-formed email address')
  })

  it('register_whenEmailExceeds320Characters_rejectsAsTooLong', () => {
    const email = `${'a'.repeat(310)}@example.com`

    expect(
      firstIssue(
        registerRequestSchema,
        { email, password: 'passw0rd1' },
        'email',
      ),
    ).toBe('must be at most 320 characters')
  })

  it('register_whenPasswordShorterThan8Characters_rejectsAsTooShort', () => {
    expect(
      firstIssue(
        registerRequestSchema,
        { email: 'a@b.co', password: 'a1b2c3' },
        'password',
      ),
    ).toBe('must be at least 8 characters')
  })

  it('register_whenPasswordLacksDigit_rejectsAsComplexity', () => {
    expect(
      firstIssue(
        registerRequestSchema,
        { email: 'a@b.co', password: 'abcdefgh' },
        'password',
      ),
    ).toBe('must contain at least one letter and one digit')
  })

  it('register_whenPasswordLacksLetter_rejectsAsComplexity', () => {
    expect(
      firstIssue(
        registerRequestSchema,
        { email: 'a@b.co', password: '12345678' },
        'password',
      ),
    ).toBe('must contain at least one letter and one digit')
  })

  it('register_whenPasswordExceeds72Utf8Bytes_rejectsAsTooManyBytes', () => {
    const password = `${'ä'.repeat(36)}1`

    expect(
      firstIssue(
        registerRequestSchema,
        { email: 'a@b.co', password },
        'password',
      ),
    ).toBe('must be at most 72 bytes')
  })

  it('register_whenPasswordIsExactly72Utf8Bytes_accepts', () => {
    const password = `${'ä'.repeat(35)}a1`

    expect(
      registerRequestSchema.safeParse({ email: 'a@b.co', password }).success,
    ).toBe(true)
  })
})

describe('loginRequestSchema (FR-03/LC-06: structural only)', () => {
  it('login_whenPasswordViolatesComplexity_acceptsWithoutShapeOracle', () => {
    expect(
      loginRequestSchema.safeParse({ email: 'a@b.co', password: 'x' }).success,
    ).toBe(true)
  })

  it('login_whenPasswordExceeds72Utf8Bytes_rejectsAsTooManyBytes', () => {
    const password = 'a'.repeat(73)

    expect(
      firstIssue(loginRequestSchema, { email: 'a@b.co', password }, 'password'),
    ).toBe('must be at most 72 bytes')
  })
})

describe('token request schemas (FR-02/FR-04/FR-05)', () => {
  it('confirm_whenTokenBlank_rejectsAsNotBlank', () => {
    expect(firstIssue(confirmRequestSchema, { token: '' }, 'token')).toBe(
      'must not be blank',
    )
  })

  it('refresh_whenTokenExceeds64Characters_rejectsAsTooLong', () => {
    expect(
      firstIssue(
        refreshRequestSchema,
        { refreshToken: 't'.repeat(65) },
        'refreshToken',
      ),
    ).toBe('must be at most 64 characters')
  })
})
