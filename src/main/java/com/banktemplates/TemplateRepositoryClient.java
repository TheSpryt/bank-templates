package com.banktemplates;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Talks to Bank Templates Lite. Reads are static files on a public bucket: {@code index.json} is the
 * whole catalogue as metadata, and {@code t/{id}-{rev}.json} is one immutable layout. Writes (share,
 * update, delete, import, report) go to a small Worker. All calls are async; callbacks run off the Swing
 * thread, so the panel marshals them back to the EDT.
 * <p>
 * The plugin identifies itself with a {@code clientId} derived from the logged-in account hash. The
 * server uses it as the owner key: it enforces the per-owner cap and only lets you update or delete
 * templates you shared.
 */
@Slf4j
@Singleton
public class TemplateRepositoryClient
{
	// Where the catalogue files and the write API live. These are the defaults baked into the build;
	// origins.json in the plugin repository overrides them once per session (see resolveOrigins), so
	// a hostname change on Cloudflare's side is a one-file edit on GitHub rather than a hub release.
	// Neither is a custom domain: the service is meant to outlive any domain registration.
	static final String DEFAULT_CDN_BASE = "https://pub-b3f8a01834d74fb3a6360430c8a4e843.r2.dev";
	static final String DEFAULT_API_BASE = "https://bank-templates-lite.spryt.workers.dev";
	private static final String ORIGINS_URL = "https://raw.githubusercontent.com/TheSpryt/bank-templates/master/origins.json";
	private volatile String cdnBase = DEFAULT_CDN_BASE;
	private volatile String apiBase = DEFAULT_API_BASE;
	private final java.util.concurrent.atomic.AtomicBoolean originsResolved = new java.util.concurrent.atomic.AtomicBoolean();

	private static final MediaType JSON = MediaType.parse("application/json");
	// Salt so the value sent to the server is a derived hash, not the raw RuneLite account hash.
	private static final String SALT = "bank-templates-v1:";
	// Obfuscation only: this key ships in the open-source plugin, so it cannot authenticate requests. It
	// just signs write requests (X-BT-TS / X-BT-Sig = HMAC of "<ts>.<body>") to deter casual non-plugin
	// calls. The Worker checks it only when its REQUIRE_SIG flag is on, and its SIG_SECRET env must equal
	// this value.
	private static final String SIG_KEY = "bt-sig-2f9c1a7e4b6d8035c1e9";
	// index.json is served with max-age=60, so asking more often than that only gets the same bytes back.
	private static final long INDEX_TTL_MS = 60_000;

	private static final String CONNECT_ERROR = "Couldn't reach the template repository. Check your connection and try again.";

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final BankTemplatesConfig config;

	// The owner key sent to the repository, derived from the logged-in OSRS account hash. Null when
	// logged out, so sharing/reporting/deleting is tied to the account and survives client restarts.
	private volatile String identity;

	// The last catalogue fetched and when, so Browse can re-render (sort, search, page) without a request.
	private final Object indexLock = new Object();
	private List<RemoteTemplate> cachedIndex;
	private long indexFetchedAt;
	// Set by invalidateIndex: the next fetch revalidates with the origin instead of trusting a cached copy.
	private boolean indexStale;

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

	String cdnBase()
	{
		resolveOrigins();
		return cdnBase;
	}

	String apiBase()
	{
		resolveOrigins();
		return apiBase;
	}

