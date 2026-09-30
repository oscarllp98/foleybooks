import { type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import App from './App'
import { AuthProvider } from './features/auth/AuthProvider'
import { http, setSessionExpiredHandler } from './lib/http'
import { clearTokens, setTokens } from './lib/tokens'

// FE-10 turned App into the routed shell (AuthProvider > App > BrowserRouter >
// AppRoutes). FE-11 made "/" the live BookList, so these smokes stub the
// gateway adapter like AppRoutes.test does — the composition itself is what
// is pinned here (provider stack boots and the catalog page arrives through
// it); BookList's own states are covered in features/catalog/BookList.test.

const BOOKS_PAGE = {
  content: [
    {
      id: '00000000-0000-0000-0000-00000000cb06',
      title: 'Clean Code',
      author: 'Robert C. Martin',
      isbn: '9780132350884',
      price: 31.99,
      coverUrl: 'https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg',
      availability: 'IN_STOCK',
      stockQuantity: 12,
      category: { id: 'cat-1', name: 'Technology' },
    },
  ],
  page: { totalElements: 1, totalPages: 1, number: 0, size: 20 },
} as const

function stubGateway(): void {
  http.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
    const response: AxiosResponse = {
      status: 200,
      statusText: '',
      headers: {},
      config,
      data: config.url === '/books' ? BOOKS_PAGE : {},
    }
    return response
  }
}

function renderApp(): void {
  render(
    <AuthProvider>
      <App />
    </AuthProvider>,
  )
}

beforeEach(() => {
  setTokens({ accessToken: 'reset', refreshToken: 'reset' })
  clearTokens()
  setSessionExpiredHandler(null)
  localStorage.clear()
  window.history.replaceState(null, '', '/')
  stubGateway()
})

describe('App', () => {
  it('app_whenBooted_rendersHeaderAndCatalogLanding', async () => {
    renderApp()

    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: 'Browse books' }),
    ).toBeInTheDocument()
    expect(await screen.findByText('Clean Code')).toBeInTheDocument()
  })

  it('app_whenSignInLinkClicked_navigatesClientSideToLoginForm', async () => {
    const user = userEvent.setup()
    renderApp()

    await user.click(screen.getByRole('link', { name: 'Sign in' }))

    expect(screen.getByRole('form', { name: 'Sign in' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/login')
  })
})
