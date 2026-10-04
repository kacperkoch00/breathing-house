import { Link, Navigate, Route, Routes } from 'react-router-dom'
import { AlertsPage } from './AlertsPage'
import { AppShell } from './AppShell'
import { HomeOverview } from './HomeOverview'
import { RoomDetail } from './RoomDetail'
import { RoomActivityView } from './room/RoomActivityView'
import { RoomAirView } from './room/RoomAirView'
import { RoomAlertsView } from './room/RoomAlertsView'
import { SensorDetailPage } from './SensorDetailPage'
import { SensorsPage } from './SensorsPage'
import { SetupPage } from './SetupPage'

function NotFound() {
  return (
    <AppShell footerLeft="Missing">
      <section className="panel p-6">
        <p className="text-base-content/45 mb-2 font-mono text-[11px] tracking-[0.16em] uppercase">
          Missing
        </p>
        <h1 className="text-3xl font-semibold">Page not found</h1>
        <p className="text-base-content/60 mt-2 text-sm">
          That path is not part of the home dashboard.
        </p>
        <Link to="/" className="btn btn-sm mt-4">
          ← Back to overview
        </Link>
      </section>
    </AppShell>
  )
}

function App() {
  return (
    <Routes>
      <Route path="/" element={<HomeOverview />} />
      <Route path="/rooms/:roomId" element={<RoomDetail />}>
        <Route index element={<Navigate to="air" replace />} />
        <Route path="air" element={<RoomAirView />} />
        <Route path="alerts" element={<RoomAlertsView />} />
        <Route path="activity" element={<RoomActivityView />} />
      </Route>
      <Route path="/alerts" element={<AlertsPage />} />
      <Route path="/sensors" element={<SensorsPage />} />
      <Route path="/sensors/:sensorId" element={<SensorDetailPage />} />
      <Route path="/setup" element={<SetupPage />} />
      <Route path="*" element={<NotFound />} />
    </Routes>
  )
}

export default App
