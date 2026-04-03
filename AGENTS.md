# AGENTS.md
The role of this file is to describe common mistakes and confusion points that agents might encounter as they work in this project. If you ever encounter something in the project that surprises you, please alert the developer working with you and indicate that this is the case in the AgentMD file to help prevent future agents from having the same issue.

### Developer defined rules
- Never put multiple classes on the base level of a file. The file name MUST describe the only top level class in that file. Therefore there can only exist 1 top level class. (sub-classes however are allowed as it does not violate this top class rule)
- When creating new functionality. Make sure to always use Result<T> when something uncurtain can happen. If it should return ia list, instead use Result<List<T>> rather than defaultng to an empty list. Defaulting to an empty list is up to the consumer, not the provider (most of hte time)
- Any functionality in the codebase should NEVER be dependend on code inside interfaces. Instead, the code should be fully complete on its own. and it should not matter what interface is being wrapped around it (either a command or menu for instance)
- NEVER use try-Catch. instead always use something like `runCatching`

### Documentation
- **Open AI docs**: https://developers.openai.com/api/docs
- **Claude docs**: https://platform.claude.com/doc
- **Gemini docs**: https://ai.google.dev/gemini-api/docs

---
## Agent Notes (Surprises Encountered)
- `AiService` should stay focused on communication with AI providers. Session management, player-context capture, command orchestration, and similar behavior should be implemented in dedicated classes instead of being placed directly inside `AiService`.
- Command classes must not contain business logic. They should delegate behavior to the implementation layer and only handle command wiring plus player-facing output.
- Outside `ai/providers`, code must not know which provider or model-specific implementation is active. Provider/model-specific request building, capability handling, and tool schema translation belong inside the `AiProvider` implementations.
- Provider tool-schema generation must respect `AiTool.Parameter.required`. If provider-side `required` lists are built from all parameter names, optional tool arguments silently stop being optional.
- Brigadier `ArgumentBuilder.then(...)` effectively snapshots the child command node at attach time. If you build generic command trees, add an argument node's child arguments and execute handlers before attaching that node to its parent, or the later-added descendants will not be reachable in the registered command.
