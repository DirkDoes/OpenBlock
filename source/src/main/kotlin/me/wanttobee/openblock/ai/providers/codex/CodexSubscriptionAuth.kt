package me.wanttobee.openblock.ai.providers.codex

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

object CodexSubscriptionAuth {
	private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
	private const val ISSUER = "https://auth.openai.com"
	private const val DEVICE_LOGIN_URL = "$ISSUER/codex/device"
	private const val DEVICE_USER_CODE_URL = "$ISSUER/api/accounts/deviceauth/usercode"
	private const val DEVICE_TOKEN_URL = "$ISSUER/api/accounts/deviceauth/token"
	private const val OAUTH_TOKEN_URL = "$ISSUER/oauth/token"
	private const val DEVICE_CALLBACK_URL = "$ISSUER/deviceauth/callback"
	private const val LOGIN_TIMEOUT_SECONDS = 15L * 60L
	private const val REFRESH_EARLY_SECONDS = 5L * 60L
	private val authFile: Path = Path.of("openblock-data", "codex-auth.json")
	private val gson = Gson()
	private val httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(20))
		.build()
	private val loginExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "openblock-codex-device-login").apply { isDaemon = true }
	}
	private val loginAttempt = AtomicLong(0L)
	@Volatile
	private var loginFuture: Future<*>? = null
	@Volatile
	private var loginError: String? = null

	fun accountStatus(): Result<AccountStatus> {
		if (!Files.exists(authFile)) {
			return Result.success(AccountStatus(null, null, null, loginPending(), loginError))
		}
		return credentials().fold(
			onSuccess = { credentials ->
				Result.success(AccountStatus("chatgpt", credentials.email, credentials.plan, loginPending(), loginError))
			},
			onFailure = { error ->
				Result.success(AccountStatus(null, null, null, loginPending(), error.message ?: loginError))
			},
		)
	}

	fun requireCredentials(): Result<Credentials> {
		return credentials().mapCatching { credentials ->
			credentials.accountId.takeIf(String::isNotBlank)
				?: throw IllegalStateException("The ChatGPT login has no account ID. Run /ob-codex logout, then /ob-codex login.")
			credentials
		}
	}

	fun forceRefresh(): Result<Credentials> = credentials(forceRefresh = true)

	fun startDeviceLogin(): Result<LoginInstructions> {
		if (loginPending()) {
			return Result.failure(IllegalStateException("A Codex ChatGPT sign-in is already waiting for completion."))
		}
		val response = sendJson(
			DEVICE_USER_CODE_URL,
			JsonObject().apply { addProperty("client_id", CLIENT_ID) },
		).getOrElse { return Result.failure(it) }
		val body = successfulJson(response, "Could not start ChatGPT device login.")
			.getOrElse { return Result.failure(it) }
		val deviceAuthId = body.string("device_auth_id")
			?: return Result.failure(IllegalStateException("ChatGPT device login returned no device authorization ID."))
		val userCode = body.string("user_code")
			?: return Result.failure(IllegalStateException("ChatGPT device login returned no user code."))
		val intervalSeconds = body.string("interval")?.toLongOrNull()
			?: body.get("interval")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
			?: 5L
		val verificationUrl = body.string("verification_uri")
			?: body.string("verification_url")
			?: DEVICE_LOGIN_URL

		loginError = null
		val attempt = loginAttempt.incrementAndGet()
		loginFuture = loginExecutor.submit {
			val result = pollDeviceLogin(deviceAuthId, userCode, intervalSeconds)
			if (loginAttempt.get() == attempt) {
				result.onFailure { error -> loginError = error.message ?: "ChatGPT sign-in failed." }
				loginFuture = null
			}
		}
		return Result.success(LoginInstructions(verificationUrl, userCode))
	}

	fun logout(): Result<Unit> {
		loginAttempt.incrementAndGet()
		loginFuture?.cancel(true)
		loginFuture = null
		loginError = null
		return runCatching<Unit> { Files.deleteIfExists(authFile) }
	}

	fun close() {
		loginAttempt.incrementAndGet()
		loginFuture?.cancel(true)
		loginExecutor.shutdownNow()
	}

	private fun pollDeviceLogin(deviceAuthId: String, userCode: String, intervalSeconds: Long): Result<Unit> {
		return runCatching {
			val deadline = Instant.now().plusSeconds(LOGIN_TIMEOUT_SECONDS)
			while (Instant.now().isBefore(deadline)) {
				Thread.sleep(intervalSeconds.coerceAtLeast(1L) * 1_000L)
				val response = sendJson(
					DEVICE_TOKEN_URL,
					JsonObject().apply {
						addProperty("device_auth_id", deviceAuthId)
						addProperty("user_code", userCode)
					},
				).getOrThrow()
				if (response.statusCode() == 403 || response.statusCode() == 404) continue
				val authorization = successfulJson(response, "ChatGPT device login failed.").getOrThrow()
				val authorizationCode = authorization.string("authorization_code")
					?: throw IllegalStateException("ChatGPT device login returned no authorization code.")
				val codeVerifier = authorization.string("code_verifier")
					?: throw IllegalStateException("ChatGPT device login returned no code verifier.")
				exchangeAuthorizationCode(authorizationCode, codeVerifier).getOrThrow()
				loginError = null
				return@runCatching
			}
			throw IllegalStateException("ChatGPT device login timed out after 15 minutes.")
		}
	}

	private fun exchangeAuthorizationCode(authorizationCode: String, codeVerifier: String): Result<Unit> {
		val form = formBody(mapOf(
			"grant_type" to "authorization_code",
			"code" to authorizationCode,
			"redirect_uri" to DEVICE_CALLBACK_URL,
			"client_id" to CLIENT_ID,
			"code_verifier" to codeVerifier,
		))
		val request = HttpRequest.newBuilder(URI.create(OAUTH_TOKEN_URL))
			.timeout(Duration.ofSeconds(30))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(form))
			.build()
		val response = send(request).getOrElse { return Result.failure(it) }
		val json = successfulJson(response, "Could not exchange the ChatGPT device login token.")
			.getOrElse { return Result.failure(it) }
		return tokenFile(json, null).mapCatching { auth ->
			saveAuth(auth).getOrThrow()
		}
	}

	@Synchronized
	private fun credentials(forceRefresh: Boolean = false): Result<Credentials> {
		var auth = readAuth().getOrElse { return Result.failure(it) }
		val expiresAt = jwtPayload(auth.accessToken).getOrNull()?.long("exp")
		val needsRefresh = forceRefresh || expiresAt?.let {
			Instant.now().epochSecond >= it - REFRESH_EARLY_SECONDS
		} == true
		if (needsRefresh) {
			auth = refresh(auth).getOrElse { return Result.failure(it) }
		}
		return claims(auth)
	}

	private fun refresh(existing: AuthFile): Result<AuthFile> {
		val refreshToken = existing.refreshToken.takeIf(String::isNotBlank)
			?: return Result.failure(IllegalStateException("The ChatGPT login cannot be refreshed. Run /ob-codex login again."))
		val response = sendJson(
			OAUTH_TOKEN_URL,
			JsonObject().apply {
				addProperty("client_id", CLIENT_ID)
				addProperty("grant_type", "refresh_token")
				addProperty("refresh_token", refreshToken)
			},
		).getOrElse { return Result.failure(it) }
		val json = successfulJson(response, "Could not refresh the ChatGPT login.")
			.getOrElse { return Result.failure(it) }
		return tokenFile(json, existing).mapCatching { refreshed ->
			saveAuth(refreshed).getOrThrow()
			refreshed
		}
	}

	private fun tokenFile(json: JsonObject, previous: AuthFile?): Result<AuthFile> {
		val accessToken = json.string("access_token")
			?: return Result.failure(IllegalStateException("ChatGPT authentication returned no access token."))
		return Result.success(AuthFile(
			accessToken = accessToken,
			refreshToken = json.string("refresh_token") ?: previous?.refreshToken.orEmpty(),
			idToken = json.string("id_token") ?: previous?.idToken,
		))
	}

	private fun claims(auth: AuthFile): Result<Credentials> {
		val payloads = listOfNotNull(
			jwtPayload(auth.accessToken).getOrNull(),
			auth.idToken?.let { jwtPayload(it).getOrNull() },
		)
		val accountId = firstClaim(payloads, "chatgpt_account_id")
			?: return Result.failure(IllegalStateException("The ChatGPT login has no account ID."))
		return Result.success(Credentials(
			accessToken = auth.accessToken,
			accountId = accountId,
			email = firstClaim(payloads, "email"),
			plan = firstClaim(payloads, "chatgpt_plan_type"),
		))
	}

	private fun firstClaim(payloads: List<JsonObject>, name: String): String? {
		return payloads.firstNotNullOfOrNull { payload ->
			payload.string(name)
				?: payload.getAsJsonObject("https://api.openai.com/auth")?.string(name)
		}
	}

	private fun jwtPayload(token: String): Result<JsonObject> {
		return runCatching {
			val encoded = token.split('.').getOrNull(1)
				?: throw IllegalArgumentException("Invalid ChatGPT login token.")
			val json = String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
			JsonParser.parseString(json).asJsonObject
		}
	}

	private fun saveAuth(auth: AuthFile): Result<Unit> {
		return runCatching<Unit> {
			Files.createDirectories(authFile.parent)
			Files.writeString(authFile, gson.toJson(auth), StandardCharsets.UTF_8)
		}
	}

	private fun readAuth(): Result<AuthFile> {
		if (!Files.exists(authFile)) {
			return Result.failure(IllegalStateException("Codex is not signed in with ChatGPT. Run /ob-codex login first."))
		}
		return runCatching {
			gson.fromJson(Files.readString(authFile, StandardCharsets.UTF_8), AuthFile::class.java)
				?: throw IllegalStateException("The saved ChatGPT login is empty.")
		}
	}

	private fun sendJson(url: String, json: JsonObject): Result<HttpResponse<String>> {
		val request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(30))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(json)))
			.build()
		return send(request)
	}

	private fun send(request: HttpRequest): Result<HttpResponse<String>> {
		return runCatching { httpClient.send(request, HttpResponse.BodyHandlers.ofString()) }
	}

	private fun successfulJson(response: HttpResponse<String>, fallbackMessage: String): Result<JsonObject> {
		val parsed = runCatching { JsonParser.parseString(response.body()).asJsonObject }
		if (response.statusCode() !in 200..299) {
			val json = parsed.getOrNull()
			val message = json?.string("error_description")
				?: json?.getAsJsonObject("error")?.string("message")
				?: json?.string("error")
				?: "$fallbackMessage HTTP ${response.statusCode()}."
			return Result.failure(IllegalStateException(message))
		}
		return parsed
	}

	private fun formBody(values: Map<String, String>): String {
		return values.entries.joinToString("&") { (key, value) ->
			"${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
		}
	}

	private fun loginPending(): Boolean = loginFuture?.isDone == false

	private fun JsonObject.string(name: String): String? {
		return get(name)?.takeUnless { it.isJsonNull }?.asString
	}

	private fun JsonObject.long(name: String): Long? {
		return get(name)?.takeUnless { it.isJsonNull }?.asLong
	}

	data class AccountStatus(
		val type: String?,
		val email: String?,
		val plan: String?,
		val loginPending: Boolean,
		val loginError: String?,
	)

	data class LoginInstructions(
		val verificationUrl: String,
		val userCode: String,
	)

	data class Credentials(
		val accessToken: String,
		val accountId: String,
		val email: String?,
		val plan: String?,
	)

	private data class AuthFile(
		val accessToken: String,
		val refreshToken: String,
		val idToken: String?,
	)
}
