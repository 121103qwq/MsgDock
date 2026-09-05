import { applyD1Migrations } from "cloudflare:test";
import { env } from "cloudflare:workers";

// Setup files run outside per-test-file storage isolation. The helper is
// idempotent, so applying migrations here is safe when Vitest starts a worker
// more than once.
const migrations = (env as typeof env & { TEST_MIGRATIONS: unknown }).TEST_MIGRATIONS;
const database = (env as typeof env & { DB: D1Database }).DB;
await applyD1Migrations(database, migrations as Parameters<typeof applyD1Migrations>[1]);
