import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Port 3000 matches java-service's default FRONTEND_URL, which is where the
// GitHub OAuth callback redirects to (/auth/callback?code=...).
export default defineConfig({
  plugins: [react()],
  server: { port: 3000, strictPort: true },
});
