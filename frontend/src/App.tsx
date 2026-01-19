import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Suspense, lazy } from 'react';
import { DashboardLayout } from './layouts/DashboardLayout';
import { WebSocketProvider } from './contexts/WebSocketContext';
import { Activity } from 'lucide-react';

// 路由级代码分割 - 按需加载页面组件
const DashboardPage = lazy(() => import('./pages/DashboardPage').then(m => ({ default: m.DashboardPage })));
const SatellitesPage = lazy(() => import('./pages/SatellitesPage').then(m => ({ default: m.SatellitesPage })));
const SatelliteDetailPage = lazy(() => import('./pages/SatelliteDetailPage').then(m => ({ default: m.SatelliteDetailPage })));
const JobsPage = lazy(() => import('./pages/JobsPage').then(m => ({ default: m.JobsPage })));
const JobDetailPage = lazy(() => import('./pages/JobDetailPage').then(m => ({ default: m.JobDetailPage })));
const DiscoveryPage = lazy(() => import('./pages/DiscoveryPage').then(m => ({ default: m.DiscoveryPage })));
const TenantsPage = lazy(() => import('./pages/TenantsPage').then(m => ({ default: m.TenantsPage })));

// 加载中占位组件
const PageLoader = () => (
  <div className="flex items-center justify-center h-64">
    <Activity className="animate-pulse text-cyan-400" size={48} />
  </div>
);

function App() {
  return (
    <WebSocketProvider>
      <BrowserRouter>
        <DashboardLayout>
          <Suspense fallback={<PageLoader />}>
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
          </Suspense>
        </DashboardLayout>
      </BrowserRouter>
    </WebSocketProvider>
  );
}

export default App;
