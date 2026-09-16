# ADR-001: Mail sending — spring-boot-starter-mail behind an app-owned port

## Status

Accepted — implements plan D-02; docs gate for FR-01/FR-02 (AU-07). Prerequisite for
AU-08 (port + adapters) and AU-09 (async dispatch + retry). The dependency itself was
declared in the AU-02 module scaffold; this ADR ratifies it per AGENTS.md §12.4
("ADRs delivered during implementation", plan §5).

## Context

FR-01 dispatches a confirmation email on registration and an "already registered"
email on duplicate-verified; FR-02 re-dispatches confirmation links. These are the
only two emails the MVP sends — the spec's out-of-reach list explicitly excludes
"any email beyond the confirmation and 'already registered' emails".

Constraints that shape the choice:

- **C2**: `spring-boot-starter-mail` was added to auth-service in AU-02; a dependency
  without an ADR is a compliance gap this document closes.
- **LC-18 / FR-01**: "Email delivery problems never block registration" — dispatch
  must be asynchronous and failures non-fatal.
- **C27**: only the gateway (`:8080`) and the Mailpit UI (`:8025`) publish host ports,
  so Mailpit's SMTP socket (`:1025`) is reachable only inside the compose network.
- **C19/C24**: SMTP credentials come from env vars only; tokens and credentials are
  never logged.
- **C7/C8**: the register/confirm services must not hard-depend on a mail framework;
  mail is an infrastructure concern behind a seam (plan §1:
  `mail/ — MailSender port + profile impls (Mailpit/SMTP/log); async dispatch`).
- **Tests (plan §6.1)**: unit tests such as
  `register_existingVerifiedUser_sendsAlreadyRegisteredEmail` must assert email
  intent without any SMTP server.

## Decision

Use `spring-boot-starter-mail` as the only mail dependency, exclusively in
auth-service, and hide every Spring mail type behind an app-owned port in the
`mail/` feature package:

```
com.foleybooks.auth.mail
├── MailSender           # app-owned port (note: our type, not Spring's same-named interface)
├── SmtpMailSender       # adapter — the only non-config code referencing mail types
└── LoggingMailSender    # test adapter — records intent, delivers nothing
```

- The port exposes the two use cases (confirmation link, already-registered notice)
  as non-blocking sends; the caller never waits on SMTP.
- **One SMTP adapter serves both Mailpit and any real provider** — Mailpit speaks
  plain SMTP, so "Mailpit" is a configuration target, not a distinct implementation.
- Dispatch is asynchronous with a scheduled retry of **3 attempts** (AU-09); when
  all attempts fail the event is logged at error level (recipient-free message
  wording where feasible, never the token value — C24) and the request flow is
  unaffected: FR-02's resend is the user-visible recovery path.

### Wiring per environment

| Context | Profile | Implementation | Target / env vars |
| ------- | ------- | -------------- | ----------------- |
| Local host run | `dev` | `SmtpMailSender` | `SPRING_MAIL_HOST:localhost` / `SPRING_MAIL_PORT:1025` (base `application.yml`, all profiles unless overridden) |
| Compose stack | `docker` | `SmtpMailSender` | `mailpit:1025` (network-internal, C27); no auth — Mailpit accepts anonymous |
| Tests | n/a — no `test` profile | `LoggingMailSender` for context-booting ITs (e.g. AU-19); plain unit tests mock the port itself (plan §6.1) | no SMTP at all |
| Production deploy | any | `SmtpMailSender` | real SMTP via `SPRING_MAIL_HOST`/`PORT`, auth via `SPRING_MAIL_USERNAME`/`PASSWORD` when introduced — add to `.env.example` at that time (C19) |

The confirmation link itself is built per D-01 from `FRONTEND_BASE_URL`
(`<base>/verify-email?token=…`); the token appears only in that link, never in a log.

### Rejected alternatives

- **Provider SDKs (SendGrid/SES/Mailgun)** — C1/C2: every provider speaks SMTP; a
  provider SDK would add a dependency without a requirement behind it.
- **Injecting `JavaMailSender` directly into services** — fails the seam tests
  require (plan §6.1 mocks the port, not a mail framework) and leaks infrastructure
  types into the domain (C7).
- **Durable outbox table / message broker** — C1: at MVP scale an in-process retry
  plus user-initiated resend covers FR-01/FR-02; a queue is a second database-sized
  decision nothing requires.

## Consequences

- AU-08 implements the three types above; AU-09 adds `@Async` dispatch and the
  3-attempt scheduled retry; the register/confirm/resend services (AU-11..AU-14)
  depend on `MailSender` only and stay unit-testable without Spring mail.
- Host-run `dev` cannot deliver mail against the compose Mailpit (its `:1025` is not
  published, C27) — delivery failures retry, then log, while registration still
  succeeds (LC-18 by construction). The demo/walkthrough path (WR-02) runs the full
  compose stack where `auth-service → mailpit:1025` works and the inbox is visible
  at `http://localhost:8025`.
- Mailpit is unauthenticated and dev-only — its safety relies entirely on staying
  network-internal per C27: **never publish `:1025` to a host or the internet**. A
  production deploy must override the `SPRING_MAIL_*` env trio toward a real SMTP
  provider (this ADR adds no code — only configuration values change).
- `.env.example` documents `SPRING_MAIL_HOST`/`SPRING_MAIL_PORT`; username/password
  placeholders are added when a credentialed provider is actually configured, keeping
  the file in sync with reality (AGENTS.md §12).
- FR-01/FR-02 acceptance is now reviewable in docs before AU-08 writes code; the
  plan §7.4 finish criterion "ADR-001…ADR-005 exist" is one step closer (ADR-003/
  004/005 land with CA-01/OR-01/OR-02).

## Deviations

Recorded because the wording sits in documents that rank **above** this ADR in the
constitution's precedence order (C6 — no silent reinterpretation):

- **AGENTS.md §2, Mail row** — "dev profile → Mailpit container" remains literally
  true (the `dev` profile points at Mailpit's SMTP), but C27's port-publish policy
  makes that target unreachable from a **host-run** process: `mvnw spring-boot:run`
  + compose means mail fails after its 3 retries (harmless per LC-18). This ADR
  resolves the tension by scoping reliable delivery to the compose stack (the
  demo/WR-02 path) rather than relaxing C27 to publish `:1025`. No edit to
  AGENTS.md is proposed; if a future change wants host-run dev mail, it must
  explicitly amend C27, not quietly expose the port.

## References

- Spec 001: FR-01, FR-02, LC-18; out-of-reach list (no other emails)
- Plan 001: §1 (mail/ package), D-01 (confirmation link), D-02 (this decision),
  §6.1 (unit tests against the port)
- AGENTS.md §2 (Mail row: starter-mail behind port, Mailpit dev), §5 (C24 logging)
- `docs/constitution.md`: C1, C2, C7, C19, C24, C27
- `docker-compose.yml` (mailpit service, C27 port policy), `.env.example` (§SMTP),
  `backend/auth-service/pom.xml` + `application.yml` (`spring.mail.*`)
