package com.banktemplates;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Talks to the community template repository (the Cloudflare Worker). All calls are async; callbacks
 * run off the Swing thread, so the panel marshals them back to the EDT.
 * <p>
 * The plugin identifies itself with a persistent random {@code clientId} (stored in config). The
 * server uses it as the owner key: it enforces the per-user upload cap and only lets you update or
 * delete templates you shared.
 */
@Slf4j
@Singleton
public class TemplateRepositoryClient
{
	private static final MediaType JSON = MediaType.parse("application/json");
	private static final int PAGE_SIZE = 20;
	private static final Type PAGE_TYPE = new TypeToken<Page>()
	{
	}.getType();
	// Salt so the value sent to the server is a derived hash, not the raw RuneLite account hash.
	private static final String SALT = "bank-templates-v1:";
	// Default community repository host. Served by the exchange-insights.gg Worker (routes under
	// /api/bank-templates/*); overridable via the "Repository URL" setting for self-hosting.
	private static final String DEFAULT_REPO_URL = "https://exchange-insights.gg";
	// Obfuscation only: this key ships in the open-source plugin, so it cannot authenticate requests. It
	// just signs write requests (X-BT-TS / X-BT-Sig = HMAC of "<ts>.<body>") to deter casual non-plugin
	// calls. The Worker checks it only when its REQUIRE_SIG flag is on, and its SIG_SECRET env must equal
	// this value.
	private static final String SIG_KEY = "bt-sig-2f9c1a7e4b6d8035c1e9";

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final BankTemplatesConfig config;

	// The owner key sent to the repository, derived from the logged-in OSRS account hash. Null when
	// logged out, so sharing/reporting/deleting is tied to the account and survives client restarts.
	private volatile String identity;

	// The raw account hash (String of client.getAccountHash()) - the same value Exchange Insights stores
	// in linked_accounts.account_hash. Sent alongside the salted clientId so a shared template can resolve
	// to the uploader's EI profile on the website. Null when logged out.
	private volatile String accountHashRaw;

