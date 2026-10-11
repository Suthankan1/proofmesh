// Standalone schema migration: the application keeps its server-only boundary.
import { Pool } from 'pg';
import { existsSync } from 'node:fs';
if (!process.env.PROOFMESH_AUTH_DB_HOST && existsSync('.env.local')) process.loadEnvFile('.env.local');
for (const name of ['PROOFMESH_AUTH_DB_HOST','PROOFMESH_AUTH_DB_PORT','PROOFMESH_AUTH_DB_NAME','PROOFMESH_AUTH_DB_USER','PROOFMESH_AUTH_DB_PASSWORD']) {
  if (!process.env[name]) throw new Error('Missing auth migration variable: '+name);
}
import { getMigrations } from 'better-auth/db/migration';
const database = new Pool({
  host: process.env.PROOFMESH_AUTH_DB_HOST,
  port: Number(process.env.PROOFMESH_AUTH_DB_PORT),
  database: process.env.PROOFMESH_AUTH_DB_NAME,
  user: process.env.PROOFMESH_AUTH_DB_USER,
  password: process.env.PROOFMESH_AUTH_DB_PASSWORD,
  options: '-c search_path=proofmesh_web_auth',
  max: 1,
});
try {
  const plan = await getMigrations({ database, account: { encryptOAuthTokens: true } });
  await plan.runMigrations();
  console.log('Operator session schema is current.');
} finally { await database.end(); }
