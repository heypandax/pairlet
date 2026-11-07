// Run with Node 22.13+: node --test scripts/tests/test_harmony_relay_endpoint.mjs
// Exercises the actual pure ArkTS routing code; this does not replace a DevEco HAP build.
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { test } from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../../harmony/entry/src/main/ets/pocket/net/RelayEndpoint.ets', import.meta.url), 'utf8');
const js = stripTypeScriptTypes(source);
const { RelayEndpoint } = await import(`data:text/javascript;base64,${Buffer.from(js).toString('base64')}`);

test('official persisted addresses and pairing requests reach cn', () => {
  assert.equal(RelayEndpoint.DEFAULT, 'wss://relay.pairlet.cn');
  for (const host of ['pocket.ark-nexus.cc', 'relay.pairlet.org', 'relay.pairlet.cn']) {
    for (const suffix of ['', '/', ':443', ':443/']) {
      assert.equal(RelayEndpoint.webSocketBase(`wss://${host}${suffix}`), 'wss://relay.pairlet.cn');
      assert.equal(RelayEndpoint.httpBase(`wss://${host}${suffix}`), 'https://relay.pairlet.cn');
      assert.equal(RelayEndpoint.httpBase(`https://${host}${suffix}`), 'https://relay.pairlet.cn');
    }
  }
});

test('custom origins and official-looking attacker hosts are not migrated', () => {
  for (const authority of ['my-relay.example', 'pocket.ark-nexus.cc.attacker.example', 'pocket.ark-nexus.cc@attacker.example',
    'pocket.ark-nexus.cc:8443', 'pocket.ark-nexus.cc/tenant',
    'pocket.ark-nexus.cc?tenant=custom', 'pocket.ark-nexus.cc#custom', 'pocket.ark-nexus.cc//']) {
    assert.equal(RelayEndpoint.webSocketBase(`wss://${authority}`), `wss://${authority}`);
    assert.equal(RelayEndpoint.httpBase(`wss://${authority}`), `https://${authority}`);
    assert.equal(RelayEndpoint.httpBase(`https://${authority}`), `https://${authority}`);
  }
  assert.equal(RelayEndpoint.webSocketBase('ws://127.0.0.1:9000'), 'ws://127.0.0.1:9000');
  assert.equal(RelayEndpoint.httpBase('ws://127.0.0.1:9000'), 'http://127.0.0.1:9000');
});
