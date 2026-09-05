const spaces = [
  { name: 'Living room', value: '21.4°C', detail: 'Humidity 44%', tone: 'cool' },
  { name: 'Bedroom', value: '19.8°C', detail: 'Humidity 48%', tone: 'quiet' },
  { name: 'Kitchen', value: '22.1°C', detail: 'Humidity 41%', tone: 'warm' },
]

function App() {
  return (
    <main className="shell">
      <nav className="topbar" aria-label="Main navigation">
        <a className="brand" href="/">
          <span className="brand-mark" aria-hidden="true">BH</span>
          <span>Breathing House</span>
        </a>
        <span className="status"><span className="status-dot" />All systems calm</span>
      </nav>

      <section className="hero">
        <div>
          <p className="eyebrow">Home overview / 09:42</p>
          <h1>A quieter read<br />of your home.</h1>
          <p className="intro">The air is settled across the house. Your latest readings are ready when you are.</p>
        </div>
        <div className="hero-readout">
          <span className="readout-label">Outside now</span>
          <strong>16.7°C</strong>
          <span>Light rain · 82% humidity</span>
        </div>
      </section>

      <section className="overview" aria-labelledby="overview-heading">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Live spaces</p>
            <h2 id="overview-heading">Room conditions</h2>
          </div>
          <span className="updated">Updated just now</span>
        </div>
        <div className="space-grid">
          {spaces.map((space) => (
            <article className={`space-card ${space.tone}`} key={space.name}>
              <div className="card-topline"><span className="pulse" />Stable</div>
              <h3>{space.name}</h3>
              <strong>{space.value}</strong>
              <p>{space.detail}</p>
            </article>
          ))}
        </div>
      </section>

      <footer>
        <span>Environment monitor is online</span>
        <span>Breathing House · v0.1</span>
      </footer>
    </main>
  )
}

export default App
