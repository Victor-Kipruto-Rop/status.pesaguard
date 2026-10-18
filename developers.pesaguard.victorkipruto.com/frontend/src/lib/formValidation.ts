import type { ApiViolation } from "../types/auth";

export const FIELD_VALIDATION_EVENT = "pesaguard:field-validation";
let activeValidationForm: HTMLFormElement | null = null;

export function withValidationForm<T>(form: HTMLFormElement, callback: () => T): T {
  const previousForm = activeValidationForm;
  activeValidationForm = form;
  try {
    return callback();
  } finally {
    activeValidationForm = previousForm;
  }
}

export function currentValidationForm(): HTMLFormElement | null {
  return activeValidationForm;
}

export function publishFieldViolations(form: HTMLFormElement | null, violations: ApiViolation[]): boolean {
  if (!form || !violations.length) return false;
  const event = new CustomEvent(FIELD_VALIDATION_EVENT, { detail: violations, cancelable: true });
  form.dispatchEvent(event);
  return event.defaultPrevented;
}
