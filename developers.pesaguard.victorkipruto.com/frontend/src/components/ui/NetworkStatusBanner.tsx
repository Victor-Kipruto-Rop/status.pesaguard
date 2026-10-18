import { useEffect, useRef, useState } from "react";
import { Wifi, WifiOff } from "lucide-react";
import { useNetworkStatus } from "../../context/NetworkStatusContext";

/** A persistent reminder while browser connectivity is absent. */
export function NetworkStatusBanner() {
  const { online } = useNetworkStatus();
  const previousOnline = useRef(online);
  const [restored, setRestored] = useState(false);

  useEffect(() => {
    if (!previousOnline.current && online) {
      setRestored(true);
      const timer = window.setTimeout(() => setRestored(false), 4000);
      previousOnline.current = online;
      return () => window.clearTimeout(timer);
    }
    if (!online) setRestored(false);
    previousOnline.current = online;
  }, [online]);

  if (online && !restored) return null;

  return (
    <div className={`network-status-banner${restored ? " network-status-banner--restored" : ""}`} role="status" aria-live="polite">
      {restored ? <Wifi size={16} aria-hidden="true" /> : <WifiOff size={16} aria-hidden="true" />}
      <span>{restored ? "Connection restored." : "You’re offline. Some features may be unavailable until your connection returns."}</span>
    </div>
  );
}
