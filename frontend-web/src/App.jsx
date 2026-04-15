import { Routes, Route } from 'react-router-dom';
import Navbar from './components/Navbar';
import ErrorBoundary from './components/ErrorBoundary';
import Dashboard from './pages/Dashboard';
import CameraList from './pages/CameraList';
import JobList from './pages/JobList';
import JobDetail from './pages/JobDetail';
import StackGallery from './pages/StackGallery';
import HdrTimelapse from './pages/HdrTimelapse';
import FaceOrbit from './pages/FaceOrbit';
import ObserverDashboard from './pages/ObserverDashboard';
import ObserverEvents from './pages/ObserverEvents';
import ObserverEventDetail from './pages/ObserverEventDetail';
import ObserverGallery from './pages/ObserverGallery';
import ObserverStatistics from './pages/ObserverStatistics';
import ObserverSettings from './pages/ObserverSettings';
import AstroSessions from './pages/AstroSessions';
import PluginManager from './pages/PluginManager';
import './App.css';

export default function App() {
  return (
    <div className="app">
      <Navbar />
      <main className="main-content">
        <ErrorBoundary>
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
            <Route path="/plugins" element={<PluginManager />} />
          </Routes>
        </ErrorBoundary>
      </main>
    </div>
  );
}
