// dns-shim.js —— Android App 沙箱 DNS 兜底（随 runtime 内置，NODE_OPTIONS --require 注入）。
//
// 背景：App 私有目录里拉起的 node 进程，其 getaddrinfo 走 bionic → netd 解析，
// 实测在部分 ROM/网络栈下直接返回 EAI_NONAME（ENOTFOUND），导致 pi 所有 HTTPS
// 请求失败（"Connection error."）。Termux 里正常是因为 Termux 有 netd 绑定。
//
// 策略：钩住 dns.lookup（node 内所有 fetch/net/tls 域名解析的唯一入口），
// 先走原解析；失败则用 dns.resolve 直连内置公共 DNS（UDP 53）逐级兜底。
const dns = require('dns');

const FALLBACK_SERVERS = ['223.5.5.5', '119.29.29.29', '8.8.8.8'];
let resolver = null;

function getResolver() {
  if (!resolver) {
    resolver = new dns.Resolver({ timeout: 3000, tries: 1 });
    resolver.setServers(FALLBACK_SERVERS);
  }
  return resolver;
}

function resolveFallback(hostname, family) {
  return new Promise((resolve, reject) => {
    const r = getResolver();
    const done = (err, addrs) => {
      if (err) return reject(err);
      if (!addrs || addrs.length === 0) return reject(new dns.DnsException(hostname, dns.NOTFOUND));
      if (family === 0) {
        // 混合返回：优先 A，附 AAAA
        const a4 = addrs.filter((x) => typeof x === 'string');
        const a6 = addrs.filter((x) => typeof x !== 'string').map((x) => x.address);
        resolve([...a4, ...a6].map((address) => ({ address, family: address.includes(':') ? 6 : 4 })));
      } else {
        const picked = addrs
          .map((x) => (typeof x === 'string' ? x : x.address))
          .filter((ip) => (family === 6) === ip.includes(':'));
        if (picked.length === 0) return reject(new dns.DnsException(hostname, dns.NOTFOUND));
        resolve(picked.map((address) => ({ address, family })));
      }
    };
    if (family === 4) r.resolve4(hostname, done);
    else if (family === 6) r.resolve6(hostname, done);
    else {
      r.resolve4(hostname, (err4, a4) => {
        r.resolve6(hostname, (err6, a6) => {
          const merged = [...(err4 ? [] : a4 || []), ...(err6 ? [] : a6 || [])];
          if (merged.length === 0) return done(err4 || err6 || new dns.DnsException(hostname, dns.NOTFOUND));
          done(null, merged);
        });
      });
    }
  });
}

const origLookup = dns.lookup;
dns.lookup = function patchedLookup(hostname, options, callback) {
  if (typeof options === 'function') {
    callback = options;
    options = {};
  }
  options = options || {};
  const family = typeof options === 'number' ? options : options.family || 0;
  return origLookup.call(this, hostname, options, (err, address, fam) => {
    if (!err) return callback(null, address, fam);
    if (err.code !== 'ENOTFOUND' && err.code !== 'EAI_AGAIN' && err.code !== 'EAI_FAIL') {
      return callback(err);
    }
    resolveFallback(hostname, family)
      .then((list) => {
        if (options.all) return callback(null, list);
        callback(null, list[0].address, list[0].family);
      })
      .catch((e2) => callback(err)); // 兜底也失败：返回原始错误
  });
};
dns.lookup.__patched = true;

// 双保险：部分库（如 undici）可能在 require 时解构捕获原 lookup 引用，
// 这里再钩 net/tls.connect，未显式指定 lookup 时注入 patched 版本
const net = require('net');
const tls = require('tls');
function injectLookup(args) {
  if (args.length > 0 && args[args.length - 1] && typeof args[args.length - 1] === 'object' && typeof args[args.length - 1] !== 'function') {
    const opts = args[args.length - 1];
    if (!opts.lookup) opts.lookup = dns.lookup;
  } else if (typeof args[0] === 'string' || typeof args[0] === 'number') {
    // (host, port, ...) 形式无法注入 options，依赖 dns.lookup 补丁兼底
  }
  return args;
}
const origNetConnect = net.connect;
net.connect = net.createConnection = function (...args) {
  return origNetConnect.apply(this, injectLookup(args));
};
const origTlsConnect = tls.connect;
tls.connect = function (...args) {
  return origTlsConnect.apply(this, injectLookup(args));
};

console.error('[dns-shim] installed, fallback servers: ' + FALLBACK_SERVERS.join(','));
