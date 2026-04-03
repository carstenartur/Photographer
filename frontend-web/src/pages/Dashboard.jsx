import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getAllCameras } from '../api/cameraApi';
import { getAllJobs } from '../api/jobApi';

export default function Dashboard() {
  const [cameras, setCameras] = useState([]);
  const [jobs, setJobs] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    Promise.allSettled([getAllCameras(), getAllJobs()]).then(
      ([camResult, jobResult]) => {
        if (camResult.status === 'fulfilled') setCameras(camResult.value || []);
        if (jobResult.status === 'fulfilled') setJobs(jobResult.value || []);
        if (
          camResult.status === 'rejected' &&
          jobResult.status === 'rejected'
        ) {
          setError('Unable to connect to backend services.');
        }
      },
    );
  }, []);

  const recentJobs = [...jobs]
    .sort((a, b) => new Date(b.created_at || 0) - new Date(a.created_at || 0))
    .slice(0, 5);

  return (
    <div className="page">
      <h1>Dashboard</h1>

      {error && <p className="error">{error}</p>}

      <section className="status-summary">
        <h2>System Status</h2>
        <div className="status-cards">
          <div className="card">
            <h3>Cameras</h3>
            <p className="card-value">{cameras.length}</p>
            <p className="card-label">profiles configured</p>
          </div>
          <div className="card">
            <h3>Jobs</h3>
            <p className="card-value">{jobs.length}</p>
            <p className="card-label">total</p>
          </div>
          <div className="card">
            <h3>Active</h3>
            <p className="card-value">
              {jobs.filter((j) => j.status === 'running').length}
            </p>
            <p className="card-label">jobs running</p>
          </div>
        </div>
      </section>

      <section className="quick-actions">
        <h2>Quick Actions</h2>
        <div className="action-buttons">
          <Link to="/jobs?new=1" className="btn btn-primary">
            Create Job
          </Link>
          <Link to="/cameras?new=1" className="btn btn-secondary">
            Add Camera
          </Link>
        </div>
      </section>

      <section className="recent-jobs">
        <h2>Recent Jobs</h2>
        {recentJobs.length === 0 ? (
          <p className="empty">No jobs yet.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Camera</th>
                <th>Status</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {recentJobs.map((job) => (
                <tr key={job.id}>
                  <td>
                    <Link to={`/jobs/${job.id}`}>{job.id}</Link>
                  </td>
                  <td>{job.camera_id || job.camera_name || '—'}</td>
                  <td>
                    <span className={`badge badge-${job.status || 'unknown'}`}>
                      {job.status || 'unknown'}
                    </span>
                  </td>
                  <td>
                    {job.created_at
                      ? new Date(job.created_at).toLocaleString()
                      : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
