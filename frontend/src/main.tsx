import '#/styles/index.scss'
import { App } from '#/App'
import { createRoot } from 'react-dom/client'
import { StrictMode } from 'react'

const rootElement = document.getElementById('app')!

if (!rootElement.innerHTML) {
  const root = createRoot(rootElement)
  root.render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
}
