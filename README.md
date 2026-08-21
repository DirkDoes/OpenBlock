<p align="center">
  <img src="assets/OpenBlock-title.png" alt="OpenBlock Logo" width="500"/>
</p>

OpenBlock is a server-side Minecraft mod focused on bringing full AI-driven gameplay and tooling into a Fabric server environment. It is built to let the server talk to multiple AI providers and manage that behavior from inside the mod.

Players do not need to install OpenBlock. It runs only on the Fabric server and uses vanilla packets and interfaces for player interaction.

## Project layout

- `source/` — mod source, Gradle build, and deployment script
- `server/` — ignored local development server files
- `assets/` — editable project artwork (not a resource pack)

Build, deploy, and start the local test server on Windows:

```powershell
cd .\source
.\deploy-test.bat
```

On its first run, `deploy-test.bat` copies the ignored Minecraft 26.2 `server/` template to `test-server/`. It builds OpenBlock, copies the mod JAR into `test-server/mods`, and starts the server. Use `deploy-test.bat --no-start` to deploy without starting it. Both server directories are ignored by Git.

## Environment Variables

OpenBlock reads env values from `config/.env` or `config/openblock.env` (from your server files). If the same key exists in both files, `openblock.env` takes priority.

```env
OPENAI_API_KEY=
ANTHROPIC_API_KEY=
GOOGLE_API_KEY=
```

## Codex subscription provider

OpenBlock can use the Codex access included with a ChatGPT subscription without an OpenAI API key. OpenBlock talks to the subscription-backed Codex Responses service directly and remains responsible for the conversation and Minecraft tools. It does not install, launch, or communicate with the Codex CLI or Codex app harness.

1. Start the Minecraft server and run `/ob-codex login` as an operator or from the server console.
2. Open the displayed verification URL, enter the one-time code, and sign in to ChatGPT. Google sign-in works normally.
3. Check the result with `/ob-codex status`, then select the `codex` provider in OpenBlock.

OpenBlock stores and refreshes its own OAuth tokens in `openblock-data/codex-auth.json` inside the server working directory. That file and the local server directories are ignored by Git. OpenBlock never receives your Google password. Usage follows the limits of the signed-in ChatGPT plan rather than API-key billing.

The provider offers GPT-5.6 Luna, Sol, and Terra with `none`, `low`, `medium`, `high`, `xhigh`, and `max` reasoning selections. The ChatGPT subscription backend is not the separately billed public OpenAI API and may change independently of it.
