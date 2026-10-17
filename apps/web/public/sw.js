// Cache only the shell. Never cache authenticated API responses or file contents.
self.addEventListener('install',event=>{self.skipWaiting()});
self.addEventListener('activate',event=>{event.waitUntil(self.clients.claim())});
self.addEventListener('fetch',event=>{const u=new URL(event.request.url);if(event.request.method!=='GET'||u.origin!==self.location.origin||u.pathname.startsWith('/v1/'))return;event.respondWith(fetch(event.request).then(response=>{if(response.ok){const copy=response.clone();caches.open('hood-shell-v1').then(c=>c.put(event.request,copy))}return response}).catch(()=>caches.match(event.request)))});
