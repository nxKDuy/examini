#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
node --test tests/auth-runtime.test.mjs tests/ui-logic.test.mjs tests/zone.test.mjs tests/calendar-model.test.mjs tests/schedule-model.test.mjs tests/notification-model.test.mjs tests/push-auth.test.mjs tests/grading-model.test.mjs tests/grading-flow.test.mjs tests/org-model.test.mjs tests/participant-model.test.mjs tests/bank-model.test.mjs tests/navigation.test.mjs tests/analytics-model.test.mjs tests/copy-sync-model.test.mjs tests/integrity-model.test.mjs
npm exec -- tsc --ignoreConfig --noEmit --target es2023 --module esnext \
  --moduleResolution bundler --jsx react-jsx --skipLibCheck --types vite/client \
  --noUnusedLocals --noUnusedParameters \
  src/store/authStore.ts src/lib/api.ts src/features/auth/authApi.ts \
  src/features/auth/LoginPage.tsx src/features/auth/RegisterPage.tsx \
  src/components/ProtectedRoute.tsx src/components/AuthRuntime.tsx \
  src/components/layout/AppLayout.tsx src/components/layout/navigation.ts src/lib/errors.ts
npm exec -- oxlint --deny-warnings src vite.config.ts tests/auth-runtime.test.mjs tests/ui-logic.test.mjs tests/zone.test.mjs tests/calendar-model.test.mjs tests/schedule-model.test.mjs tests/notification-model.test.mjs tests/push-auth.test.mjs tests/grading-model.test.mjs tests/grading-flow.test.mjs tests/org-model.test.mjs tests/participant-model.test.mjs tests/bank-model.test.mjs tests/navigation.test.mjs tests/fixture-server.mjs tests/bank-fixture.mjs
# Bundling check; the full app typecheck runs in `npm run build` (tsc -b).
npm exec -- vite build
