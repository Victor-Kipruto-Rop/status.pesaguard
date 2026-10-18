import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import { AuthProvider } from "../context/AuthContext";
import { NetworkStatusProvider } from "../context/NetworkStatusContext";
import { AppErrorBoundary } from "../components/ui/AppErrorBoundary";
import { NetworkStatusBanner } from "../components/ui/NetworkStatusBanner";
import { ToastProvider } from "../components/ui/ToastProvider";
import { installGlobalErrorHandlers } from "../lib/errors";
import "../styles/globals.css";
import "../styles/brand-typography.css";

installGlobalErrorHandlers();

const rootElement = document.getElementById("root");

if (!rootElement) {
  throw new Error("The application root element is missing.");
}

createRoot(rootElement).render(
  <StrictMode>
    <AppErrorBoundary>
      <ToastProvider>
        <NetworkStatusProvider>
          <NetworkStatusBanner />
          <AuthProvider>
            <App />
          </AuthProvider>
        </NetworkStatusProvider>
      </ToastProvider>
    </AppErrorBoundary>
  </StrictMode>,
);
