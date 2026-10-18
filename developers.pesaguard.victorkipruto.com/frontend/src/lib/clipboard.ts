export async function copyTextToClipboard(text: string): Promise<void> {
  if (typeof document === "undefined") {
    throw new Error("Clipboard access is unavailable in this environment.");
  }

  if (typeof navigator !== "undefined" && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // Continue with the user-initiated document copy fallback below.
    }
  }

  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.setAttribute("readonly", "");
  textarea.setAttribute("aria-hidden", "true");
  textarea.style.position = "fixed";
  textarea.style.top = "0";
  textarea.style.left = "-9999px";
  textarea.style.opacity = "0";

  let copied = false;
  try {
    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();
    textarea.setSelectionRange(0, textarea.value.length);
    copied = document.execCommand("copy");
  } finally {
    textarea.remove();
  }

  if (!copied) {
    throw new Error("Clipboard access is unavailable. Allow clipboard access or select and copy the value manually.");
  }
}
