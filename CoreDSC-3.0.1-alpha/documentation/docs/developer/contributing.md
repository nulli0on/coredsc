# Contributing

## Before opening a pull request

- Build with Java 21.
- Run `mvn clean verify`.
- Keep optional modules isolated from core startup.
- Add configuration defaults and migration handling together.
- Avoid direct Bukkit scheduler calls outside the scheduler adapter.
- Do not add telemetry fields containing IDs, names or free-form values.
- Update the documentation and exact default YAML downloads.

## Reliability rules

A feature is incomplete without validation, shutdown cleanup, error reporting and a recovery path. Do not silently fall back to another guild, channel, storage mode or command scope.

## Bug reports

Provide reproducible steps, versions, the first complete exception and redacted configuration. Do not publish secrets or private community content.
