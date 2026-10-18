import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { CheckCircle2, Info, TriangleAlert, X } from "lucide-react";

export type ToastKind = "success" | "error" | "info";

type ToastInput = {
  title: string;
  message?: string;
  kind?: ToastKind;
  duration?: number;
};

type ToastRecord = ToastInput & { id: number; kind: ToastKind };
type ToastContextValue = { showToast: (toast: ToastInput) => void };

const ToastContext = createContext<ToastContextValue | null>(null);
let nextToastId = 0;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastRecord[]>([]);
  const timers = useRef(new Map<number, number>());

  const dismissToast = useCallback((id: number) => {
    const timer = timers.current.get(id);
    if (timer !== undefined) window.clearTimeout(timer);
    timers.current.delete(id);
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const showToast = useCallback((toast: ToastInput) => {
    const id = ++nextToastId;
    const record: ToastRecord = { ...toast, id, kind: toast.kind ?? "info" };
    setToasts((current) => [...current.slice(-3), record]);
    timers.current.set(id, window.setTimeout(() => dismissToast(id), toast.duration ?? 5000));
  }, [dismissToast]);

  useEffect(() => () => {
    for (const timer of timers.current.values()) window.clearTimeout(timer);
    timers.current.clear();
  }, []);

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      <div className="global-toast-region" aria-label="Notifications" aria-live="polite" aria-relevant="additions">
        {toasts.map((toast) => {
          const Icon = toast.kind === "success" ? CheckCircle2 : toast.kind === "error" ? TriangleAlert : Info;
          return (
            <section className={`global-toast global-toast--${toast.kind}`} key={toast.id} role={toast.kind === "error" ? "alert" : "status"}>
              <Icon size={18} aria-hidden="true" />
              <div className="global-toast__copy">
                <strong>{toast.title}</strong>
                {toast.message && <span>{toast.message}</span>}
              </div>
              <button type="button" aria-label="Dismiss notification" onClick={() => dismissToast(toast.id)}>
                <X size={15} aria-hidden="true" />
              </button>
            </section>
          );
        })}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const context = useContext(ToastContext);
  if (!context) throw new Error("useToast must be used within a ToastProvider.");
  return context;
}
