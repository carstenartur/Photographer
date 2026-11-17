import { lazy, Suspense } from 'react';
import { Routes, Route } from 'react-router-dom';
import AuthProvider from './components/AuthContext';
import { useAuth } from './components/useAuth';
import LoginPage from './pages/LoginPage';
import Navbar from './components/Navbar';
import ErrorBoundary from './components/ErrorBoundary';
import StatusBar from './components/StatusBar';
// Route-based code splitting: each page is loaded on demand to keep the
// initial bundle small. The login screen and shell components stay eager so
// the first paint is fast for unauthenticated users.
const Dashboard = lazy(() => import('./pages/Dashboard'));
const CameraList = lazy(() => import('./pages/CameraList'));
const JobList = lazy(() => import('./pages/JobList'));
const JobDetail = lazy(() => import('./pages/JobDetail'));
const StackGallery = lazy(() => import('./pages/StackGallery'));
const HdrTimelapse = lazy(() => import('./pages/HdrTimelapse'));
const FaceOrbit = lazy(() => import('./pages/FaceOrbit'));
const ObserverDashboard = lazy(() => import('./pages/ObserverDashboard'));
const ObserverEvents = lazy(() => import('./pages/ObserverEvents'));
const ObserverEventDetail = lazy(() => import('./pages/ObserverEventDetail'));
const ObserverGallery = lazy(() => import('./pages/ObserverGallery'));
const ObserverStatistics = lazy(() => import('./pages/ObserverStatistics'));
const ObserverSettings = lazy(() => import('./pages/ObserverSettings'));
const AstroSessions = lazy(() => import('./pages/AstroSessions'));
const FresnelIntegration = lazy(() => import('./pages/FresnelIntegration'));
const PluginManager = lazy(() => import('./pages/PluginManager'));
import './App.css';

function AuthenticatedApp() {
  return (
    <div className="app">
      <Navbar />
      <main className="main-content">
        <ErrorBoundary>
          <Suspense fallback={<div className="loading" role="status" aria-live="polite">Loading…</div>}>
            <Routes>
              <Route path="/" element={<Dashboard />} />
              <Route path="/cameras" element={<CameraList />} />
              <Route path="/jobs" element={<JobList />} />
              <Route path="/jobs/:id" element={<JobDetail />} />
              <Route path="/gallery" element={<StackGallery />} />
              <Route path="/hdr-timelapse" element={<HdrTimelapse />} />
              <Route path="/face-orbit" element={<FaceOrbit />} />
              <Route path="/observer" element={<ObserverDashboard />} />
              <Route path="/observer/events" element={<ObserverEvents />} />
              <Route path="/observer/events/:id" element={<ObserverEventDetail />} />
              <Route path="/observer/gallery" element={<ObserverGallery />} />
              <Route path="/observer/statistics" element={<ObserverStatistics />} />
              <Route path="/observer/settings" element={<ObserverSettings />} />
              <Route path="/astro" element={<AstroSessions />} />
              <Route path="/integrations/fresnel" element={<FresnelIntegration />} />
              <Route path="/plugins" element={<PluginManager />} />
            </Routes>
          </Suspense>
        </ErrorBoundary>
      </main>
      <StatusBar />
    </div>
  );
}

function AppShell() {
  const { authed } = useAuth();
  return authed ? <AuthenticatedApp /> : <LoginPage />;
}

export default function App() {
  return (
    <AuthProvider>
      <AppShell />
    </AuthProvider>
  );
}
