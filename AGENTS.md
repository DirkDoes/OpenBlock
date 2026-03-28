# AGENTS.md
The role of this file is to describe common mistakes and confusion points that agents might encounter as they work in this project. If you ever encounter something in the project that surprises you, please alert the developer working with you and indicate that this is the case in the AgentMD file to help prevent future agents from having the same issue.


### Documentation
- **Open AI docs**: https://developers.openai.com/api/docs
- **Claude docs**: https://platform.claude.com/doc
- **Gemini docs**: https://ai.google.dev/gemini-api/docs

---
## Agent Notes (Surprises Encountered)
- `AiService` should stay focused on communication with AI providers. Session management, player-context capture, command orchestration, and similar behavior should be implemented in dedicated classes instead of being placed directly inside `AiService`.
- Command classes must not contain business logic. They should delegate behavior to the implementation layer and only handle command wiring plus player-facing output.
- Outside `ai/providers`, code must not know which provider or model-specific implementation is active. Provider/model-specific request building, capability handling, and tool schema translation belong inside the `AiProvider` implementations.
