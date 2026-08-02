# Custom commands and workflows

## Custom commands

A command can exist on Minecraft, Discord or both. Each definition controls permissions, linked-account requirements, Discord roles, cooldowns, response text, options and bounded actions.

Potential actions include sending messages, changing roles/groups, creating a ticket, triggering a workflow and—only when explicitly enabled—running an allowed console command.

## Workflows

Workflows combine:

- a trigger;
- optional conditions;
- one or more actions.

Example triggers include account linking, first join and schedules. Keep definitions small and observable. A workflow that attempts several unrelated administrative actions is harder to recover after a partial external failure.

## Safety

- Keep `allow-console-actions: false` unless required.
- Use narrow command prefixes.
- Do not insert untrusted message text into console commands.
- Test each action independently before combining it in a workflow.
