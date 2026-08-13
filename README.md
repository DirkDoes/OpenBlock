<p align="center">
  <img src="assets/OpenBlock-title.png" alt="OpenBlock Logo" width="500"/>
</p>

OpenBlock is a server-side Minecraft mod focused on bringing full AI-driven gameplay and tooling into a Fabric server environment. It is built to let the server talk to multiple AI providers and manage that behavior from inside the mod.

Players do not need to install OpenBlock. It runs only on the Fabric server and uses vanilla packets and interfaces for player interaction.

## Project layout

- `source/` — mod source, Gradle build, and deployment script
- `server/` — ignored local development server files
- `assets/` — editable project artwork (not a resource pack)

Build and start the development server on Windows:

```powershell
cd .\source
.\deploy-server.bat
```

Use `deploy-server.bat --no-start` to build without starting the server. Gradle creates and runs the ignored `server/` directory automatically; no standalone server launcher is needed.

## Environment Variables

OpenBlock reads env values from `config/.env` or `config/openblock.env` (from your server files). If the same key exists in both files, `openblock.env` takes priority.

```env
OPENAI_API_KEY=
ANTHROPIC_API_KEY=
GOOGLE_API_KEY=
```
