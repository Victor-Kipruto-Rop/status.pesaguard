export function organizationNameValidationMessage(value: string): string | null {
  const name = value.trim();
  const length = [...name].length;
  if (length < 2 || length > 120 || /[\u0000-\u001f\u007f-\u009f]/u.test(name)) {
    return "Enter an organization name between 2 and 120 characters.";
  }
  return null;
}
