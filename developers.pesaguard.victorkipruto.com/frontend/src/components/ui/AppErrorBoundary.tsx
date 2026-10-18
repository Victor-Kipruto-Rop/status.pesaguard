import { Component, type ErrorInfo, type ReactNode } from "react";
import { AlertTriangle, RotateCw } from "lucide-react";
import { reportError } from "../../lib/errors";

type AppErrorBoundaryProps = { children: ReactNode };
type AppErrorBoundaryState = { hasError: boolean; retryKey: number };

export class AppErrorBoundary extends Component<AppErrorBoundaryProps, AppErrorBoundaryState> {
  state: AppErrorBoundaryState = { hasError: false, retryKey: 0 };

  static getDerivedStateFromError(): AppErrorBoundaryState {
    return { hasError: true, retryKey: 0 };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    const component = info.componentStack?.match(/^\s*at\s+([^(\s]+)/m)?.[1] ?? "react-render";
    reportError(error, { component });
  }

  private retry = () => {
    this.setState((state) => ({ hasError: false, retryKey: state.retryKey + 1 }));
  }

  render() {
    if (this.state.hasError) {
      return (
        <main className="app-error-screen" role="alert">
          <div className="app-error-card">
            <span className="app-error-icon"><AlertTriangle size={22} aria-hidden="true" /></span>
            <h1>Something went wrong</h1>
            <p>We couldn't load this page correctly. Please try again.</p>
            <button className="button primary" type="button" onClick={this.retry}>
              Try again <RotateCw size={15} aria-hidden="true" />
            </button>
            <a className="button secondary" href="/">Go to dashboard</a>
          </div>
        </main>
      );
    }
    return <div key={this.state.retryKey}>{this.props.children}</div>;
  }
}
