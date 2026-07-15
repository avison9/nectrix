// @vitest-environment node
//
// jsdom's global realm has its own Uint8Array distinct from Node's -- jose's
// internal `instanceof Uint8Array` key checks fail across that realm
// boundary. This file does no DOM rendering, so it opts back into the plain
// Node environment rather than jsdom's (only components/*.test.tsx need jsdom).
import { describe, expect, it, beforeAll } from "vitest";
import { SignJWT } from "jose";
import { verifyAccessToken } from "./session";

// A real, valid base64-encoded 256-bit secret -- the exact same shape
// apps/core-app's JwtService requires (>= 32 decoded bytes).
const TEST_SECRET_B64 = Buffer.from("a".repeat(32)).toString("base64");

function secretKeyBytes(): Uint8Array {
  const binary = atob(TEST_SECRET_B64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function signToken(claims: Record<string, unknown>, expiresInSeconds = 900): Promise<string> {
  return new SignJWT(claims)
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(String(claims.sub ?? "user-1"))
    .setIssuedAt()
    .setExpirationTime(Math.floor(Date.now() / 1000) + expiresInSeconds)
    .sign(secretKeyBytes());
}

describe("verifyAccessToken", () => {
  beforeAll(() => {
    process.env.JWT_SIGNING_SECRET = TEST_SECRET_B64;
  });

  it("returns the decoded session for a real, validly-signed token", async () => {
    const token = await signToken({
      sub: "11111111-1111-1111-1111-111111111111",
      email: "follower@example.com",
      roles: ["FOLLOWER"],
      two_factor_enabled: true,
    });

    const session = await verifyAccessToken(token);

    expect(session).not.toBeNull();
    expect(session?.userId).toBe("11111111-1111-1111-1111-111111111111");
    expect(session?.email).toBe("follower@example.com");
    expect(session?.roles).toEqual(["FOLLOWER"]);
    expect(session?.twoFactorEnabled).toBe(true);
  });

  it("returns null for a token signed with the wrong secret", async () => {
    const wrongSecret = Buffer.from("b".repeat(32)).toString("base64");
    const binary = atob(wrongSecret);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    const token = await new SignJWT({ sub: "x", email: "x@example.com", roles: [] })
      .setProtectedHeader({ alg: "HS256" })
      .setExpirationTime("15m")
      .sign(bytes);

    expect(await verifyAccessToken(token)).toBeNull();
  });

  it("returns null for an expired token", async () => {
    const token = await signToken({ sub: "x", email: "x@example.com", roles: [] }, -60);
    expect(await verifyAccessToken(token)).toBeNull();
  });

  it("returns null for a malformed token", async () => {
    expect(await verifyAccessToken("not-a-real-jwt")).toBeNull();
  });

  it("defaults twoFactorEnabled to false when the claim is absent", async () => {
    const token = await signToken({ sub: "x", email: "x@example.com", roles: [] });
    const session = await verifyAccessToken(token);
    expect(session?.twoFactorEnabled).toBe(false);
  });
});
