import { useState, useEffect, useCallback } from 'react';
import { setCredentials, clearCredentials, isAuthenticated as checkAuth } from '../api/auth';
import { AuthContext } from './authContextDef';

export default function AuthProvider({ children }) {
  const [authed, setAuthed] = useState(checkAuth());

  const login = useCallback(async (username, password) => {
    // Validate credentials by making a test request
    const auth = 'Basic ' + btoa(`${username}:${password}`);
    const res = await fetch('/api/cameras', {
      headers: { Authorization: auth },
    });
    if (res.status === 401 || res.status === 403) {
      throw new Error('Invalid username or password');
    }
    setCredentials(username, password);
    setAuthed(true);
  }, []);

  const logout = useCallback(() => {
    clearCredentials();
    setAuthed(false);
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
