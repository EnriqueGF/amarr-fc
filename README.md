# amarr-fc

Production-oriented aMule connector for Sonarr, based on
[vexdev/amarr](https://github.com/vexdev/amarr).

It exposes:

- a Torznab TV indexer backed by Kad/eD2k searches;
- the subset of the qBittorrent Web API used by Sonarr;
- liveness and readiness health checks;
- persistent download/category ownership in SQLite.

The original project and this derivative are distributed under the MIT
license. See [LICENSE.md](LICENSE.md).

## Sonarr

Add a Torznab indexer:

```text
URL: http://amarr-fc:8080/indexer/amule/api
API key: AMARR_API_KEY
RSS: disabled
Automatic Search: initially disabled
Interactive Search: enabled
```

Add a qBittorrent download client:

```text
Host: amarr-fc
Port: 8080
Username: AMARR_QBIT_USERNAME
Password: AMARR_QBIT_PASSWORD
Category: sonarr
```

Sonarr must mount the completed directory at `/data/amule/complete`.

## Development

```bash
./gradlew test
docker build -t amarr-fc:local .
```

Deployment files and configuration examples live in [`deploy/`](deploy/).
The complete runtime design is documented in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
