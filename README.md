# SummitChatEvents

A Spigot/Paper plugin scaffold for advanced chat event handling.

## Requirements

| Tool | Version |
|---|---|
| Java | 21+ |
| Gradle | 8.10 (via wrapper) |
| Paper / Spigot | 1.21.x |

## Building

```bash
./gradlew build
```

The shaded jar will appear at:

```
build/libs/summit-chat-events-<version>.jar
```

## Project structure

```
src/main/java/com/summit/summitchatevents/
├── SummitChatEventsPlugin.java   ← main class
├── events/
│   └── SummitChatEvent.java      ← custom plugin event (scaffold)
├── listeners/
│   └── ChatListener.java         ← Bukkit listener (scaffold)
├── managers/
│   └── EventManager.java         ← event pipeline manager (scaffold)
└── utils/
    └── MessageUtils.java         ← Adventure/MiniMessage helpers
```

## Roadmap

- [ ] Implement `AsyncChatEvent` handling in `ChatListener`
- [ ] Fire `SummitChatEvent` through `EventManager`
- [ ] Add chat format configuration
- [ ] Add chat filter rules (caps, URLs, …)
- [ ] Add PlaceholderAPI support

## License

MIT
