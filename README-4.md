# NiFi certificate script (`scripts-sil2.sh`)

This script creates all the certificates for NiFi Red, NiFi Black and Prometheus. They are all signed by one shared root CA, so Red, Black and Prometheus trust each other.

## What it makes

| Certificate | Used for |
| --- | --- |
| Shared Root CA | Signs everything below. NiFi trusts this CA. |
| NiFi Red keystore + truststore | NiFi Red HTTPS, and Red to Black traffic |
| NiFi Black keystore + truststore | NiFi Black HTTPS, and Black to Red traffic |
| Prometheus client cert | Lets Prometheus read NiFi's metrics page |

## Before you run it

You need these on your PATH. Git Bash on Windows works.

- `openssl`
- `keytool` (comes with Java)
- `base64`

## How to run

From the `ca-creation` folder:

```bash
./scripts-sil2.sh
```

To keep the password out of the file:

```bash
PASS='<keystore password>' ./scripts-sil2.sh
```

To also copy the NiFi secrets straight into the Kustomize overlay folders:

```bash
RED_SECRET_PATH=../env/sil-direct/red/tls-secret.yaml \
BLACK_SECRET_PATH=../env/sil-direct/black/tls-secret.yaml \
./scripts-sil2.sh
```

To force a brand new root CA (see the warning below):

```bash
./scripts-sil2.sh --new-ca
```

## What you get

Everything goes into `certs/`, one folder per certificate:

```
certs/
├── .gitignore        ignores everything in this folder
├── root-ca/          root-ca.key, root-ca.crt, root-ca.srl
├── red/              red.key, red.crt, red-keystore.p12, red-truststore.p12, tls-secret.yaml
├── black/            black.key, black.crt, black-keystore.p12, black-truststore.p12, tls-secret.yaml
└── prometheus/       prometheus.key, prometheus.crt, nifi-scrape-tls.yaml
```

The YAML files are ready to use:

| File | Kubernetes Secret | Where it goes |
| --- | --- | --- |
| `red/tls-secret.yaml` | `nifi-red-tls-secret` | NiFi Red overlay (replaces the existing file) |
| `black/tls-secret.yaml` | `nifi-black-tls-secret` | NiFi Black overlay (replaces the existing file) |
| `prometheus/nifi-scrape-tls.yaml` | `nifi-scrape-tls` | Prometheus app, namespace `telemetry-stack` |

## Settings

These are at the top of the script. Change them there or pass them as environment variables.

| Setting | Default | What it is |
| --- | --- | --- |
| `PASS` | value in the script | Password for the keystores and truststores |
| `NAMESPACE` | `inspire-silrelease` | Namespace written into `nifi-scrape-tls.yaml` |
| `RED_IP`, `RED_DNS`, `RED_DNS2` | `172.27.3.23`, `nifi-red...`, `nifi-red-0...` | Names and IP in the Red certificate |
| `BLACK_IP`, `BLACK_DNS`, `BLACK_DNS2` | `172.27.3.12`, `nifi-black...`, `nifi-black-0...` | Names and IP in the Black certificate |
| `RED_SECRET_PATH`, `BLACK_SECRET_PATH` | empty | If set, the NiFi secret is copied there too |

The IP or DNS name that anything uses to reach NiFi (browsers, the other NiFi, Prometheus) must be in that NiFi's certificate. If you add a new address, add it here and re-run.

Change `NAMESPACE` in `nifi-scrape-tls.yaml` to `telemetry-stack` before adding it to the Prometheus app, or set `NAMESPACE=telemetry-stack` when you run the script.

## After running

1. Put `red/tls-secret.yaml` and `black/tls-secret.yaml` in the NiFi overlays. Commit and let Argo CD sync.
2. Restart both NiFi pods so they load the new files. Do Red and Black in the same window, or Red to Black traffic stops.
3. Put `prometheus/nifi-scrape-tls.yaml` in the Prometheus app. Commit and let Argo CD sync.
4. First time only: in the NiFi UI on both sides, add the user `CN=prometheus, OU=monitoring` and give it the policy **view the user interface**.
5. Check Prometheus → **Status → Target health** → `nifi-red` is **UP**.

## Things to know

- **The root CA is made once.** On later runs the script reuses `certs/root-ca/`. If that folder is missing, a new CA is made.
- **A new CA means redeploying everything.** If the CA changes, both NiFi secrets and the Prometheus secret must be deployed together. Otherwise nothing trusts anything.
- **Every run makes new Red, Black and Prometheus certificates.** If you run the script, deploy all of them. Don't run it "just to check".
- **Keep `root-ca.key` safe.** Store it in the team vault. If it is lost, the only way to add a new certificate is a new CA and a full redeploy.
- **Don't commit `certs/`.** It holds private keys. The folder has its own `.gitignore`.
- **Certificates last 825 days.** The root CA lasts 10 years. To renew, re-run the script and follow "After running".

## Problems

| What you see | Cause | Fix |
| --- | --- | --- |
| `subject name is expected to be in the format /type0=value0...` | Git Bash changes `/CN=...` into a Windows path | Keep `export MSYS_NO_PATHCONV=1` at the top of the script |
| `root-ca.crt: No such file or directory` | The CA step failed earlier, usually for the reason above | Fix that, delete `certs/`, run again |
| `keytool: command not found` | Java is not on the PATH | Install Java or add its `bin` folder to PATH |
| `base64: invalid option -- 'w'` | macOS `base64` | Run in Git Bash or Linux, or replace `base64 -w0` with `base64 \| tr -d '\n'` |
| NiFi Red and Black stop talking after deploy | Only one side got the new certificates | Deploy both secrets and restart both pods |
