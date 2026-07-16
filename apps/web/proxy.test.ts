// @vitest-environment node
//
// See lib/session.test.ts's own comment: jose's key-material checks don't
// survive jsdom's separate global realm, and this file does no DOM rendering.
import { describe, expect, it, beforeAll } from "vitest";
import { NextRequest } from "next/server";
import { SignJWT } from "jose";
import { proxy } from "./proxy";

const TEST_SECRET_B64 = Buffer.from("c".repeat(32)).toString("base64");

function secretKeyBytes(): Uint8Array {
  const binary = atob(TEST_SECRET_B64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function signValidToken(): Promise<string> {
  return new SignJWT({ email: "follower@example.com", roles: ["FOLLOWER"] })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject("11111111-1111-1111-1111-111111111111")
    .setIssuedAt()
    .setExpirationTime("15m")
    .sign(secretKeyBytes());
}

describe("proxy (TICKET-110 AC6 — no public entry point)", () => {
  beforeAll(() => {
    process.env.JWT_SIGNING_SECRET = TEST_SECRET_B64;
  });

  it("redirects to /login when there is no access_token cookie at all", async () => {
    const request = new NextRequest("http://localhost:3000/broker-accounts/link");
    const response = await proxy(request);
    expect(response.status).toBe(307);
    expect(response.headers.get("location")).toContain("/login");
  });

  it("redirects to /login when the access_token cookie is invalid", async () => {
    const request = new NextRequest("http://localhost:3000/broker-accounts/link", {
      headers: { cookie: "access_token=not-a-real-jwt" },
    });
    const response = await proxy(request);
    expect(response.status).toBe(307);
    expect(response.headers.get("location")).toContain("/login");
  });

  it("passes through with a real, validly-signed access_token cookie", async () => {
    const token = await signValidToken();
    const request = new NextRequest("http://localhost:3000/broker-accounts/link", {
      headers: { cookie: `access_token=${token}` },
    });
    const response = await proxy(request);
    // NextResponse.next() has no redirect Location header and a 200-ish passthrough status.
    expect(response.headers.get("location")).toBeNull();
  });

  it("never gates /login itself, even with no cookie", async () => {
    const request = new NextRequest("http://localhost:3000/login");
    const response = await proxy(request);
    expect(response.headers.get("location")).toBeNull();
  });

  it("never gates /masters (TICKET-112 public discovery), even with no cookie", async () => {
    const request = new NextRequest("http://localhost:3000/masters");
    const response = await proxy(request);
    expect(response.headers.get("location")).toBeNull();
  });

  it("never gates /masters/{id} (public master profile), even with no cookie", async () => {
    const request = new NextRequest("http://localhost:3000/masters/some-master-id");
    const response = await proxy(request);
    expect(response.headers.get("location")).toBeNull();
  });
});
