import { Component } from 'react';

/**
 * Global error boundary that catches uncaught render errors in any child
 * component and displays a user-friendly fallback instead of a blank screen.
 */
export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }

  componentDidCatch(error, info) {
    // Log to console for developer visibility; replace with a real
    // error-reporting service (e.g. Sentry) when needed.
    console.error('Uncaught render error:', error, info.componentStack);
  }

  render() {
    if (this.state.hasError) {
      return (
        <div style={{ padding: '2rem', textAlign: 'center' }}>
          <h2>Something went wrong</h2>
          <p style={{ color: '#666' }}>
            An unexpected error occurred. Please refresh the page.
          </p>
          {this.state.error && (
            <pre style={{ color: '#c00', fontSize: '0.85rem', textAlign: 'left',
                          display: 'inline-block', maxWidth: '600px' }}>
              {this.state.error.message}
            </pre>
          )}
          <br />
          <button onClick={() => window.location.reload()}>Reload page</button>
        </div>
      );
    }
    return this.props.children;
  }
}
