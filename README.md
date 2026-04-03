<p align="center">
  <img src="assets/OpenBlock-title.png" alt="OpenBlock Logo" width="500"/>
</p>

OpenBlock is a server-side Minecraft mod focused on bringing full AI-driven gameplay and tooling into a Fabric server environment. It is built to let the server talk to multiple AI providers and manage that behavior from inside the mod.

## Environment Variables

OpenBlock reads env values from `config/.env` or `config/openblock.env` (from your server files). If the same key exists in both files, `openblock.env` takes priority.

```env
OPENAI_API_KEY=
ANTHROPIC_API_KEY=
GOOGLE_API_KEY=
```
