import { Pool } from "pg";

import { serverEnv } from "@/lib/config/server-env";

const globalForAuthDatabase = globalThis as typeof globalThis & {
  proofMeshAuthDatabase?: Pool;
};

function createAuthDatabase(): Pool {
  return new Pool({
    host: serverEnv.authDatabase.host,
    port: serverEnv.authDatabase.port,
    database: serverEnv.authDatabase.database,
    user: serverEnv.authDatabase.user,
    password: serverEnv.authDatabase.password,

    options: "-c search_path=proofmesh_web_auth",

    max: 5,
  });
}

export const authDatabase =
  globalForAuthDatabase.proofMeshAuthDatabase ??
  createAuthDatabase();

if (process.env.NODE_ENV !== "production") {
  globalForAuthDatabase.proofMeshAuthDatabase =
    authDatabase;
}