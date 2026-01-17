import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { DashboardLayout } from './layouts/DashboardLayout';
import { DashboardPage } from './pages/DashboardPage';
import { SatellitesPage } from './pages/SatellitesPage';
import { SatelliteDetailPage } from './pages/SatelliteDetailPage';
import { JobsPage } from './pages/JobsPage';
import { JobDetailPage } from './pages/JobDetailPage';
import { DiscoveryPage } from './pages/DiscoveryPage';
import { TenantsPage } from './pages/TenantsPage';
import { WebSocketProvider } from './contexts/WebSocketContext';

function App() {
  return (
    <WebSocketProvider>
      <BrowserRouter>
        <DashboardLayout>
          <Routes>
            <Route path="/" element={<DashboardPage />} />
            <Route path="/satellites" element={<SatellitesPage />} />
            <Route path="/satellites/:id" element={<SatelliteDetailPage />} />
            <Route path="/jobs" element={<JobsPage />} />
            <Route path="/jobs/:id" element={<JobDetailPage />} />
            <Route path="/discovery" element={<DiscoveryPage />} />
            <Route path="/tenants" element={<TenantsPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </DashboardLayout>
      </BrowserRouter>
    </WebSocketProvider>
  );
}

export default App;
