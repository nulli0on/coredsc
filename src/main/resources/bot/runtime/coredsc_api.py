"""Small standard-library-only SDK exposed to CoreDSC Python scripts."""
from __future__ import annotations

import re
from typing import Any, Callable, Dict, Iterable, List, Optional, Union

_COMMANDS: List[dict] = []
_EVENTS: Dict[str, List[dict]] = {}
_HANDLERS: Dict[str, Callable[["Context"], Any]] = {}
_EVENT_NAME = re.compile(r"[a-z0-9][a-z0-9_.:-]{0,63}\Z")
_COMMAND_NAME = re.compile(r"[a-z0-9_-]{1,32}\Z")
_MAX_ACTIONS = 1_000


def _normalize_platforms(values: Iterable[str]) -> List[str]:
    if isinstance(values, str):
        values = (values,)
    normalized = []
    for value in values:
        platform = str(value).strip().upper()
        if platform not in {"DISCORD", "MINECRAFT"}:
            raise ValueError("Command platforms must be DISCORD and/or MINECRAFT")
        if platform not in normalized:
            normalized.append(platform)
    if not normalized:
        raise ValueError("At least one command platform is required")
    return normalized


def command(
    *,
    name: str,
    description: str,
    platforms: Iterable[str] = ("DISCORD",),
    permission: str = "",
    ephemeral: bool = True,
    guild_only: bool = False,
    linked_only: bool = False,
    cooldown_seconds: int = 0,
    allowed_role_ids: Iterable[Union[str, int]] = (),
    options: Optional[List[dict]] = None,
):
    """Register one Minecraft/Discord command implemented by a Python function."""
    normalized_name = str(name).strip().lower()
    normalized_description = str(description).strip()
    normalized_platforms = _normalize_platforms(platforms)
    if not _COMMAND_NAME.fullmatch(normalized_name):
        raise ValueError("Command names must match [a-z0-9_-]{1,32}")
    if not 1 <= len(normalized_description) <= 100:
        raise ValueError("Command descriptions must contain 1-100 characters")

    def decorator(function: Callable[[Context], Any]):
        handler = f"command:{function.__module__}:{function.__name__}"
        _HANDLERS[handler] = function
        _COMMANDS.append({
            "name": normalized_name,
            "description": normalized_description,
            "platforms": list(normalized_platforms),
            "permission": str(permission),
            "ephemeral": bool(ephemeral),
            "guild_only": bool(guild_only),
            "linked_only": bool(linked_only),
            "cooldown_seconds": max(0, int(cooldown_seconds)),
            "allowed_role_ids": [str(value) for value in allowed_role_ids],
            "options": list(options or []),
            "handler": handler,
            "script": function.__module__,
        })
        return function
    return decorator


def event(name: str):
    """Register a function for a CoreDSC event such as player_join or ticket_created."""
    normalized = str(name).strip().lower()
    if not _EVENT_NAME.fullmatch(normalized):
        raise ValueError("Event names must match [a-z0-9][a-z0-9_.:-]{0,63}")

    def decorator(function: Callable[[Context], Any]):
        handler = f"event:{function.__module__}:{function.__name__}"
        _HANDLERS[handler] = function
        _EVENTS.setdefault(normalized, []).append({
            "handler": handler,
            "script": function.__module__,
        })
        return function
    return decorator


class DotDict(dict):
    """Dictionary with safe attribute-style reads for script convenience."""
    def __getattr__(self, name: str) -> Any:
        value = self.get(name)
        if isinstance(value, dict) and not isinstance(value, DotDict):
            value = DotDict.wrap(value)
            self[name] = value
        return value

    @staticmethod
    def wrap(value: Any) -> Any:
        if isinstance(value, dict):
            return DotDict({key: DotDict.wrap(item) for key, item in value.items()})
        if isinstance(value, list):
            return [DotDict.wrap(item) for item in value]
        return value


class DiscordActions:
    def __init__(self, context: "Context"):
        self._context = context

    def send(
        self,
        channel_id: Union[str, int],
        message: str,
        durable: bool = False,
        dedupe_key: str = "",
    ) -> None:
        """Send to Discord. durable=True uses CoreDSC's persistent delivery queue when enabled."""
        self._context._action(
            "DISCORD_SEND",
            channel_id=str(channel_id),
            message=str(message),
            durable=bool(durable),
            dedupe_key=str(dedupe_key),
        )

    def add_role(self, role_id: Union[str, int], user_id: Union[str, int] = "") -> None:
        self._context._action("ADD_DISCORD_ROLE", role_id=str(role_id), user_id=str(user_id))

    def remove_role(self, role_id: Union[str, int], user_id: Union[str, int] = "") -> None:
        self._context._action("REMOVE_DISCORD_ROLE", role_id=str(role_id), user_id=str(user_id))


