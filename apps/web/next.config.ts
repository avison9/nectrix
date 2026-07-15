import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // TICKET-110 — minimal, self-contained runtime output for the Dockerfile's
  // runtime stage (copies .next/standalone rather than node_modules + build
  // output wholesale), same as apps/admin-portal/next.config.ts.
  output: "standalone",
};

export default nextConfig;
