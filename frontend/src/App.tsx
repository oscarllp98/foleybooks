import { QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router'
import { AppRoutes } from './routes/AppRoutes'
import { queryClient } from './lib/queryClient'

// FE-10: the app is now the routed shell. AuthProvider stays above the
// router in main.tsx (ADR-012), so Layout's session-expired handler installs
// inside the provider and the chain order holds (children-first effects).
// FE-11: the one QueryClientProvider (AGENTS.md §2: TanStack Query owns all
// server state) sits above the router because routes are its consumers.

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </QueryClientProvider>
  )
}

export default App
