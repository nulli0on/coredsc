# Python worker

The Python worker is an optional developer feature. It runs as a separate local process and is disabled by default.

```yaml title="bot/config.yml"
enabled: false
auto-start: false
engine: EXTERNAL_CPYTHON
python-executable: auto
```

## Commands

```text
/coredsc bot status
/coredsc bot scripts
/coredsc bot start
/coredsc bot stop
/coredsc bot restart
/coredsc emit <event> [key=value ...]
```

## Security model

The worker isolates sensitive environment variables by default and applies queue, timeout, action and restart limits. It is not a sandbox. A script installed by an administrator is trusted code with the actions permitted in `bot/config.yml`.

Keep console commands disabled, review every third-party script, and avoid loading arbitrary packages at runtime.
