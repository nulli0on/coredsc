# Permissions

| Permission | Default | Purpose |
|---|---:|---|
| `coredsc.status` | OP | View CoreDSC status |
| `coredsc.reload` | OP | Reload configuration/modules |
| `coredsc.doctor` | OP | Diagnostics and setup helpers |
| `coredsc.queue` | OP | Inspect/retry/clear queue |
| `coredsc.bot` | OP | Manage Python worker |
| `coredsc.emit` | OP | Publish configured Python events |
| `coredsc.migrate` | OP | Run supported migration tools |
| `coredsc.webeditor` | OP | Manage temporary WebEditor sessions from the server console |
| `coredsc.link` | Everyone | Generate a link code |
| `coredsc.unlink` | Everyone | Remove own link |
| `coredsc.link.bypass` | OP | Bypass required linking |
| `coredsc.ticket` | Everyone | Own tickets |
| `coredsc.report` | Everyone | Create reports |
| `coredsc.report.bypass` | OP | Bypass report limits where supported |
| `coredsc.report.admin` | OP | Report administration GUI |
| `coredsc.case.view` | OP | View cases |
| `coredsc.case.manage` | OP | Close cases |
| `coredsc.appeal` | Everyone | Appeal own cases |
| `coredsc.apply` | Everyone | Create own application |
| `coredsc.application.manage` | OP | Review applications |
| `coredsc.voice.optout` | False | Exclude player from voice grouping |
| `coredsc.lore.trigger` | OP | Trigger configured cinematic lore profiles |

Use a permissions plugin for staff roles rather than granting operator status solely for CoreDSC administration.
