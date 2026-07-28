#!/usr/bin/env python3
"""CoreDSC's local JSON-lines Python worker. Uses only the Python standard library."""
from __future__ import annotations

import argparse
import importlib.util
import json
import pathlib
import sys
import traceback

_PROTOCOL_OUT = sys.stdout
# Bundled/user imports must not write __pycache__ files into the plugin source or
# extracted runtime directory.
sys.dont_write_bytecode = True
# User print() calls must never corrupt the JSON protocol.
sys.stdout = sys.stderr


def send(message: dict) -> None:
    _PROTOCOL_OUT.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
    _PROTOCOL_OUT.flush()


def load_scripts(scripts_directory: pathlib.Path):
    runtime_directory = pathlib.Path(__file__).resolve().parent
    if str(runtime_directory) not in sys.path:
        sys.path.insert(0, str(runtime_directory))
    if str(scripts_directory) not in sys.path:
        # Keep CoreDSC's runtime and the Python standard library ahead of user
        # scripts so files such as coredsc_api.py or json.py cannot shadow them.
        sys.path.append(str(scripts_directory))

    import coredsc_api

    loaded = []
    scripts_directory.mkdir(parents=True, exist_ok=True)
    for script in sorted(scripts_directory.glob("*.py")):
        if script.name.startswith("_"):
            continue
        module_name = f"coredsc_user_{script.stem}"
        checkpoint = coredsc_api._registry_checkpoint()
        try:
            specification = importlib.util.spec_from_file_location(module_name, script)
            if specification is None or specification.loader is None:
                raise ImportError(f"Could not load {script.name}")
            module = importlib.util.module_from_spec(specification)
            sys.modules[module_name] = module
            specification.loader.exec_module(module)
            loaded.append(script.name)
        except Exception as error:
            coredsc_api._registry_restore(checkpoint)
            sys.modules.pop(module_name, None)
            send({
                "type": "log",
                "level": "ERROR",
                "script": script.name,
                "message": f"Load failed: {error}\n{traceback.format_exc()}",
            })
    registry = coredsc_api._registry_snapshot()
    registry["scripts"] = loaded
    return coredsc_api, registry


def main() -> int:
    if sys.version_info < (3, 8):
        send({"type": "log", "level": "ERROR", "message": "CoreDSC requires Python 3.8 or newer"})
        return 2
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--scripts", required=True)
    arguments = parser.parse_args()
    api, registry = load_scripts(pathlib.Path(arguments.scripts).resolve())
    hello_seen = False

    for raw_line in sys.stdin:
        try:
            message = json.loads(raw_line)
            message_type = message.get("type", "")
            if message_type == "hello":
                if int(message.get("protocol", 0)) != 1:
                    raise RuntimeError("Unsupported CoreDSC Python protocol")
                hello_seen = True
                send({"type": "ready", **registry})
            elif message_type == "execute":
                if not hello_seen:
                    raise RuntimeError("CoreDSC hello handshake was not received")
                request_id = str(message.get("request_id", ""))
                try:
                    if message.get("kind") == "event":
                        actions = api._execute_event(message.get("event", ""), message.get("context") or {})
                    else:
                        actions = api._execute_handler(message.get("handler", ""), message.get("context") or {})
                    send({"type": "result", "request_id": request_id, "actions": actions})
                except Exception as error:
                    send({
                        "type": "error",
                        "request_id": request_id,
                        "message": str(error),
                        "traceback": traceback.format_exc(),
                    })
            elif message_type == "shutdown":
                return 0
            else:
                send({"type": "log", "level": "WARNING", "message": f"Unknown request: {message_type}"})
        except Exception as error:
            send({"type": "log", "level": "ERROR", "message": f"Protocol error: {error}"})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
