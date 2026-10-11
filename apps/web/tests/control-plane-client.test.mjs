import { test } from 'node:test';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const packageRequire = createRequire(import.meta.url);
const __dirname = fileURLToPath(new URL('.', import.meta.url));
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import ts from 'typescript';
function client(fetch) {
  const cache = new Map();
  function load(file) {
    if (cache.has(file)) return cache.get(file);
    const compiledModule = { exports: {} }; cache.set(file, compiledModule.exports);
    const code = ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
    const requireModule = name => {
      if (name === 'server-only') return {};
      if (name === '@/lib/config/server-env') return { serverEnv: { controlPlane: { baseUrl: 'http://localhost:8080' } } };
      if (name.startsWith('@/')) return load(path.resolve(__dirname, '../src', name.slice(2) + '.ts'));
      return packageRequire(name);
    };
    vm.runInNewContext(code, { module: compiledModule, exports: compiledModule.exports, require: requireModule, fetch, URL, AbortSignal, console });
    return compiledModule.exports;
  }
  return load(path.resolve(__dirname, '../src/lib/api/control-plane-client.ts'));
}
test('operator transport rejects redirects and never caches bearer requests', async () => {
  let options;
  const api = client(async (url, init) => { options = init; if (init.redirect !== 'error') return new Response(JSON.stringify({ subject: 'operator', authorities: [] })); throw new TypeError('redirect rejected'); });
  await assert.rejects(api.getIdentity('private-token'), { kind: 'UNAVAILABLE' });
  assert.equal(options.redirect, 'error');
  assert.equal(options.cache, 'no-store');
  assert.equal(options.headers.Authorization, 'Bearer private-token');
});
for (const [status, kind] of [[401, 'UNAUTHORIZED'], [403, 'FORBIDDEN'], [503, 'UPSTREAM_ERROR']]) {
  test(`HTTP ${status} remains a typed failure`, async () => {
    await assert.rejects(client(async () => new Response('secret body', { status })).getIdentity('token'), { kind, status });
  });
}
test('malformed and incompatible responses fail closed', async () => {
  for (const body of ['not json', '{"subject":3,"authorities":[]}']) {
    await assert.rejects(client(async () => new Response(body)).getIdentity('token'), { kind: 'CONTRACT_VIOLATION' });
  }
});
test('valid authority response is parsed', async () => {
  const result = await client(async () => new Response('{"subject":"operator","authorities":["ROLE_VIEWER"]}')).getIdentity('token');
  assert.equal(result.subject, 'operator'); assert.deepEqual(result.authorities, ['ROLE_VIEWER']);
});
