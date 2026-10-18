export const USERNAME_INVALID_MESSAGE =
  "Username must be 6 to 25 lowercase letters (a-z) and cannot be an email address.";

export function usernameValidationMessage(username: string, email = ""): string | null {
  if (!username.trim()) {
    return "Enter a username to create your account.";
  }

  if (email.trim() && username.toLowerCase() === email.trim().toLowerCase()) {
    return "Use a username, not your email address.";
  }

  return /^[a-z]{6,25}$/.test(username) ? null : USERNAME_INVALID_MESSAGE;
}