	// One fetch per session of the repository's origins.json. Only https URLs are accepted, anything
	// else leaves the defaults in place, and a failure is silent: the defaults are what the build was
	// tested against. The first request may race this and go to the defaults; that is fine, they are
	// the same hostnames unless something has been deliberately moved.
	private void resolveOrigins()
	{
		if (!originsResolved.compareAndSet(false, true))
		{
			return;
		}
		okHttpClient.newCall(new Request.Builder().url(ORIGINS_URL).get().build()).enqueue(new Callback()
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
					final JsonObject o = gson.fromJson(r.body().string(), JsonObject.class);
					final String cdn = originOf(o, "cdn");
					final String api = originOf(o, "api");
					if (cdn != null)
					{
						cdnBase = cdn;
					}
					if (api != null)
					{
						apiBase = api;
					}
				}
				catch (IOException | RuntimeException e)
				{
					// Malformed or unreachable: keep the defaults.
				}
			}
		});
	}

	private static String originOf(JsonObject o, String key)
	{
		if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive())
		{
			return null;
		}
		final String v = o.get(key).getAsString().trim().replaceAll("/+$", "");
		return v.startsWith("https://") && HttpUrl.parse(v) != null ? v : null;
	}

	/** Updates the account identity. Pass the value of {@code client.getAccountHash()} (-1 when logged out). */
	void setIdentity(long accountHash)
	{
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

	// ---- catalogue reads (static files) -------------------------------------------------------------

	/** The whole index.json: metadata for every approved template, no layouts. */
	private static class Index
	{
		List<RemoteTemplate> templates;
	}

	/** One t/{id}-{rev}.json: the layout for a single revision of a template. */
	static class LayoutFile
	{
		long id;
		int rev;
		int columns;
		List<TabLayout> tabs;
	}

	/**
	 * Fetches the catalogue. Within 60 seconds of the last fetch the cached list is handed back
	 * synchronously and nothing is sent, unless {@code force} is set; Browse's sort, search and paging
	 * all work off that list. The shared OkHttp client's disk cache handles anything longer-lived.
	 */
	void fetchIndex(boolean force, Consumer<List<RemoteTemplate>> onSuccess, Consumer<String> onError)
	{
		if (!isEnabled())
		{
			onError.accept("The community repository is turned off. Enable it in the plugin settings.");
			return;
		}
		final boolean revalidate;
		synchronized (indexLock)
		{
			if (!force && !indexStale && cachedIndex != null
				&& System.currentTimeMillis() - indexFetchedAt < INDEX_TTL_MS)
			{
				onSuccess.accept(cachedIndex);
				return;
			}
			revalidate = force || indexStale;
		}
		final Request.Builder rb = new Request.Builder().url(cdnBase() + "/index.json").get();
		if (revalidate)
		{
			// The file is served with max-age=60, so within that window OkHttp would answer from its own
			// cache and a deliberate refresh (after the player's own share, say) would see nothing new. A
			// conditional request instead: the origin returns 304 when the file really hasn't changed.
			rb.cacheControl(CacheControl.FORCE_NETWORK);
		}
		okHttpClient.newCall(rb.build()).enqueue(new Callback()
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
					final Index index = gson.fromJson(r.body().string(), Index.class);
					final List<RemoteTemplate> list = new ArrayList<>();
					if (index != null && index.templates != null)
					{
						for (RemoteTemplate rt : index.templates)
						{
							if (rt != null)
							{
								list.add(rt);
							}
						}
					}
					final List<RemoteTemplate> frozen = Collections.unmodifiableList(list);
					synchronized (indexLock)
					{
						cachedIndex = frozen;
						indexFetchedAt = System.currentTimeMillis();
						indexStale = false;
					}
					onSuccess.accept(frozen);
				}
				catch (IOException | JsonSyntaxException e)
				{
					onError.accept(CONNECT_ERROR);
				}
			}
		});
	}

	/** Drops the 60 second floor so the next {@link #fetchIndex} asks the origin again. */
	void invalidateIndex()
	{
		synchronized (indexLock)
		{
			indexFetchedAt = 0;
			indexStale = true;
		}
	}

	/** Fetches one template's layout. The file is immutable per revision, so repeat views come from the disk cache. */
	void fetchLayout(long id, int rev, Consumer<LayoutFile> onSuccess, Consumer<String> onError)
	{
		if (!isEnabled())
		{
			onError.accept("The community repository is turned off. Enable it in the plugin settings.");
			return;
		}
		final Request request = new Request.Builder().url(cdnBase() + "/t/" + id + "-" + rev + ".json").get().build();
		okHttpClient.newCall(request).enqueue(new Callback()
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
					if (r.code() == 404)
					{
						// An edit publishes a new revision and removes the old file, so a missing layout means
						// the list this card came from is out of date.
						onError.accept("This template changed since the list was loaded. Refresh the list and try again.");
						return;
					}
					if (!r.isSuccessful() || r.body() == null)
					{
						onError.accept(CONNECT_ERROR);
						return;
					}
					final LayoutFile layout = gson.fromJson(r.body().string(), LayoutFile.class);
					if (layout == null || layout.tabs == null)
					{
						onError.accept("The repository sent a layout the plugin couldn't read.");
						return;
					}
					onSuccess.accept(layout);
				}
				catch (IOException | JsonSyntaxException e)
				{
					onError.accept(CONNECT_ERROR);
				}
			}
		});
	}

	// ---- write API -----------------------------------------------------------------------------------

	/**
	 * The ids this identity owns on the server. A reinstall loses the local owned flags and there is no
	 * sync to restore them, so Browse asks once per character. Any failure yields an empty set.
	 */
	void fetchMine(Consumer<Set<Long>> onDone)
	{
		if (!isEnabled() || !hasIdentity())
		{
			onDone.accept(Collections.emptySet());
			return;
		}
		final HttpUrl url = HttpUrl.parse(apiBase() + "/api/mine").newBuilder()
			.addQueryParameter("clientId", clientId()).build();
		okHttpClient.newCall(new Request.Builder().url(url).get().build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onDone.accept(Collections.emptySet());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				final Set<Long> ids = new HashSet<>();
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						final JsonObject o = gson.fromJson(r.body().string(), JsonObject.class);
						final JsonArray arr = o != null && o.has("ids") && o.get("ids").isJsonArray() ? o.getAsJsonArray("ids") : null;
						if (arr != null)
						{
							for (int i = 0; i < arr.size(); i++)
							{
								ids.add(arr.get(i).getAsLong());
							}
						}
					}
				}
				catch (IOException | RuntimeException e)
				{
					ids.clear();
				}
				onDone.accept(ids);
			}
		});
	}

	/** Creates a new shared template. {@code onSuccess} receives the new repo id. */
	void create(BankTemplate template, String author, boolean anonymous, Consumer<Long> onSuccess, Consumer<String> onError)
	{
		final String bodyJson = gson.toJson(payload(template, author, anonymous));
		final Request.Builder rb = new Request.Builder()
			.url(apiBase() + "/api/templates")
			.post(RequestBody.create(JSON, bodyJson));
		addSig(rb, bodyJson);
		send(rb.build(), body ->
		{
			invalidateIndex();
			final JsonObject o = gson.fromJson(body, JsonObject.class);
			onSuccess.accept(o != null && o.has("id") ? o.get("id").getAsLong() : null);
		}, onError);
	}

	/** Updates a template the user owns, in place. */
	void update(long repoId, BankTemplate template, String author, boolean anonymous, Runnable onSuccess, Consumer<String> onError)
	{
		final String bodyJson = gson.toJson(payload(template, author, anonymous));
		final Request.Builder rb = new Request.Builder()
			.url(apiBase() + "/api/templates/" + repoId)
			.put(RequestBody.create(JSON, bodyJson));
		addSig(rb, bodyJson);
		send(rb.build(), body ->
		{
			invalidateIndex();
			onSuccess.run();
		}, onError);
	}

	void delete(long repoId, Runnable onSuccess, Consumer<String> onError)
	{
		final HttpUrl url = HttpUrl.parse(apiBase() + "/api/templates/" + repoId)
			.newBuilder().addQueryParameter("clientId", clientId()).build();
		final Request.Builder rb = new Request.Builder().url(url).delete();
		addSig(rb, "");
		// A 404 means the template is already gone from the server, which is exactly what delete wants -
		// treat it as success so the local copy can be cleaned up too.
		sendAllowingNotFound(rb.build(), body ->
		{
			invalidateIndex();
			onSuccess.run();
		}, onError);
	}

	// Records an import server-side, deduped to one per account per template. {@code onDone} (if any)
	// runs once the server has responded.
	void recordImport(long repoId, Runnable onDone)
	{
		fireAndForget(repoId, "import", onDone);
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
			.url(apiBase() + "/api/templates/" + repoId + "/" + subPath)
			.post(RequestBody.create(JSON, bodyJson));
		addSig(rb, bodyJson);
		okHttpClient.newCall(rb.build()).enqueue(new Callback()
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
			.url(apiBase() + "/api/templates/" + repoId + "/report")
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
		payload.add("tabs", gson.toJsonTree(template.getTabs()));
		return payload;
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
}
