# Account linking

The default flow is:

1. Player runs `/link` in Minecraft.
2. CoreDSC generates a temporary code.
3. The player submits the code through the Discord `/link` command.
4. CoreDSC stores one Minecraft UUID ↔ one Discord user link.
5. Optional link roles and first-link rewards are applied.

## Link-code settings

```yaml
code-expiry-seconds: 300
code-length: 8
```

Codes are persistent, expiring and single-use. Security options can require Discord membership, a minimum account age, a role, or an IP-based daily link limit.

## Required linking

```yaml
required:
  enabled: false
  grace-period-seconds: 300
  allowed-commands:
    - link
```

Do not enable required linking until the normal link flow has been tested. Keep `/link` available and configure an operator bypass permission.

## Rewards

`modules/link-rewards.yml` runs configured commands once after the first successful link. Reward state is stored so restarts do not repeat the claim.