	@Inject
	TemplateRepositoryClient(OkHttpClient okHttpClient, Gson gson, BankTemplatesConfig config)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.config = config;
	}

	boolean isEnabled()
	{
		return config.enableRepository();
	}

	/** Updates the account identity. Pass the value of {@code client.getAccountHash()} (-1 when logged out). */
	void setIdentity(long accountHash)
	{
		accountHashRaw = accountHash == -1 ? null : String.valueOf(accountHash);
		identity = accountHash == -1 ? null : sha256(SALT + accountHash);
	}

	boolean hasIdentity()
	{
		return identity != null && !identity.isEmpty();
	}

	String clientId()
	{
		return identity != null ? identity : "";
	}

	private static String sha256(String s)
	{
		try
		{
			final byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			final StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest)
			{
				sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			// SHA-256 is guaranteed to exist on every JVM.
			throw new IllegalStateException(e);
		}
	}

	// Signs a write request: X-BT-TS = now, X-BT-Sig = HMAC-SHA256(SIG_KEY, "<ts>.<body>"). Obfuscation
	// only (see SIG_KEY); the Worker enforces it only when REQUIRE_SIG is enabled.
	private void addSig(Request.Builder builder, String body)
	{
		final long ts = System.currentTimeMillis();
		builder.header("X-BT-TS", Long.toString(ts));
		builder.header("X-BT-Sig", hmacHex(SIG_KEY, ts + "." + body));
	}

	private static String hmacHex(String key, String msg)
	{
		try
		{
			final Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			final byte[] digest = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
			final StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest)
			{
				sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
			}
			return sb.toString();
		}
		catch (GeneralSecurityException e)
		{
			// HmacSHA256 is guaranteed present on every JVM; if signing somehow fails, send no signature.
			return "";
		}
	}

	// Repository base, from the (optional) setting, falling back to the default host. Any trailing
	// slashes are stripped so path building stays clean.
	private String baseUrl()
	{
		return DEFAULT_REPO_URL;
	}

	// Shown when a browse request can't connect or the server errors - deliberately generic and pointing at
	// a client restart, since the usual causes (a dropped connection, a stale client session, or the server
	// having moved) clear on one. Kept in 1.5.6 so existing clients already show it when 1.6 relocates the API.
	private static final String CONNECT_ERROR = "Couldn't connect to the template repository. Please restart your client and try again.";

	void search(String query, String sort, int offset, Consumer<Page> onSuccess, Consumer<String> onError)
	{
		if (!isEnabled())
		{
			onError.accept("The community repository is turned off. Enable it in the plugin settings.");
			return;
		}

		final HttpUrl base = HttpUrl.parse(baseUrl() + "/api/bank-templates/templates");
		if (base == null)
		{
			onError.accept("The repository URL is invalid.");
			return;
		}

		final HttpUrl.Builder url = base.newBuilder()
			.addQueryParameter("limit", Integer.toString(PAGE_SIZE))
			.addQueryParameter("offset", Integer.toString(Math.max(0, offset)));
		if (query != null && !query.trim().isEmpty())
		{
			url.addQueryParameter("q", query.trim());
		}
		if (sort != null)
		{
			url.addQueryParameter("sort", sort);
		}

		okHttpClient.newCall(new Request.Builder().url(url.build()).get().build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onError.accept(CONNECT_ERROR);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						onError.accept(CONNECT_ERROR);
						return;
					}
					final Page page = gson.fromJson(r.body().string(), PAGE_TYPE);
					onSuccess.accept(page != null ? page : new Page());
				}
				catch (IOException | JsonSyntaxException e)
				{
					onError.accept(CONNECT_ERROR);
				}
			}
		});
	}

	/** Creates a new shared template. {@code onSuccess} receives the new repo id. */
	void create(BankTemplate template, String author, boolean anonymous, Consumer<Long> onSuccess, Consumer<String> onError)
	{
		final String bodyJson = gson.toJson(payload(template, author, anonymous));
		final Request.Builder rb = new Request.Builder()
			.url(baseUrl() + "/api/bank-templates/templates")
			.post(RequestBody.create(JSON, bodyJson));
		addSig(rb, bodyJson);
		send(rb.build(), body ->
		{
			final JsonObject o = gson.fromJson(body, JsonObject.class);
			onSuccess.accept(o != null && o.has("id") ? o.get("id").getAsLong() : null);
		}, onError);
	}

	/** Updates a template the user owns, in place. */
	void update(long repoId, BankTemplate template, String author, boolean anonymous, Runnable onSuccess, Consumer<String> onError)
	{
		final String bodyJson = gson.toJson(payload(template, author, anonymous));
		final Request.Builder rb = new Request.Builder()
			.url(baseUrl() + "/api/bank-templates/templates/" + repoId)
			.put(RequestBody.create(JSON, bodyJson));
		addSig(rb, bodyJson);
		send(rb.build(), body -> onSuccess.run(), onError);
	}

	void delete(long repoId, Runnable onSuccess, Consumer<String> onError)
	{
		final HttpUrl.Builder ub = HttpUrl.parse(baseUrl() + "/api/bank-templates/templates/" + repoId)
			.newBuilder().addQueryParameter("clientId", clientId());
		// A duplex-synced template is owned by web:<id>, not by the plugin's clientId, so the server needs
		// the verified account hash to authorise deleting it. Harmless for clientId-owned public shares -
		// the server matches clientId first.
		if (accountHashRaw != null)
		{
			ub.addQueryParameter("accountHash", accountHashRaw);
		}
		final HttpUrl url = ub.build();
		// A 404 means the template is already gone from the server, which is exactly what delete wants -
		// treat it as success so the local copy can be cleaned up too.
		sendAllowingNotFound(new Request.Builder().url(url).delete().build(), body -> onSuccess.run(), onError);
	}

	// Records an import server-side, deduped to one per account per template. {@code onDone} (if any)
	// runs once the server has responded, so callers can refresh the true count.
	void recordImport(long repoId, Runnable onDone)
	{
		fireAndForget(repoId, "import", onDone);
	}

	// Reverses an import when the user deletes their imported copy (decrements the count).
	void unimport(long repoId)
	{
		fireAndForget(repoId, "unimport", null);
	}

	// Best-effort POST {clientId} to a template sub-path; the local action has already happened.
	private void fireAndForget(long repoId, String subPath, Runnable onDone)
	{
		if (!isEnabled())
		{
			return;
		}
		final JsonObject body = new JsonObject();
		body.addProperty("clientId", clientId());
		final String bodyJson = gson.toJson(body);
		final Request.Builder rb = new Request.Builder()
			.url(baseUrl() + "/api/bank-templates/templates/" + repoId + "/" + subPath)
			.post(RequestBody.create(JSON, bodyJson));
		addSig(rb, bodyJson);
		final Request request = rb.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				if (onDone != null)
				{
					onDone.run();
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				response.close();
				if (onDone != null)
				{
					onDone.run();
				}
			}
		});
	}

	void report(long repoId, Runnable onSuccess, Consumer<String> onError)
	{
		final JsonObject body = new JsonObject();
		body.addProperty("clientId", clientId());
		final String bodyJson = gson.toJson(body);
		final Request.Builder rb = new Request.Builder()
			.url(baseUrl() + "/api/bank-templates/templates/" + repoId + "/report")
			.post(RequestBody.create(JSON, bodyJson));
		addSig(rb, bodyJson);
		send(rb.build(), b -> onSuccess.run(), onError);
	}

	private JsonObject payload(BankTemplate template, String author, boolean anonymous)
	{
		final String name = author == null ? "" : author;
		final JsonObject payload = new JsonObject();
		payload.addProperty("name", template.getName());
		payload.addProperty("description", template.getDescription() == null ? "" : template.getDescription());
		// Public display name: blanked when sharing anonymously so other clients can never show it, even
		// against a server that doesn't yet understand the "anonymous" flag.
		payload.addProperty("author", anonymous ? "" : name);
		// The real RuneScape name. Only sent when NOT sharing anonymously (where it equals the public
		// author anyway). Moderation - bans, ownership, the per-account limit and duplicate-import/report
		// guards - all key off clientId (a salted account hash that is always sent), so withholding the
		// name when anonymous costs nothing and keeps anonymous shares truly anonymous.
		payload.addProperty("rsn", anonymous ? "" : name);
		payload.addProperty("anonymous", anonymous);
		payload.addProperty("columns", template.getColumns());
		payload.addProperty("clientId", clientId());
		// Raw account hash (matches Exchange Insights linked_accounts.account_hash) so a shared template
		// resolves to the uploader's EI profile on the website. Omitted for anonymous shares. The server
		// only trusts it when it verifies against clientId, so it can't be used to impersonate another
		// account, and never leaves the plugin for anonymous uploads.
		if (!anonymous && accountHashRaw != null)
		{
			payload.addProperty("accountHash", accountHashRaw);
		}
		payload.add("tabs", gson.toJsonTree(template.getTabs()));
		return payload;
	}

	// Fetch the templates this account's linked Exchange Insights user made on the website (including ones
	// saved privately, never shared), so they can be pulled into My Templates in-game. The server resolves
	// them from the verified account hash via the account link; returns an empty list when the character
	// isn't linked to an Exchange Insights account. No-op when logged out.
	void fetchForAccount(Consumer<List<RemoteTemplate>> onDone)
	{
		if (!isEnabled() || accountHashRaw == null || !hasIdentity())
		{
			onDone.accept(Collections.emptyList());
			return;
		}
		final JsonObject body = new JsonObject();
		body.addProperty("clientId", clientId());
		body.addProperty("accountHash", accountHashRaw);
		final String bodyJson = gson.toJson(body);
		final Request.Builder rb = new Request.Builder()
			.url(baseUrl() + "/api/bank-templates/for-account")
			.post(RequestBody.create(JSON, bodyJson));
		addSig(rb, bodyJson);
		okHttpClient.newCall(rb.build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onDone.accept(Collections.emptyList());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						onDone.accept(Collections.emptyList());
						return;
					}
					final Page page = gson.fromJson(r.body().string(), PAGE_TYPE);
					onDone.accept(page != null && page.templates != null ? page.templates : Collections.emptyList());
				}
				catch (IOException | JsonSyntaxException e)
				{
					onDone.accept(Collections.emptyList());
				}
			}
		});
	}

	// Duplex "My Templates" sync. Sends this account's in-game editable templates (imports and in-plugin
	// creations, plus copies previously pulled from the website), each tagged with its stable clientKey,
	// the website id it already knows (webId, as "repoId"), and its local updatedAt. The server reconciles
	// them last-write-wins against the account's website templates and returns the authoritative set to
	// mirror back. onDone gets null on any failure (nothing is changed locally) and a result with
	// linked=false when the character isn't linked to an Exchange Insights account. No-op when logged out.
	void sync(List<BankTemplate> local, Consumer<SyncResult> onDone)
	{
		if (!isEnabled() || accountHashRaw == null || !hasIdentity())
		{
			onDone.accept(null);
			return;
		}
		final JsonObject body = new JsonObject();
		body.addProperty("clientId", clientId());
		body.addProperty("accountHash", accountHashRaw);
		final JsonArray arr = new JsonArray();
		for (BankTemplate t : local)
		{
			final JsonObject o = new JsonObject();
			o.addProperty("clientKey", t.getClientKey());
			// The website id we already know for this template. Falls back to repoId so a copy synced by an
			// older plugin build (which stored the web id in repoId, with no webId/clientKey) is matched to
			// its existing row instead of being re-inserted as a duplicate. A community-import repoId simply
			// won't match any of this owner's rows server-side, so it's treated as new (as intended).
			final Long candidateWebId = t.getWebId() != null ? t.getWebId() : t.getRepoId();
			if (candidateWebId != null)
			{
				o.addProperty("repoId", candidateWebId);
			}
			o.addProperty("name", t.getName());
			o.addProperty("description", t.getDescription() == null ? "" : t.getDescription());
			o.addProperty("columns", t.getColumns());
			o.addProperty("updatedAt", t.getUpdatedAt());
			o.add("tabs", gson.toJsonTree(t.getTabs()));
			arr.add(o);
		}
		body.add("templates", arr);
		final String bodyJson = gson.toJson(body);
		final Request.Builder rb = new Request.Builder()
			.url(baseUrl() + "/api/bank-templates/sync")
			.post(RequestBody.create(JSON, bodyJson));
		addSig(rb, bodyJson);
		okHttpClient.newCall(rb.build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onDone.accept(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						onDone.accept(null);
						return;
					}
					onDone.accept(gson.fromJson(r.body().string(), SyncResult.class));
				}
				catch (IOException | JsonSyntaxException e)
				{
					onDone.accept(null);
				}
			}
		});
	}

	// The website's authoritative My Templates set for a duplex sync. linked=false means the character
	// isn't tied to an Exchange Insights account, so there's nothing to sync.
	static class SyncResult
	{
		boolean linked;
		// Whether the server ran the push/write pass this call (false when the per-account rate-limit gap
		// skipped it, so the plugin should retry a pending change).
		boolean applied;
		// The account's private-sync setting; when false the server never applies the plugin's pushes, so
		// there's no point retrying them (the website->plugin pull still runs).
		boolean privateSync;
		List<WebTemplate> templates;
	}

	// One website "My Templates" row as returned by /sync. Mirrors the JSON the Worker sends.
	static class WebTemplate
	{
		long id;
		String name;
		String description;
		int columns;
		List<TabLayout> tabs;
		String status;
		String clientKey;
		long updatedAt;

		BankTemplate toTemplate()
		{
			final BankTemplate t = new BankTemplate();
			t.setName(name);
			t.setDescription(description);
			t.setColumns(columns);
			if (tabs != null)
			{
				for (TabLayout tl : tabs)
				{
					t.putTab(tl.getTab(), tl.getLayout());
				}
			}
			return t;
		}
	}

	// Link this character to the Exchange Insights account that owns the given token, by posting its
	// identity to the same /api/plugin/identity endpoint the Exchange Insights plugin uses. The server
	// verifies the token, is ownership-safe (can't hijack a character already linked to another account)
	// and idempotent, so this works fine alongside the Exchange Insights plugin sending the same thing.
	// onLinked runs on success so the caller can start a sync now that the account is linked.
	void linkEiAccount(String token, long accountHash, String rsn, Runnable onLinked, Consumer<String> onError)
	{
		if (token == null || token.trim().isEmpty() || rsn == null || rsn.isEmpty())
		{
			return;
		}
		final JsonObject body = new JsonObject();
		body.addProperty("accountHash", Long.toString(accountHash));
		body.addProperty("rsn", rsn);
		final Request request = new Request.Builder()
			.url(baseUrl() + "/api/plugin/identity")
			.header("Authorization", "Bearer " + token.trim())
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				if (onError != null)
				{
					onError.accept("Couldn't reach the server to link your account.");
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.isSuccessful())
					{
						if (onLinked != null)
						{
							onLinked.run();
						}
					}
					else if (onError != null)
					{
						onError.accept(r.code() == 401
							? "That Exchange Insights account token is invalid or revoked."
							: "Couldn't link your account (" + r.code() + ").");
					}
				}
			}
		});
	}

	// ---- one-click device link ----------------------------------------------------------------------
	// The plugin starts a link (server returns a short user code + verification URL to open in the browser,
	// and a device secret to poll with), the signed-in browser approves it, and the issued account token is
	// handed back on the next poll. Public endpoints (no HMAC signature): link/start is unauthenticated by
	// design and link/poll self-authorizes via the one-time device secret. Same flow the Exchange Insights
	// plugin uses, so linking either way (or via a pasted token) is interchangeable.

	// Begin a device link for the logged-in character. onStart receives the code/secret/verification URL.
	void startDeviceLink(long accountHash, String rsn, Consumer<LinkStart> onStart, Consumer<String> onError)
	{
		final JsonObject body = new JsonObject();
		body.addProperty("accountHash", Long.toString(accountHash));
		if (rsn != null && !rsn.isEmpty())
		{
			body.addProperty("accountName", rsn);
		}
		// Default label for the minted token, so the website's account page names it by the plugin it came from.
		body.addProperty("label", "Bank Templates plugin");
		final Request request = new Request.Builder()
			.url(baseUrl() + "/api/plugin/link/start")
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				if (onError != null)
				{
					onError.accept("Couldn't reach the server to start linking.");
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						final LinkStart s = gson.fromJson(r.body().string(), LinkStart.class);
						// Defence in depth: only ever open a verification URL on our own (hardcoded) host, so a
						// tampered response can't redirect the browser somewhere else.
						if (s != null && s.deviceSecret != null && s.verificationUrl != null
							&& s.verificationUrl.startsWith(baseUrl() + "/"))
						{
							if (onStart != null)
							{
								onStart.accept(s);
							}
							return;
						}
					}
					if (onError != null)
					{
						onError.accept("The server didn't start the link. Please try again.");
					}
				}
				catch (IOException | JsonSyntaxException e)
				{
					if (onError != null)
					{
						onError.accept("The server didn't start the link. Please try again.");
					}
				}
			}
		});
	}

	// Poll a pending device link once. On approval, the result carries the freshly-issued account token
	// (handed out exactly once); other statuses are pending/denied/expired/invalid/claimed.
	void pollDeviceLink(String deviceSecret, Consumer<LinkPoll> onResult, Consumer<String> onError)
	{
		final JsonObject body = new JsonObject();
		body.addProperty("deviceSecret", deviceSecret);
		final Request request = new Request.Builder()
			.url(baseUrl() + "/api/plugin/link/poll")
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				if (onError != null)
				{
					onError.accept("connection lost");
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						final LinkPoll p = gson.fromJson(r.body().string(), LinkPoll.class);
						if (p != null)
						{
							if (onResult != null)
							{
								onResult.accept(p);
							}
							return;
						}
					}
					if (onError != null)
					{
						onError.accept("bad response");
					}
				}
				catch (IOException | JsonSyntaxException e)
				{
					if (onError != null)
					{
						onError.accept("bad response");
					}
				}
			}
		});
	}

	// Verify a stored token and return the linked Exchange Insights handle (may be null), for the panel's
	// "linked as" status line. GET /api/plugin/ping with the token as a bearer.
	void pingLink(String token, Consumer<String> onHandle, Consumer<String> onError)
	{
		if (token == null || token.trim().isEmpty())
		{
			if (onError != null)
			{
				onError.accept("no token");
			}
			return;
		}
		final Request request = new Request.Builder()
			.url(baseUrl() + "/api/plugin/ping")
			.header("Authorization", "Bearer " + token.trim())
			.get()
			.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				if (onError != null)
				{
					onError.accept("offline");
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						final Ping p = gson.fromJson(r.body().string(), Ping.class);
						if (p != null && p.ok)
						{
							if (onHandle != null)
							{
								onHandle.accept(p.handle);
							}
							return;
						}
					}
					if (onError != null)
					{
						onError.accept(r.code() == 401 ? "revoked" : "error");
					}
				}
				catch (IOException | JsonSyntaxException e)
				{
					if (onError != null)
					{
						onError.accept("error");
					}
				}
			}
		});
	}

	// /api/plugin/link/start response.
	static class LinkStart
	{
		String userCode;
		String deviceSecret;
		String verificationUrl;
		long expiresAt;
		int pollSeconds;
	}

	// /api/plugin/link/poll response.
	static class LinkPoll
	{
		String status;
		String token;
	}

	// /api/plugin/ping response.
	static class Ping
	{
		boolean ok;
		String handle;
	}

	// Opt-in bank snapshot for bank-value tracking ([itemId, quantity, tab] triples). Same
	// self-authorization as sync/claim: clientId + raw account hash + request signature; the server only
	// stores snapshots for characters linked to an Exchange Insights account, so this is a no-op for
	// everyone else. Best-effort: a lost snapshot is re-sent on the next bank change. On success the
	// server returns the bank's live GE value, delivered to onValue (off the Swing thread).
	void sendBankSnapshot(List<int[]> items, Consumer<Long> onValue)
	{
		if (!isEnabled() || accountHashRaw == null || !hasIdentity() || items == null || items.isEmpty())
		{
			return;
		}
		final JsonObject body = new JsonObject();
		body.addProperty("clientId", clientId());
		body.addProperty("accountHash", accountHashRaw);
		body.add("items", gson.toJsonTree(items));
		final String bodyJson = gson.toJson(body);
		final Request.Builder rb = new Request.Builder()
			.url(baseUrl() + "/api/bank-templates/bank-snapshot")
			.post(RequestBody.create(JSON, bodyJson));
		addSig(rb, bodyJson);
		okHttpClient.newCall(rb.build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						return;
					}
					final SnapshotResult res = gson.fromJson(r.body().string(), SnapshotResult.class);
					if (res != null && res.ok && res.totalValue != null && onValue != null)
					{
						onValue.accept(res.totalValue);
					}
				}
				catch (IOException | JsonSyntaxException e)
				{
				}
			}
		});
	}

	// /api/bank-templates/bank-snapshot response.
	static class SnapshotResult
	{
		boolean ok;
		boolean linked;
		Long totalValue;
	}

	// Best-effort backfill: link this account's already-shared templates to its Exchange Insights profile
	// (for templates uploaded before the accountHash field existed). The server verifies accountHash
	// against clientId, so this only ever stamps the caller's own uploads. No-op when logged out.
	void claimTemplates()
	{
		if (!isEnabled() || accountHashRaw == null || !hasIdentity())
		{
			return;
		}
		final JsonObject body = new JsonObject();
		body.addProperty("clientId", clientId());
		body.addProperty("accountHash", accountHashRaw);
		final String bodyJson = gson.toJson(body);
		final Request.Builder rb = new Request.Builder()
			.url(baseUrl() + "/api/bank-templates/claim")
			.post(RequestBody.create(JSON, bodyJson));
		addSig(rb, bodyJson);
		okHttpClient.newCall(rb.build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				response.close();
			}
		});
	}

	private void send(Request request, Consumer<String> onSuccess, Consumer<String> onError)
	{
		send(request, onSuccess, onError, false);
	}

	// Like send(), but a 404 is reported to onSuccess instead of onError. Used by delete, where a
	// missing template means the work is already done.
	private void sendAllowingNotFound(Request request, Consumer<String> onSuccess, Consumer<String> onError)
	{
		send(request, onSuccess, onError, true);
	}

	private void send(Request request, Consumer<String> onSuccess, Consumer<String> onError, boolean notFoundIsSuccess)
	{
		if (!isEnabled())
		{
			onError.accept("The community repository is turned off. Enable it in the plugin settings.");
			return;
		}
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onError.accept("Could not reach the repository.");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					final String body = r.body() != null ? r.body().string() : "";
					if (r.isSuccessful() || (notFoundIsSuccess && r.code() == 404))
					{
						onSuccess.accept(body);
						return;
					}
					onError.accept(errorMessage(body, r.code()));
				}
				catch (IOException e)
				{
					onError.accept("Could not read the repository response.");
				}
			}
		});
	}

	/** Pulls the server's friendly {@code message} (e.g. a moderation/limit/rate rejection) out of a response. */
	private String errorMessage(String body, int code)
	{
		try
		{
			final JsonObject err = gson.fromJson(body, JsonObject.class);
			if (err != null && err.has("message"))
			{
				return err.get("message").getAsString();
			}
		}
		catch (JsonSyntaxException ignored)
		{
			// fall through
		}
		return "Request failed (" + code + ").";
	}

	static class Page
	{
		List<RemoteTemplate> templates;
		boolean hasMore;
		// Total number of templates matching the current search/filter (across all pages), for the
		// "Count: N" label and "Page X of N" pager. Older servers omit it, so it defaults to 0.
		int total;
	}
}
