# `secrets.yml`

This file is used only when `discord.token-source` is `SECRETS.YML`.

```yaml
config-version: 5
generated-by-version: "3.0.1-alpha"
discord-token: ""
```

An environment variable is safer because the token does not need to be stored in the plugin directory. When a file is unavoidable, restrict it to the server account and exclude it from public backups, screenshots and support archives.

[Download the exact default file](/default-configs/secrets.yml).