class MinecraftActions:
    def __init__(self, context: "Context"):
        self._context = context

    def broadcast(self, message: str) -> None:
        self._context._action("MINECRAFT_BROADCAST", message=str(message))

    def send(self, message: str, player_uuid: str = "") -> None:
        self._context._action("PLAYER_MESSAGE", message=str(message), player_uuid=str(player_uuid))

    def console(self, command_line: str) -> None:
        self._context._action("CONSOLE_COMMAND", command=str(command_line))


class TicketActions:
    def __init__(self, context: "Context"):
        self._context = context

    def create(self, reason: str, message: str, player_uuid: str = "") -> None:
        self._context._action(
            "CREATE_TICKET", reason=str(reason), message=str(message), player_uuid=str(player_uuid)
        )


class ReportActions:
    def __init__(self, context: "Context"):
        self._context = context

    def create(self, target: str, reason: str, message: str = "", reporter_uuid: str = "") -> None:
        self._context._action(
            "CREATE_REPORT",
            target=str(target),
            reason=str(reason),
            message=str(message),
            reporter_uuid=str(reporter_uuid),
        )


class Context:
    """Execution context. Methods queue validated actions for the Java core."""
    def __init__(self, raw: dict):
        self.data = DotDict.wrap(dict(raw or {}))
        self.platform = self.data.get("platform", "EVENT")
        self.player = self.data.get("player") or DotDict()
        self.discord_user = self.data.get("discord_user") or DotDict()
        self.link = self.data.get("link") or DotDict()
        self.args = list(self.data.get("args") or [])
        self.options = self.data.get("options") or DotDict()
        self.event = self.data.get("event") or DotDict()
        self.plugins = self.data.get("plugins") or DotDict()
        self._actions: List[dict] = []
        self.discord = DiscordActions(self)
        self.minecraft = MinecraftActions(self)
        self.ticket = TicketActions(self)
        self.report = ReportActions(self)

    def reply(self, message: str) -> None:
        self._action("REPLY", message=str(message))

    def log(self, message: str, level: str = "INFO") -> None:
        self._action("LOG", message=str(message), level=str(level).upper())

    def _action(self, action_type: str, **values: Any) -> None:
        if len(self._actions) >= _MAX_ACTIONS:
            raise RuntimeError(f"A Python execution cannot create more than {_MAX_ACTIONS} actions")
        normalized_type = str(action_type).strip().upper()
        if not normalized_type or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", normalized_type):
            raise ValueError("Action types must match [A-Z][A-Z0-9_]{0,63}")
        action = {"type": normalized_type}
        action.update(values)
        self._actions.append(action)


def _registry_checkpoint() -> tuple:
    return (
        list(_COMMANDS),
        {name: list(registrations) for name, registrations in _EVENTS.items()},
        dict(_HANDLERS),
    )


def _registry_restore(checkpoint: tuple) -> None:
    commands, events, handlers = checkpoint
    _COMMANDS.clear()
    _COMMANDS.extend(commands)
    _EVENTS.clear()
    _EVENTS.update(events)
    _HANDLERS.clear()
    _HANDLERS.update(handlers)


def _registry_snapshot() -> dict:
    return {
        "commands": list(_COMMANDS),
        "events": sorted(_EVENTS.keys()),
    }


def _execute_handler(handler: str, raw_context: dict) -> List[dict]:
    function = _HANDLERS.get(handler)
    if function is None:
        raise LookupError(f"Unknown Python handler: {handler}")
    context = Context(raw_context)
    result = function(context)
    if isinstance(result, str):
        context.reply(result)
    elif isinstance(result, dict):
        context._action(str(result.get("type", "")),
                        **{key: value for key, value in result.items() if key != "type"})
    elif isinstance(result, list):
        for action in result:
            if isinstance(action, dict):
                context._action(str(action.get("type", "")),
                                **{key: value for key, value in action.items() if key != "type"})
    return context._actions


def _execute_event(event_name: str, raw_context: dict) -> List[dict]:
    actions: List[dict] = []
    for registration in list(_EVENTS.get(str(event_name).lower(), [])):
        actions.extend(_execute_handler(registration["handler"], raw_context))
    return actions
