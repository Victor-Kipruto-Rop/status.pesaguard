const ignoredInputTypes = new Set(["button", "submit", "reset", "image", "hidden"]);

function formHasChanges(form: HTMLFormElement): boolean {
  if (form.hasAttribute("data-pesaguard-no-unsaved-warning")) return false;

  for (const control of Array.from(form.elements)) {
    if (control instanceof HTMLInputElement) {
      if (control.disabled || ignoredInputTypes.has(control.type)) continue;
      if (control.type === "file" ? control.files?.length : control.type === "checkbox" || control.type === "radio"
        ? control.checked !== control.defaultChecked
        : control.value !== control.defaultValue) return true;
    } else if (control instanceof HTMLTextAreaElement) {
      if (!control.disabled && control.value !== control.defaultValue) return true;
    } else if (control instanceof HTMLSelectElement && !control.disabled) {
      if (Array.from(control.options).some((option) => option.selected !== option.defaultSelected)) return true;
    }
  }
  return false;
}

export function hasUnsavedFormChanges(): boolean {
  return Array.from(document.forms).some(formHasChanges);
}

export function confirmLeavingUnsavedForm(): boolean {
  return !hasUnsavedFormChanges()
    || window.confirm("You have unsaved changes. Leave this page and discard them?");
}

export function installUnsavedChangesWarning(): () => void {
  function warnBeforeUnload(event: BeforeUnloadEvent) {
    if (!hasUnsavedFormChanges()) return;
    event.preventDefault();
    event.returnValue = "";
  }
  window.addEventListener("beforeunload", warnBeforeUnload);
  return () => window.removeEventListener("beforeunload", warnBeforeUnload);
}
