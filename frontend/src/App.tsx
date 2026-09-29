import { BrowserRouter } from 'react-router'
import { AppRoutes } from './routes/AppRoutes'

// FE-10: the app is now the routed shell. AuthProvider stays above the
// router in main.tsx (ADR-012), so Layout's session-expired handler installs
// inside the provider and the chain order holds (children-first effects).

function App() {
  return (
    <BrowserRouter>
      <AppRoutes />
    </BrowserRouter>
  )
}

export default App
