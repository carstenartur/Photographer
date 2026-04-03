import { Routes, Route } from 'react-router-dom';
import Navbar from './components/Navbar';
import Dashboard from './pages/Dashboard';
import CameraList from './pages/CameraList';
import JobList from './pages/JobList';
import JobDetail from './pages/JobDetail';
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
        </Routes>
      </main>
    </div>
  );
}
