/** 与 protocol/RelayEndpoint.kt 同步：只改网络目的地，不改持久化配对身份。 */
export class RelayEndpoint {
  static readonly DEFAULT: string = 'wss://relay.pairlet.cn';

  static webSocketBase(configured: string): string {
    const base = configured.endsWith('/') ? configured.substring(0, configured.length - 1) : configured;
    if (base === 'wss://pocket.ark-nexus.cc' || base === 'wss://pocket.ark-nexus.cc:443' ||
      base === 'wss://relay.pairlet.org' || base === 'wss://relay.pairlet.org:443' ||
      base === 'wss://relay.pairlet.cn' || base === 'wss://relay.pairlet.cn:443') {
      return RelayEndpoint.DEFAULT;
    }
    // Preserve custom ports, paths and query parameters.
    return configured;
  }

  static httpBase(configured: string): string {
    const ws = configured.startsWith('https://') ? 'wss://' + configured.substring(8) : configured;
    const base = RelayEndpoint.webSocketBase(ws);
    if (base.startsWith('wss://')) return 'https://' + base.substring(6);
    if (base.startsWith('ws://')) return 'http://' + base.substring(5);
    return base;
  }
}
