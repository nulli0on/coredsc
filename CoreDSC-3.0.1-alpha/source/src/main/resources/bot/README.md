# CoreDSC Python Bot Module

This folder is part of the optional Python integration for the CoreDSC plugin. The plugin can load Python scripts from here to add custom commands and react to events.

## How it works
- The plugin uses the Python worker in the runtime folder to start a Python process.
- Scripts are loaded from the scripts folder.
- Example files are included so you can see how commands and events are defined.
- The module is disabled by default and must be configured in the bot config before it will run.

## Quick start
- Open bot/config.yml and enable the Python module if you want to use it.
- Put your own Python script in the scripts folder.
- Start with the example_command.py and example_event.py files to learn the format.
- Restart the plugin or reload the Python module after changing scripts.

## What this is for
- Register custom Discord and Minecraft commands.
- Listen for CoreDSC events such as account linking or other plugin events.
- Use the CoreDSC API helpers from runtime/coredsc_api.py inside your scripts.

## Warning
These scripts run inside the plugin environment and can interact with your server and bot runtime. Only use trusted code. Do not install random scripts from strangers or run code you do not understand.
