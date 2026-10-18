import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

type NetworkStatus = { online: boolean };

const NetworkStatusContext = createContext<NetworkStatus>({ online: true });

/**
 * Browser connectivity is only a hint: an online browser can still be unable to
 * reach the API. It still lets the portal explain a known offline state before a
 * user retries a mutation that cannot be sent.
 */
export function NetworkStatusProvider({ children }: { children: ReactNode }) {
  const [online, setOnline] = useState(() => typeof navigator === "undefined" || navigator.onLine);

  useEffect(() => {
    const markOnline = () => setOnline(true);
    const markOffline = () => setOnline(false);
    window.addEventListener("online", markOnline);
    window.addEventListener("offline", markOffline);
    return () => {
      window.removeEventListener("online", markOnline);
      window.removeEventListener("offline", markOffline);
    };
  }, []);

  const value = useMemo(() => ({ online }), [online]);
  return <NetworkStatusContext.Provider value={value}>{children}</NetworkStatusContext.Provider>;
}

export function useNetworkStatus(): NetworkStatus {
  return useContext(NetworkStatusContext);
}
