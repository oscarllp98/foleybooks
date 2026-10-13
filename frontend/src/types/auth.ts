import { z } from 'zod'

// FE-02: mirrors the Bean Validation constraints on auth-service's request
// records (AGENTS.md §6, FR-01..FR-05). Error strings are the backend's
// verbatim messages, so inline form errors read exactly like the 400
// ProblemDetail `errors[]` a direct API call would produce.

export const MAX_EMAIL_LENGTH = 320
export const MAX_TOKEN_LENGTH = 64
export const MAX_PASSWORD_BYTES = 72
export const MIN_PASSWORD_LENGTH = 8

// RegisterRequest's @Pattern: at least one Unicode letter and one digit.
// `s` mirrors the backend's (?s) so `.` spans newlines; `u` enables \p{L}.
const PASSWORD_PATTERN = /^(?=.*\p{L})(?=.*\d).*$/su

function utf8ByteLength(value: string): number {
  return new TextEncoder().encode(value).length
}

// Mirrors EmailNormalizingDeserializer: trim + lowercase before validation,
// so the client and server agree on what "same address" means. z.email()
// approximates Hibernate's @Email: the server stays the authority; this is
// an inline-feedback mirror, not a second enforcement layer.
const normalizedEmailSchema = z
  .string()
  .trim()
  .toLowerCase()
  .min(1, 'must not be blank')
  .max(MAX_EMAIL_LENGTH, `must be at most ${MAX_EMAIL_LENGTH} characters`)
  .check(z.email('must be a well-formed email address'))

// Opaque 256-bit token transported as base64url text (FR-02/FR-04/FR-05).
const opaqueTokenSchema = z
  .string()
  .min(1, 'must not be blank')
  .max(MAX_TOKEN_LENGTH, `must be at most ${MAX_TOKEN_LENGTH} characters`)

// Passwords are never transformed: they are kept verbatim, whitespace
// significant (LC-26); @NotBlank only rejects blank-looking values.
function passwordSchema(maxBytesOnly: boolean) {
  const base = z
    .string()
    .refine((value) => value.trim().length > 0, 'must not be blank')
  if (maxBytesOnly) {
    // LoginRequest is deliberately structural-only: complexity or length
    // feedback at login would be a shape oracle (FR-03, LC-06).
    return base.refine(
      (value) => utf8ByteLength(value) <= MAX_PASSWORD_BYTES,
      `must be at most ${MAX_PASSWORD_BYTES} bytes`,
    )
  }
  return base
    .min(
      MIN_PASSWORD_LENGTH,
      `must be at least ${MIN_PASSWORD_LENGTH} characters`,
    )
    .regex(PASSWORD_PATTERN, 'must contain at least one letter and one digit')
    .refine(
      (value) => utf8ByteLength(value) <= MAX_PASSWORD_BYTES,
      `must be at most ${MAX_PASSWORD_BYTES} bytes`,
    )
}

export const registerRequestSchema = z.object({
  email: normalizedEmailSchema,
  password: passwordSchema(false),
})

export const loginRequestSchema = z.object({
  email: normalizedEmailSchema,
  password: passwordSchema(true),
})

export const confirmRequestSchema = z.object({
  token: opaqueTokenSchema,
})

export const resendRequestSchema = z.object({
  email: normalizedEmailSchema,
})

export const refreshRequestSchema = z.object({
  refreshToken: opaqueTokenSchema,
})

export const logoutRequestSchema = z.object({
  refreshToken: opaqueTokenSchema,
})

export type RegisterRequest = z.infer<typeof registerRequestSchema>
export type LoginRequest = z.infer<typeof loginRequestSchema>
export type ConfirmRequest = z.infer<typeof confirmRequestSchema>
export type ResendRequest = z.infer<typeof resendRequestSchema>
export type RefreshRequest = z.infer<typeof refreshRequestSchema>
export type LogoutRequest = z.infer<typeof logoutRequestSchema>

// Response contracts (plan §2): public UUIDs are strings on the wire,
// BigDecimal money is a JSON number (D-08).

export type UserRole = 'CUSTOMER' | 'ADMIN'
export type UserStatus = 'UNVERIFIED' | 'VERIFIED'
export type ConfirmOutcome = 'CONFIRMED' | 'ALREADY_CONFIRMED'

export interface UserSummary {
  id: string
  email: string
  role: UserRole
}

export interface TokenPair {
  accessToken: string
  refreshToken: string
  tokenType: 'Bearer'
  expiresIn: number
  user: UserSummary
}

export interface RegisterResponse {
  email: string
  status: UserStatus
  message: string
}

export interface ConfirmResponse {
  outcome: ConfirmOutcome
  message: string
}

export interface ResendResponse {
  message: string
}
