import { notFound } from "next/navigation";
import { ApiError } from "@nectrix/api-client";

/**
 * A Server Component that fetches a specific resource by id must not let a
 * 403 (not-yours, per BrokerAccountService's own IDOR guard) or 404 (doesn't
 * exist) ApiError bubble up as an unhandled exception -- caught live during
 * this ticket's own frontend verification: an unauthorized viewer got Next's
 * generic 500 error page instead of a clean not-found state. Both cases
 * render the same not-found page here, deliberately not distinguishing
 * "forbidden" from "doesn't exist" to the viewer (the API itself already
 * makes that distinction for a legitimate owner).
 */
export async function fetchOrNotFound<T>(promise: Promise<T>): Promise<T> {
  try {
    return await promise;
  } catch (error) {
    if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
      notFound();
    }
    throw error;
  }
}
