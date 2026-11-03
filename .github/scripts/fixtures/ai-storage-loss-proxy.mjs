import { createServer, request } from 'node:http';

// Fixed loopback-only disposable backend. Bodies/credentials are streamed, never logged.
const allowed = new Set(['/api/v1/ai/review/create.json', '/api/v1/ai/review/confirm.json', '/api/v1/ai/review/get.json']);
let blockedPath = '';
const requests = {}, dropped = {};
const server = createServer((incoming, outgoing) => {
    const path = incoming.url.split('?')[0];
    if (path === '/__qa/meta') {
        outgoing.setHeader('Content-Type', 'application/json');
        outgoing.end(JSON.stringify({ fixture: 'finexy-ai-storage-loss-proxy', backend: '127.0.0.1:18141', requests, dropped }));
        return;
    }
    if (path === '/__qa/control' && incoming.method === 'POST') {
        let body = '';
        incoming.on('data', chunk => { body += chunk; if (body.length > 1024) incoming.destroy(); });
        incoming.on('end', () => {
            try {
                const next = JSON.parse(body).blockedPath;
                if (typeof next !== 'string' || (next && !allowed.has(next))) throw new Error('invalid control');
                blockedPath = next; outgoing.writeHead(204); outgoing.end();
            } catch { outgoing.writeHead(400); outgoing.end(); }
        });
        return;
    }
    requests[path] = (requests[path] || 0) + 1;
    const upstream = request({ hostname: '127.0.0.1', port: 18141, method: incoming.method,
        path: incoming.url, headers: { ...incoming.headers, host: '127.0.0.1:18141' } }, response => {
        if (path === blockedPath && response.statusCode === 200) {
            response.resume();
            response.on('end', () => { dropped[path] = (dropped[path] || 0) + 1; outgoing.destroy(); });
        } else {
            outgoing.writeHead(response.statusCode, response.headers);
            response.pipe(outgoing);
        }
    });
    upstream.on('error', () => { if (!outgoing.headersSent) outgoing.writeHead(502); outgoing.end(); });
    incoming.pipe(upstream);
});
server.listen(18142, '127.0.0.1', () => console.log('Disposable AI storage loss proxy ready on 18142'));
