#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
node --test tests/auth-runtime.test.mjs
npm exec -- tsc --ignoreConfig --noEmit --target es2023 --module esnext \
  --moduleResolution bundler --jsx react-jsx --skipLibCheck --types vite/client \
  --noUnusedLocals --noUnusedParameters \
  src/store/authStore.ts src/lib/api.ts src/features/auth/authApi.ts \
  src/features/auth/LoginPage.tsx src/features/auth/RegisterPage.tsx \
  src/components/ProtectedRoute.tsx src/components/AuthRuntime.tsx \
  src/components/layout/AppLayout.tsx src/features/dashboard/DashboardPage.tsx
npm exec -- oxlint src/App.tsx src/main.tsx src/store/authStore.ts src/lib/api.ts \
  src/features/auth/authApi.ts src/features/auth/LoginPage.tsx \
  src/components/ProtectedRoute.tsx src/components/AuthRuntime.tsx \
  src/components/layout/Sidebar.tsx src/components/layout/TopNavbar.tsx \
  src/features/dashboard/DashboardPage.tsx src/dummyIcon.ts vite.config.ts \
  tests/auth-runtime.test.mjs tests/fixture-server.mjs
# Bundling only; full npm run build has a separate cross-domain typecheck gate.
npm exec -- vite build
