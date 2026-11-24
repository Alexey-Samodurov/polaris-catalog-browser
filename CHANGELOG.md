# Changelog

## Unreleased

- Detail tables size their columns to their contents and scroll sideways instead of cutting values short.
- Detail table headers are bold and do not wrap, as in the data grid.

## 0.1.0

- Browse Apache Polaris catalogs, nested namespaces, Iceberg tables and views.
- Table details: schema, partition spec, sort order, properties with secrets masked, snapshot history.
- Table data preview with deletes applied, at any snapshot, branch or tag, with column choice and partition filters.
- Data preview in an editor tab on double-click.
- S3 keys per connection for catalogs with STS off; vended credentials otherwise.
