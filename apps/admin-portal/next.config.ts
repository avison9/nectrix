import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // TICKET-012 — minimal, self-contained runtime output for the Dockerfile's
  // runtime stage (copies .next/standalone rather than node_modules + build
  // output wholesale).
  output: "standalone",
};

export default nextConfig;
