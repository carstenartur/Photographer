import { Routes, Route } from 'react-router-dom';
import Navbar from './components/Navbar';
import Dashboard from './pages/Dashboard';
import CameraList from './pages/CameraList';
import JobList from './pages/JobList';
import JobDetail from './pages/JobDetail';
import StackGallery from './pages/StackGallery';
import HdrTimelapse from './pages/HdrTimelapse';
import FaceOrbit from './pages/FaceOrbit';
import './App.css';

export default function App() {
  return (
    <div className="app">
      <Navbar />
      <main className="main-content">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/cameras" element={<CameraList />} />
          <Route path="/jobs" element={<JobList />} />
          <Route path="/jobs/:id" element={<JobDetail />} />
          <Route path="/gallery" element={<StackGallery />} />
          <Route path="/hdr-timelapse" element={<HdrTimelapse />} />
          <Route path="/face-orbit" element={<FaceOrbit />} />
        </Routes>
      </main>
    </div>
  );
}
