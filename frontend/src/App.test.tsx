import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from './App'

describe('App', () => {
  it('renders the store name as a heading', () => {
    render(<App />)
    expect(
      screen.getByRole('heading', { name: /foley books/i }),
    ).toBeInTheDocument()
  })
})
