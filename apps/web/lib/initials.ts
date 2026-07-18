// Shared between Sidebar (user block) and any other place that needs a 2-letter avatar fallback.
export function initialsFor(email: string): string {
  const local = email.split("@")[0] ?? "";
  const parts = local.split(/[._-]/).filter(Boolean);
  const initials = parts.length >= 2 ? parts[0]![0] + parts[1]![0] : local.slice(0, 2);
  return initials.toUpperCase();
}
