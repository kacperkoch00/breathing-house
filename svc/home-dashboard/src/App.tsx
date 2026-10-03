import { Link, Route, Routes } from 'react-router-dom'
import { HomeOverview } from './HomeOverview'
import { RoomDetail } from './RoomDetail'

function NotFound() {
  return (
    <main className="shell">
      <section className="detail-hero">
        <p className="eyebrow">Missing</p>
        <h1>Page not found</h1>
        <p className="intro">That path is not part of the home dashboard.</p>
        <p className="banner">
          <Link className="back-link" to="/">
            ← Back to overview
          </Link>
        </p>
      </section>
    </main>
  )
}

function App() {
  return (
    <Routes>
      <Route path="/" element={<HomeOverview />} />
      <Route path="/rooms/:roomId" element={<RoomDetail />} />
      <Route path="*" element={<NotFound />} />
    </Routes>
  )
}

export default App
