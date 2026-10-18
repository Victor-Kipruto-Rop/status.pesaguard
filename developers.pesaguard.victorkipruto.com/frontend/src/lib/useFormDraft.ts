import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Persists an explicitly non-sensitive form value for a short browser-local
 * recovery window. Do not use this for passwords, secrets, codes, or tokens.
 */
export function useFormDraft(key: string, value: string, setValue: (value: string) => void) {
  const [restored, setRestored] = useState(false);
  const [ready, setReady] = useState(false);
  const loaded = useRef(false);

  useEffect(() => {
    if (loaded.current) return;
    loaded.current = true;
    try {
      const stored = window.localStorage.getItem(key);
      if (stored !== null && stored.length > 0) {
        setValue(stored);
        setRestored(true);
      }
    } catch {
      // Private browsing or disabled storage must never stop a form from working.
    } finally {
      setReady(true);
    }
  }, [key, setValue]);

  useEffect(() => {
    if (!ready) return;
    try {
      if (value.length === 0) window.localStorage.removeItem(key);
      else window.localStorage.setItem(key, value);
    } catch {
      // Storage is optional recovery assistance, never form correctness.
    }
  }, [key, ready, value]);

  const clearDraft = useCallback(() => {
    setRestored(false);
    try {
      window.localStorage.removeItem(key);
    } catch {
      // See storage access note above.
    }
  }, [key]);

  return { restored, clearDraft };
}
