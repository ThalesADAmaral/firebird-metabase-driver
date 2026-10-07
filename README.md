# Metabase Firebird 2.5.x Driver

🇧🇷 [Leia em português](README.pt-BR.md)

A [Metabase](https://www.metabase.com/) driver plugin for **Firebird 2.5** (also works with Firebird 3, 4 and 5),
built for current Metabase versions.

The original [evosec/metabase-firebird-driver](https://github.com/evosec/metabase-firebird-driver) stopped working
around Metabase 0.48, when Metabase dropped HoneySQL 1, and that repository was archived in 2025. This driver is a
rewrite against the current driver API (HoneySQL 2, MBQL 5 compilation). Firebird 2.5 is a first-class target, so the
SQL it generates avoids features that only exist in Firebird 3+.

## Compatibility

| Metabase | Firebird | Status |
|---|---|---|
| v0.63.19.1 | 2.5.9 (dialect 3) | ✅ tested, 48/48 automated queries pass |
| v0.61 – v0.62 | — | should work (MBQL 5 compilation was introduced in 0.61), not tested |
| ≤ v0.60 | — | ❌ not supported |

The plugin bundles **Jaybird 5.0.13** (`java11` build), the last Jaybird line that officially supports Firebird 2.5.

> Metabase has no stable public driver API, so a future Metabase release can break any third-party driver, including
> this one. See [Keeping up with Metabase releases](#keeping-up-with-metabase-releases).

## Installation

Metabase loads every `.jar` it finds in its plugins directory at startup. In the official Docker image, that
directory is **`/plugins`** inside the container. Installing the driver comes down to three steps:

1. Put `firebird.metabase-driver.jar` in a folder on the host.
2. Mount that folder at `/plugins` in the Metabase container.
3. Restart Metabase.

> **Remove any older Firebird driver first** (for example evosec's `firebird.metabase-driver_jaybird-3.jar`). Both
> register the `firebird` driver; with the old one loaded you get
> `No method in multimethod 'connection-details->spec' for dispatch value: :firebird`.

### Step 1 – Download the jar to the server

Run these commands on the server that runs Docker:

```bash
sudo mkdir -p /opt/metabase/plugins
sudo curl -fL -o /opt/metabase/plugins/firebird.metabase-driver.jar \
  https://github.com/ThalesADAmaral/firebird-metabase-driver/releases/latest/download/firebird.metabase-driver.jar
ls -l /opt/metabase/plugins
```

You can also [build the jar yourself](#building) and copy it with `scp`:

```bash
scp dist/firebird.metabase-driver.jar root@your-server:/opt/metabase/plugins/
```

### Option A – `docker run`

```bash
docker run -d --name metabase -p 3000:3000 \
  -v /opt/metabase/plugins:/plugins \
  metabase/metabase:v0.63.19.1
```

A volume can't be added to a container that already exists, so the container has to be recreated with `-v`.

> ⚠️ **Before recreating the container, check where Metabase keeps its own data** (dashboards, questions, users).
> With `MB_DB_TYPE=postgres` or `mysql`, the data lives in that database and recreating the container is safe. With
> the embedded H2 database (the default), the data is inside the container unless the H2 file is on a volume. In that
> case, back it up or move to Postgres first, or you will lose your dashboards.

### Option B – Docker Compose

Add the mount to the Metabase service and run `docker compose up -d`:

```yaml
services:
  metabase:
    image: metabase/metabase:v0.63.19.1
    volumes:
      - /opt/metabase/plugins:/plugins
    # ...the rest of your configuration (MB_DB_TYPE, ports, etc.)
```

### Option C – Coolify

Coolify's one-click **Metabase service** is a Docker Compose stack. It stores Metabase's data in a Postgres container
of the same stack, so redeploying is safe. The template does **not** mount `/plugins`, so the mount has to be added to
the compose file.

1. **On the server (SSH):** download the jar as in [Step 1](#step-1--download-the-jar-to-the-server). Any absolute
   path works; this guide uses `/opt/metabase/plugins`.
2. **In Coolify:** open the Metabase service and click **Edit Compose File**.
3. Under the `metabase` service, add the mount to `volumes:`, keeping the line that is already there:
   ```yaml
   services:
     metabase:
       image: metabase/metabase:v0.63.19.1   # recommended: pin a tested version instead of :latest
       volumes:
         - /dev/urandom:/dev/random:ro
         - /opt/metabase/plugins:/plugins
   ```
   Use an **absolute** host path. A relative path such as `./plugins` resolves to Coolify's internal service folder,
   not to the folder where you put the jar.
4. **Save**, then **Restart** (or **Redeploy**) the service.
5. Open the service **Logs** and look for `firebird` (see [Check that it loaded](#check-that-it-loaded)).

If Metabase was deployed as a Coolify **Application** (from a Docker image) instead of a Service, add the mount in the
UI: **Configuration → Persistent Storage → Add → Directory Mount**, with source `/opt/metabase/plugins` and
destination `/plugins`. Then redeploy.

### Quick test without a volume (not persistent)

You can copy the jar into the running container to try the driver. The copy disappears when the container is
recreated, for example on the next Coolify redeploy or Metabase upgrade:

```bash
docker cp firebird.metabase-driver.jar <container>:/plugins/
docker restart <container>
```

### Running the JAR directly (no Docker)

Put the jar in the `plugins/` folder next to `metabase.jar`, or point `MB_PLUGINS_DIR` at the folder that contains it.

### Check that it loaded

```bash
docker ps --format '{{.Names}}\t{{.Image}}'                 # find the container name
docker exec <container> ls -l /plugins                       # the jar must be listed here
docker logs <container> 2>&1 | grep -i firebird
```

Expected log lines:

```
Registering lazy loading driver :firebird...
Registered driver :firebird (parents: [:sql-jdbc])
```

The second line comes from the plugin manifest. When you first connect a database, the driver itself loads and logs
`Registered driver :firebird (parents: [:sql-jdbc :sql-mbql5])`.

**Firebird** then appears in **Admin → Databases → Add database**.

### Updating the driver

Replace the jar in the host folder (same file name) and restart the container (or the Coolify service). No other
step is needed.

### Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `No method in multimethod 'connection-details->spec' for dispatch value: :firebird` | An old driver (evosec) is still in `/plugins`. Remove it and restart. |
| Two Firebird entries in the database list ("FirebirdSQL" and "Firebird") | Same as above: an old driver is still loaded. |
| Firebird is not in the database list | The jar isn't in `/plugins`. Check the mount with `docker exec <container> ls -l /plugins` and the log. |
| Timeout or "connection refused" when saving | Network. From the Docker host, run `nc -zv <firebird-host> <port>`. A Firebird on a private LAN is not reachable from a cloud VPS; use a VPN (Tailscale, WireGuard…) or the SSH tunnel option. |
| Accented characters come out wrong | Change **Connection charset** (`WIN1252`, `ISO8859_1`, `UTF8`…). |
| Firebird runs on a non-default port | Set **Port** to that port. Firebird 2.5 needs no `firebird.conf` changes: it only uses legacy auth and has no wire encryption. |

## Connecting

In Metabase go to **Admin → Databases → Add database → Firebird**.

| Field | Example | Notes |
|---|---|---|
| Host | `10.0.0.5` | Must be reachable from where Metabase runs (from inside the container, when using Docker) |
| Port | `3050` | |
| Database path or alias | `C:/Data/DB.FDB`, `/var/lib/firebird/db.fdb` or `MYALIAS` | Path **on the Firebird server**, or an alias from `aliases.conf` |
| Username / Password | | A read-only user is recommended |
| Connection charset | `WIN1252` | Jaybird connection encoding: `WIN1252`, `ISO8859_1`, `UTF8`, `NONE`, … |
| SSH tunnel | | Useful when Firebird is on a private network |
| Additional JDBC options | `blobBufferSize=16384` | Passed through to Jaybird |

Notes:

- **Charset:** with garbled accented characters, try another value. `WIN1252` is the default because it is the most
  common choice for legacy Latin-language databases.
- **Security:** the Firebird 2.5 wire protocol is not encrypted. Don't expose port 3050 to the internet; use a VPN or
  the SSH tunnel option.
- **Read-only user:** Metabase only reads, but native SQL lets users type anything. Connect with a user that has only
  `SELECT` grants.

## How it works

Firebird 2.5 lacks several features that Metabase's generic SQL generator assumes. The driver translates them:

| Metabase generates | The driver emits for Firebird 2.5 |
|---|---|
| `LIMIT n` / `OFFSET m` | `ROWS n` / `ROWS m TO n` (also inside subqueries) |
| `TRUE` / `FALSE` | `1` / `0` (no BOOLEAN type in 2.5) |
| `date_trunc(...)` | `DATEADD` + `EXTRACT` for minute, hour, day, week, month, quarter, year |
| start of week | honors Metabase's *start of week* setting |
| `concat(a, b)` | `a \|\| b` |
| `substring(x, i, n)` | `SUBSTRING(x FROM i FOR n)` |
| `length`, `ltrim`, `rtrim`, `log` | `CHAR_LENGTH`, `TRIM(LEADING/TRAILING FROM …)`, `LOG10` |
| long generated aliases | truncated to 31 bytes (Firebird 2.5 identifier limit) |
| string literals in `CASE` | cast to `VARCHAR`, so results come back without CHAR padding |
| boolean expressions as columns (`SELECT a > b`) | `CASE WHEN a > b THEN 1 ELSE 0 END` (2.5 only allows predicates in WHERE/CASE) |
| `?` parameters in GUI queries | values are inlined (escaped). Firebird 2.5 can't infer `?` types in SELECT/CASE/COALESCE, and parameterized expressions don't match their GROUP BY counterpart |

Native SQL queries still use real JDBC parameters for `{{variables}}`.

### Limitations

- These features are disabled because Firebird 2.5 lacks them: window functions, standard deviation and variance,
  percentile and median, regex. Metabase still computes *cumulative sum/count* itself.
- Only **dialect 3** databases are supported, because Metabase always quotes identifiers. Dialect 3 is the default for
  databases created with Firebird 1.5+.
- Firebird has no schemas, so all tables appear at the top level.

## Building

The plugin ships as Clojure **source**, not AOT-compiled classes. Metabase compiles it when loading the driver, so
building needs neither Java nor Clojure: it just zips the source, the plugin manifest and Jaybird together.

**Windows (PowerShell):**

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

The script downloads Jaybird from Maven Central into `lib/` (first run only) and writes
`dist/firebird.metabase-driver.jar`.

### Project layout

```
src/metabase/driver/firebird.clj   the driver
resources/metabase-plugin.yaml     plugin manifest (name, connection form)
build.ps1                          builds dist/firebird.metabase-driver.jar
testes/                            test database script + query test suite
LICENSE                            MIT
```

## Testing

The suite in `testes/` runs real queries through Metabase's HTTP API against a test Firebird database. It covers
sync, every temporal bucketing unit, relative date filters, joins, pagination, nested queries, expressions,
aggregations, binning and native queries with variables and field filters.

1. Start a disposable Firebird 2.5 server. The official zip kit works without installing:
   `fbserver.exe -a -p 3051`.
2. Create the test database: `isql -i testes/criar-banco-teste.sql`. Edit the path in the first lines first.
3. Start Metabase with `MB_PLUGINS_DIR` pointing at a folder that contains the built jar, and add the test database.
4. Save an admin session id to `testes/sid.txt`.
5. Run the suites:
   ```bash
   python testes/tests.py
   python testes/tests2.py
   ```
   `MB_TEST_DB` sets the Metabase database id (default `2`).

> ⚠️ Never point the test suite at a production database.

## Keeping up with Metabase releases

Third-party drivers run against Metabase **internals**, which change between releases. This driver reduces the risk:

- **No AOT compilation.** Compiled drivers break on any internal class change. This one is recompiled from source
  by each Metabase version.
- **Small surface.** It only overrides the multimethods that Metabase's own bundled drivers (H2, SQL Server, Vertica,
  SQLite) also implement, so upstream keeps those working and documents their changes.
- **Already on MBQL 5.** Metabase is moving SQL compilation to MBQL 5 and requires every driver to migrate by v64.
  This driver already derives from `:sql-mbql5`, like Metabase's own H2 driver, so it is ahead of that deadline.
- **Upstream changelog.** Metabase lists every driver API change in
  [`docs/developers-guide/driver-changelog.md`](https://github.com/metabase/metabase/blob/master/docs/developers-guide/driver-changelog.md).
  Read it before upgrading.

Recommendations:

- Pin your Metabase image to a tested version (`metabase/metabase:v0.63.19.1`, not `:latest`).
- Before upgrading, run the test suite against the new version.

## Contributing

Issues and PRs are welcome, especially reports of Metabase or Firebird versions you've tested. When reporting a
query bug, include the generated SQL (**View the SQL** in the query editor) and the error from the Metabase log.

## Credits

- Developed by **ThalesADAmaral** in collaboration with **Claude** ([Anthropic](https://www.anthropic.com/)).
- Based on ideas from [evosec/metabase-firebird-driver](https://github.com/evosec/metabase-firebird-driver).
- Uses [Jaybird](https://github.com/FirebirdSQL/jaybird), the Firebird JDBC driver, licensed under LGPL-2.1.

## License

[MIT](LICENSE). Anyone may use, modify and redistribute this project, including commercially, as long as the
copyright and credits notice in [LICENSE](LICENSE) is kept. The bundled Jaybird driver keeps its own LGPL-2.1
license.
