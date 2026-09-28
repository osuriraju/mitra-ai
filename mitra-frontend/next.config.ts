import type { NextConfig } from "next";

/* The browser only ever talks to /api/* on the app's own origin; Next proxies it to the Nest API.
   Same-origin means the httpOnly session cookie just works — no CORS or third-party-cookie games. */
const API_URL = process.env.API_URL || "http://localhost:4000";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  /* The service worker must never be served stale, or users get stuck on an old version. */
  async headers() {
    return [
      {
        source: "/sw.js",
        headers: [
          { key: "Cache-Control", value: "no-cache, no-store, must-revalidate" },
          { key: "Service-Worker-Allowed", value: "/" },
        ],
      },
      { source: "/manifest.webmanifest", headers: [{ key: "Content-Type", value: "application/manifest+json" }] },
    ];
  },
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${API_URL}/api/:path*` }];
  },
};

export default nextConfig;
