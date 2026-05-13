# Openfire 5.0.4 — Kubernetes Deployment
## (NodePort · MySQL · REST API + Monitoring plugins · Default services)

---

## Directory layout

```
.
├── Dockerfile
├── config/
│   └── openfire.xml.tmpl        rendered at startup; configures MySQL & domain
├── scripts/
│   └── entrypoint.sh            wraps the base image entrypoint
├── plugins/                     ← PUT YOUR JARS HERE (not downloaded by Docker)
│   ├── restAPI.jar
│   └── monitoring.jar
└── k8s/
    ├── 00-namespace-config-secret.yaml
    ├── 01-mysql.yaml
    └── 02-openfire.yaml         Deployment + 4 NodePort Services
```

---

## 1 — Add your plugin JARs

```bash
cp /wherever/restAPI.jar    ./plugins/
cp /wherever/monitoring.jar ./plugins/
```

No download happens inside the Dockerfile.

---

## 2 — Set your values

### `k8s/00-namespace-config-secret.yaml`

| Field | Default | What to change |
|-------|---------|---------------|
| `XMPP_DOMAIN` | `example.com` | Your XMPP domain |
| `XMPP_FQDN` | `xmpp.example.com` | Your server hostname |
| `MYSQL_USER` (secret) | `openfire` | Your DB user |
| `MYSQL_PASSWORD` (secret) | `openfire` | **Use a real password** |
| `REST_API_SECRET` (secret) | `changeme` | **Use a real secret** |

### `k8s/01-mysql.yaml`

Set `MYSQL_ROOT_PASSWORD` and `MYSQL_PASSWORD` to match the values above.

Encode any secret value:
```bash
echo -n 'MyStr0ngPass!' | base64
```

### `k8s/02-openfire.yaml`

Replace the image line:
```yaml
image: your.registry.io/openfire:5.0.4-plugins
```

Adjust `nodePort` values if any clash with existing services in your cluster.

---

## 3 — Build & push the image

```bash
# From the directory that contains the Dockerfile
docker build -t your.registry.io/openfire:5.0.4-plugins .
docker push your.registry.io/openfire:5.0.4-plugins
```

---

## 4 — Deploy

```bash
kubectl apply -f k8s/00-namespace-config-secret.yaml
kubectl apply -f k8s/01-mysql.yaml

# Wait for MySQL to be Ready
kubectl -n openfire rollout status statefulset/mysql

kubectl apply -f k8s/02-openfire.yaml

# Watch Openfire start up (first boot runs schema init + property seeding)
kubectl -n openfire logs -f deployment/openfire
```

---

## 5 — NodePort service map

| Service | Port on Pod | NodePort | Protocol |
|---------|-------------|----------|----------|
| XMPP C2S | 5222 | **30522** | plain / STARTTLS |
| XMPP C2S SSL | 5223 | **30523** | legacy SSL |
| XMPP S2S | 5269 | **30526** | server-to-server |
| BOSH HTTP | 7070 | **30707** | HTTP binding |
| BOSH HTTPS | 7443 | **30744** | HTTPS binding |
| Admin / REST HTTP | 9090 | **30909** | admin console + REST API |
| Admin / REST HTTPS | 9091 | **30910** | admin console + REST API |

Access admin console: `http://<any-node-ip>:30909`

---

## 6 — REST API

All requests need the secret header:
```
Authorization: <REST_API_SECRET>
```

Example — list users (from inside cluster):
```bash
curl -H "Authorization: changeme" \
     http://openfire-admin.openfire.svc.cluster.local:9090/plugins/restapi/v1/users
```

From outside via NodePort:
```bash
curl -H "Authorization: changeme" \
     http://<node-ip>:30909/plugins/restapi/v1/users
```

---

## 7 — Default services created on first boot

| Service | Subdomain |
|---------|-----------|
| Multi-User Chat | `conference.example.com` |
| Publish-Subscribe | `pubsub.example.com` |
| User Search | `search.example.com` |

These are seeded as `ofProperty` rows on first boot (guarded by
`/var/lib/openfire/.init/.props_done`).  Delete that file and restart to
re-seed.

---

## 8 — Monitoring plugin

Access via Admin Console → **Monitoring** tab, or directly:
```
http://<node-ip>:30909/plugin/monitoring/stats.jsp
```
