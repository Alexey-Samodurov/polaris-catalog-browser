# Catalog Browser for Apache Polaris

[Русский](README.ru.md)

A plugin for PyCharm and other JetBrains IDEs to browse
[Apache Polaris](https://polaris.apache.org/) catalogs from the IDE: catalogs,
nested namespaces, Iceberg tables and views. For a table it shows the schema,
partition spec, sort order, properties, snapshot history, and its rows.

The plugin **only reads**: it never creates, changes or deletes anything in
Polaris or in storage.

- [Install](#install)
- [Connect to Polaris](#connect-to-polaris)
- [The catalog tree](#the-catalog-tree)
- [Table and view details](#table-and-view-details)
- [Table data](#table-data)
- [Security](#security)
- [Troubleshooting](#troubleshooting)
- [Limits](#limits)

## Install

Requires a JetBrains IDE **2026.2 or later** (PyCharm, IntelliJ IDEA, DataGrip, …).

- **From JetBrains Marketplace:** Settings → Plugins → Marketplace, search for
  "Catalog Browser for Apache Polaris", install.
- **From a file:** Settings → Plugins → ⚙ → Install Plugin from Disk…, choose
  the `.zip` from the [releases](../../releases).

## Connect to Polaris

**Settings → Tools → Polaris Catalog Browser**, then **+**.

| Field | What to enter |
| --- | --- |
| Name | Any name, e.g. `prod` |
| Server URL | The Polaris server, e.g. `https://polaris.example.com`. A pasted `/api/catalog` is removed |
| Realm | The Polaris realm, if the server has several; otherwise leave empty |
| Client id / Client secret | A Polaris principal's credentials |
| Scope | `PRINCIPAL_ROLE:ALL`, or `PRINCIPAL_ROLE:<role>` for one role |
| Named catalogs | Comma-separated catalogs, for a principal that may not list catalogs |

**Test Connection** asks for a token; on success it says "Connected: a token
was issued". You can add several connections, e.g. dev, test and prod.

The **Data Preview Storage** section is only needed to read rows; see
[Table data](#table-data).

The plugin uses the IDE's certificate store and proxy settings, including proxy
logins.

## The catalog tree

Open the **Polaris** tool window on the right side of the IDE.

```text
prod                       ← connection
└── bronze                 ← catalog
    └── sales              ← namespace (may be nested)
        ├── orders         ← table
        └── recent  view   ← view
```

- Nodes load when first expanded.
- **Search:** start typing while the tree has focus; it filters the nodes
  already loaded.
- **Double-click or Enter on a table** opens its data in an editor tab, like a
  file in Big Data Tools. Doing it again switches to the open tab.

| Toolbar button | What it does |
| --- | --- |
| ⟳ Refresh | Reloads the selected node, or the whole tree when nothing is selected |
| + Add Catalog by Name | Adds a catalog by name to the selected connection |
| Open Data Preview | Opens the selected table's data in an editor tab |
| Copy Qualified Name | Copies `catalog.namespace.table` as Spark SQL takes it, back-quoting parts that need it |
| ⚙ Settings | Opens the connection settings |

**"Not permitted to list catalogs":** the principal may not see the catalog
list. Select the connection, click **Add Catalog by Name** and type the catalog
name; it is remembered under *Named catalogs*.

**"Client secret not stored: double-click to enter it":** the secret was not
kept, e.g. because the IDE's password safe is set not to remember. Double-click
the message and enter it.

## Table and view details

Selecting a table opens its details below the tree:

| Tab | Contents |
| --- | --- |
| Overview | Metadata and data locations, format version (v1–v3), UUID, current snapshot, last update |
| Schema | Columns with id, name, type, required, identifier, doc and defaults; nested fields by full path |
| Partition spec | Partition fields: transform (`day`, `bucket[16]`, …) and source column |
| Sort order | The write sort order |
| Properties | Table properties; values of keys that look like secrets are shown as `********` |
| Snapshots | Snapshot history, newest first: time, operation, branches and tags, summary |
| Data | The table's rows |

Only the snapshots that branches and tags point at are loaded at first; the full
history loads when the Snapshots tab is opened. **Refresh** on a table checks
whether it changed and reloads it only if it did.

A **view** shows Overview, Schema, SQL (one tab per dialect) and Properties.

## Table data

The plugin reads the **table**, not its Parquet files one by one: deletion
vectors, position deletes and equality deletes are applied, so rows deleted or
replaced by merge-on-read writes never appear. You see what a `SELECT` in Spark
returns.

### Storage access

When a Polaris catalog has STS turned off (`stsUnavailable`), Polaris vends no
storage credentials, and the connection needs S3 keys of its own, in **Data
Preview Storage**:

| Field | What to enter |
| --- | --- |
| S3 access key id | An S3 key (AWS, MinIO, SeaweedFS, …). **Prefer a read-only one** |
| S3 secret access key | Stored in the IDE's password safe, never in settings files |
| S3 endpoint | The S3 address; empty takes the one Polaris returns |
| Addressing | *From Polaris* (default), *Path-style* or *Virtual-host*; MinIO and SeaweedFS often need *Path-style* |
| Region | Rarely needed |

With no key id, the plugin asks Polaris for vended credentials; the principal
then needs `TABLE_READ_DATA`. If Polaris vends none, the plugin says so and
never falls back to keys found in the environment or `~/.aws`.

### Reading

Open a table's **Data** tab, or its editor tab. The first 100 rows load by
themselves.

| Control | What it does |
| --- | --- |
| **At** | The table state: *Current*, a branch or tag, a snapshot from the history, or a snapshot id typed in |
| **Columns (N/M)** | Which columns to read; **All** ticks or clears every one. With all chosen, an older snapshot shows the columns of its own schema |
| Filter rows | One row per partition source column: operator (`=`, `!=`, `<`, `<=`, `>`, `>=`, `in`) and value; empty means no filter; **+** adds another condition on the same column, e.g. for a range |
| **Run** | Reads again with the current settings |
| **Load More** | 100 more rows, up to 1000 in total |
| **Cancel** | Stops the read |
| **Copy** | Copies the selected rows, or all, with the header, tab-separated |

Filter values: a date as `2026-11-02`, a timestamp as `2026-11-02T00:00:00`
(with `+00:00` for `timestamptz`), `in` values comma-separated. A filter on a
partition source column skips files before reading: two conditions on `ts`,
`>= 2026-11-02T00:00:00` and `< 2026-11-03T00:00:00`, read only that day's
files.

A column added after the last write shows as `NULL` at *Current* and on
branches; a tag or an older snapshot reads with its own schema, as in Spark.

The grid shows row numbers, the type of each column under its name, grid lines,
and sizes columns to their contents (very long values are cut; drag to widen).
Numbers are right-aligned and `NULL` is grey and italic. Cells and ranges can be
selected; Ctrl+C copies them.

If the connection has an S3 key id but its secret is not stored, the tab shows
**Enter S3 Secret…**: keep the secret in the password safe or only until the IDE
closes.

## Security

- The Polaris secret and the S3 secret live only in the IDE's password safe (the
  macOS keychain by default); they never appear in settings files, logs or
  error messages.
- Rows are never written to disk; S3 keys stay in memory only while one table's
  preview is open.
- What the S3 key may read in storage, the plugin can show. Do not use keys
  that can write.

## Troubleshooting

| Message | What to do |
| --- | --- |
| `token request refused: invalid_client` | Wrong client id or secret |
| `Not permitted to list catalogs` | Not an error: add catalogs with **Add Catalog by Name** |
| `… 403 …` expanding a namespace | The principal lacks privileges on that namespace or table |
| `Polaris vended no storage credentials … Enter S3 keys` | Fill in **Data Preview Storage** on the connection |
| `S3 refused access (403)` | The S3 key may not read the table's bucket |
| `S3: … NoSuchBucket` / address errors | Check **S3 endpoint** and **Addressing** |
| `The preview reads tables on S3 only` | The table is not on S3 (local files, GCS, Azure); the preview does not support it |
| `TLS failed …` in the Data tab | S3 or Polaris uses an internal certificate authority: add it to the JDK the IDE runs on |
| TLS error in the tree | Accept the certificate in Settings → Tools → Server Certificates |
| `no longer exists` | The table was dropped or renamed: **Refresh** its namespace |

## Limits

- Read-only: nothing can be created, changed or deleted.
- At most 1000 rows, no SQL, no sorting: that is a query engine's job.
- Filters on partition source columns only.
- Principals, roles and grants are not shown.
- Search covers the nodes already loaded.

## License

[Apache License 2.0](LICENSE). Bundled third-party libraries and their
licenses: [THIRD-PARTY.md](THIRD-PARTY.md). Building and testing:
[DEVELOPMENT.md](DEVELOPMENT.md).

Apache Polaris, Apache Iceberg and Apache are trademarks of the Apache Software
Foundation. This plugin is not affiliated with or endorsed by the ASF.
