import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Suspense, lazy } from 'react';
import { DashboardLayout } from './layouts/DashboardLayout';
import { WebSocketProvider } from './contexts/WebSocketContext';
import { AuthProvider, useAuth } from './contexts/AuthContext';
import { Activity } from 'lucide-react';

// 路由级代码分割 - 按需加载页面组件
const DashboardPage = lazy(() => import('./pages/DashboardPage').then(m => ({ default: m.DashboardPage })));
const SatellitesPage = lazy(() => import('./pages/SatellitesPage').then(m => ({ default: m.SatellitesPage })));
const SatelliteDetailPage = lazy(() => import('./pages/SatelliteDetailPage').then(m => ({ default: m.SatelliteDetailPage })));
const JobsPage = lazy(() => import('./pages/JobsPage').then(m => ({ default: m.JobsPage })));
const JobDetailPage = lazy(() => import('./pages/JobDetailPage').then(m => ({ default: m.JobDetailPage })));
const DiscoveryPage = lazy(() => import('./pages/DiscoveryPage').then(m => ({ default: m.DiscoveryPage })));
const TenantsPage = lazy(() => import('./pages/TenantsPage').then(m => ({ default: m.TenantsPage })));
const LoginPage = lazy(() => import('./pages/LoginPage').then(m => ({ default: m.LoginPage })));

// 加载中占位组件
const PageLoader = () => (
  <div className="flex items-center justify-center h-64">
    <Activity className="animate-pulse text-cyan-400" size={48} />
  </div>
);

// 路由守卫 - 未登录时重定向到 /login
const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) return <PageLoader />;
  if (!isAuthenticated) return <Navigate to="/login" replace />;

  return <>{children}</>;
};

function AppRoutes() {
  const { isAuthenticated } = useAuth();

  return (
    <Routes>
      {/* 登录页 - 已登录时重定向到首页 */}
      <Route path="/login" element={
        isAuthenticated ? <Navigate to="/" replace /> : <LoginPage />
      } />

      {/* 受保护的应用路由 */}
      <Route path="/*" element={
        <ProtectedRoute>
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
        </ProtectedRoute>
      } />
    </Routes>
  );
}

function App() {
  return (
    <AuthProvider>
      <WebSocketProvider>
        <BrowserRouter>
          <Suspense fallback={<PageLoader />}>
            <AppRoutes />
          </Suspense>
        </BrowserRouter>
      </WebSocketProvider>
    </AuthProvider>
  );
}

export default App;
