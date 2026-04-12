// frontend/src/test/msw/server.ts
import { setupServer } from "msw/node";

/**
 * Shared MSW server instance. Started in `vitest.setup.ts` via `server.listen()`.
 *
 * Default handlers (registered at startup) live in `handlers.ts` and cover the
 * "fresh visit, no session" baseline. Per-test overrides use `server.use(...)`.
 */
export const server = setupServer();
