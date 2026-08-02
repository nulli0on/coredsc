# Verify the installation

A green startup line is not enough. Verify each layer.

## Core checks

```text
/coredsc status
/coredsc doctor
```

Confirm:

- core and storage are ready;
- the configured guild ID resolves to the intended guild;
- slash-command registration reports the expected scope;
- enabled modules are ready;
- no configuration warnings remain unexplained.

## Functional checks

1. Send Minecraft chat to Discord.
2. Send a Discord message back to Minecraft if reverse chat is enabled.
3. Restart the server and confirm no message is duplicated.
4. Disconnect the bot briefly or remove channel permission, then restore it and check recovery.
5. Run `/link`, complete the Discord command and confirm the account appears linked.

## Support information

When reporting a problem, include:

- CoreDSC version;
- Paper and Java versions;
- `/coredsc status`;
- `/coredsc doctor`;
- the first complete exception;
- relevant YAML with tokens and private data removed.
