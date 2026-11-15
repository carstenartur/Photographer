import { useState, useEffect, useCallback } from 'react';
import { apiUrl, authHeader, setCredentials, clearCredentials, isAuthenticated as checkAuth } from '../api/auth';
import { AuthContext } from './authContextDef';

export default function AuthProvider({ children }) {
  const [authed, setAuthed] = useState(checkAuth());

  const login = useCallback(async (username, password) => {
    const auth = 'Basic ' + btoa(`${username}:${password}`);
    const res = await fetch(apiUrl('/browser/socket-session'), {
      method: 'POST',
      credentials: 'include',
      headers: { Authorization: auth, 'X-Requested-With': 'XMLHttpRequest' },
    });
    if (res.status === 401 || res.status === 403) {
      throw new Error('Invalid username or password');
    }
    if (res.status !== 204) {
      throw new Error('Unable to verify sign-in with the camera service');
    }
    setCredentials(username, password);
    setAuthed(true);
  }, []);

  const logout = useCallback(async () => {
    try {
      const response = await fetch(apiUrl('/browser/socket-session'), {
        method: 'DELETE',
        credentials: 'include',
        headers: { Authorization: authHeader(), 'X-Requested-With': 'XMLHttpRequest' },
      });
      if (!response.ok) throw new Error('Unable to close the browser session');
    } finally {
      clearCredentials();
      setAuthed(false);
    }
  }, []);

  // Listen for 401 responses from the shared request() helper
  useEffect(() => {
    function onUnauthorized() {
      setAuthed(false);
    }
    window.addEventListener('auth:unauthorized', onUnauthorized);
    return () => window.removeEventListener('auth:unauthorized', onUnauthorized);
  }, []);

  return (
    <AuthContext.Provider value={{ authed, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
