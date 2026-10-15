// CI-only TLS wire fixture; uses no Android dependency or private WebDAV service.
const fs = require('node:fs');
const https = require('node:https');
const http2 = require('node:http2');

const [keyFile, certFile, readyFile] = process.argv.slice(2);
const tls = { key: fs.readFileSync(keyFile), cert: fs.readFileSync(certFile) };
const expectedAuthorization = `Basic ${Buffer.from('runtime-user:runtime-password').toString('base64')}`;
const log = value => process.stdout.write(`${JSON.stringify(value)}\n`);

function handleRequest(request, response) {
  const chunks = [];
  request.on('data', chunk => chunks.push(chunk));
  request.on('error', error => log({ event: 'requestError', code: error.code, message: error.message }));
  request.on('end', () => {
    const body = Buffer.concat(chunks);
    const rawHeaders = request.rawHeaders.map((value, index, headers) =>
      index % 2 && /^(authorization|cookie)$/i.test(headers[index - 1]) && value !== expectedAuthorization
        ? '<redacted>' : value);
    const wire = {
      method: request.method,
      path: request.url,
      authority: request.headers[':authority'] || request.headers.host || '',
      authorization: request.headers.authorization === expectedAuthorization ? expectedAuthorization
        : request.headers.authorization ? '<redacted>' : '',
      userAgent: request.headers['user-agent'] || '',
      rawHeaders,
      body: body.toString('utf8'),
      bodyBytes: body.length,
      httpVersion: request.httpVersion,
      alpn: request.socket.alpnProtocol || '',
      sni: request.socket.servername || '',
    };
    log({ event: 'request', ...wire });
    const same = '/redirect/same/';
    const cross = '/redirect/cross/';
    let status = 200;
    let reason;
    const responseHeaders = { 'content-type': 'application/json', 'cache-control': 'no-store' };
    if (wire.path.startsWith(same)) {
      status = 307;
      responseHeaders.location = `/dav/${wire.path.slice(same.length)}`;
    } else if (wire.path.startsWith(cross)) {
      status = 302;
      const port = wire.httpVersion === '2.0' ? 19444 : 19443;
      const targetHost = wire.authority.startsWith('localhost:') ? '127.0.0.1' : 'localhost';
      responseHeaders.location = `https://${targetHost}:${port}/signed/hop/${wire.path.slice(cross.length)}`;
    } else if (wire.path.startsWith('/signed/') && request.headers.authorization) {
      status = 400;
      reason = 'multiple authentication mechanisms';
    } else if (wire.path.startsWith('/signed/hop/')) {
      status = 302;
      responseHeaders.location = `/signed/${wire.path.slice('/signed/hop/'.length)}`;
    } else if (wire.path.startsWith('/dav/missing')) {
      status = 404;
      reason = 'missing';
    }
    log({ event: 'response', path: wire.path, status, location: responseHeaders.location, reason });
    response.writeHead(status, responseHeaders);
    response.end(JSON.stringify({ ...wire, ...(reason ? { reason } : {}) }));
  });
}

const servers = [
  [http2.createSecureServer({ ...tls, allowHTTP1: false }, handleRequest), 19443],
  [https.createServer({ ...tls, ALPNProtocols: ['http/1.1'] }, handleRequest), 19444],
];
let listening = 0;
for (const [server, port] of servers) {
  server.on('error', error => { log({ event: 'serverError', port, code: error.code, message: error.message }); process.exitCode = 1; });
  server.on('tlsClientError', error => log({ event: 'tlsClientError', port, code: error.code, message: error.message }));
  server.on('sessionError', error => log({ event: 'sessionError', port, code: error.code, message: error.message }));
  server.on('session', session => {
    session.on('frameError', (type, code, id) => log({ event: 'frameError', port, type, code, id }));
    session.on('stream', stream => stream.on('error', error => log({ event: 'streamError', port, code: error.code, message: error.message })));
  });
  server.listen(port, '127.0.0.1', () => {
    log({ event: 'listening', port });
    if (++listening === servers.length) fs.writeFileSync(readyFile, 'ready\n');
  });
}
