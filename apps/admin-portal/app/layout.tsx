import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Nectrix Admin Portal",
  description: "Nectrix — Admin/Support/Master portal (separate deployable from the follower app)",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
