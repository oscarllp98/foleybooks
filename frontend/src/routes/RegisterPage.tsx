import { RegisterForm } from '../features/auth/RegisterForm'

// FE-10: the /register page mounts FE-06's form. The form owns its own
// success state ("check your inbox", LC-01), so the page is layout only —
// no speculative post-registration navigation (FR-01 never logs the user
// in, so there is nothing to redirect for).

export function RegisterPage() {
  return (
    <section className="mx-auto flex w-full max-w-md flex-col py-4">
      <RegisterForm />
    </section>
  )
}
