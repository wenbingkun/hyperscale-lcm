import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { Suspense, lazy } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
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

const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) return <PageLoader />;
  if (!isAuthenticated) return <Navigate to="/login" replace />;

  return <>{children}</>;
};

// 页面动画包装组件
const PageWrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <motion.div
    initial={{ opacity: 0, y: 20 }}
    animate={{ opacity: 1, y: 0 }}
    exit={{ opacity: 0, y: -20 }}
    transition={{ duration: 0.3, ease: 'easeOut' }}
    className="h-full"
  >
    {children}
  </motion.div>
);

function AppRoutes() {
  const { isAuthenticated } = useAuth();
  const location = useLocation();

  return (
    <Routes>
      {/* 登录页 - 已登录时重定向到首页 */}
      <Route path="/login" element={
        isAuthenticated ? <Navigate to="/" replace /> : <LoginPage />
      } />

      <Route path="/*" element={
        <ProtectedRoute>
          <WebSocketProvider>
          <DashboardLayout>
            <AnimatePresence mode="wait">
              <Routes location={location} key={location.pathname}>
                <Route path="/" element={<PageWrapper><DashboardPage /></PageWrapper>} />
                <Route path="/satellites" element={<PageWrapper><SatellitesPage /></PageWrapper>} />
                <Route path="/satellites/:id" element={<PageWrapper><SatelliteDetailPage /></PageWrapper>} />
                <Route path="/jobs" element={<PageWrapper><JobsPage /></PageWrapper>} />
                <Route path="/jobs/:id" element={<PageWrapper><JobDetailPage /></PageWrapper>} />
                <Route path="/discovery" element={<PageWrapper><DiscoveryPage /></PageWrapper>} />
                <Route path="/tenants" element={<PageWrapper><TenantsPage /></PageWrapper>} />
                <Route path="*" element={<Navigate to="/" replace />} />
              </Routes>
            </AnimatePresence>
          </DashboardLayout>
          </WebSocketProvider>
        </ProtectedRoute>
      } />
    </Routes>
  );
}

function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Suspense fallback={<PageLoader />}>
          <AppRoutes />
        </Suspense>
      </BrowserRouter>
    </AuthProvider>
  );
}

export default App;
