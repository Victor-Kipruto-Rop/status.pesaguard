import { Check, Circle, ShieldCheck } from "lucide-react";
import { useEffect, useState } from "react";
import { PreviewBadge } from "./PreviewBadge";

export interface ChecklistItem {
  id: string;
  title: string;
  detail: string;
}

interface ChecklistPanelProps {
  title: string;
  description: string;
  items: ChecklistItem[];
  storageKey: string;
  note: string;
}

export function ChecklistPanel({ title, description, items, storageKey, note }: ChecklistPanelProps) {
  const [completed, setCompleted] = useState<string[]>([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    try {
      const saved = window.localStorage.getItem(storageKey);
      if (saved) {
        const parsed: unknown = JSON.parse(saved);
        if (Array.isArray(parsed) && parsed.every((item): item is string => typeof item === "string")) {
          setCompleted(parsed.filter((id) => items.some((item) => item.id === id)));
        }
      }
    } catch (error) {
      console.warn(`Unable to load ${title.toLowerCase()} checklist progress.`);
    } finally {
      setLoaded(true);
    }
  }, [items, storageKey, title]);

  function toggle(id: string) {
    setCompleted((current) => {
      const next = current.includes(id) ? current.filter((item) => item !== id) : [...current, id];
      try {
        window.localStorage.setItem(storageKey, JSON.stringify(next));
      } catch (error) {
        console.warn(`Unable to save ${title.toLowerCase()} checklist progress.`);
      }
      return next;
    });
  }

  const done = completed.length;
  const progress = Math.round((done / items.length) * 100);

  return (
    <section className="panel setup-checklist">
      <div className="panel-heading">
        <div>
          <h2>{title}</h2>
          <p>{description}</p>
        </div>
        <PreviewBadge>Personal tracker</PreviewBadge>
      </div>
      <div className="checklist-meter" aria-label={`${done} of ${items.length} complete`}>
        <span style={{ width: `${progress}%` }} />
      </div>
      <div className="checklist-meter-label">
        <span>{done} of {items.length} complete</span>
        <strong>{loaded ? `${progress}%` : "…"}</strong>
      </div>
      <ul className="setup-checklist-items">
        {items.map((item) => {
          const isDone = completed.includes(item.id);
          return (
            <li key={item.id}>
              <button
                type="button"
                className={`setup-checklist-item${isDone ? " setup-checklist-item--done" : ""}`}
                aria-pressed={isDone}
                onClick={() => toggle(item.id)}
              >
                {isDone ? <Check size={16} aria-hidden="true" /> : <Circle size={16} aria-hidden="true" />}
                <span><strong>{item.title}</strong><small>{item.detail}</small></span>
              </button>
            </li>
          );
        })}
      </ul>
      <p className="checklist-disclaimer"><ShieldCheck size={13} aria-hidden="true" />{note}</p>
    </section>
  );
}
