package com.banktemplates;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
	// The community repository endpoint. Not a user setting (the plugin has to call it regardless).
	private static final String REPO_URL = "https://bank-templates-repo.spryt.workers.dev";
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

	private String baseUrl()
	{
		return REPO_URL;
	}

	void search(String query, String sort, int offset, Consumer<Page> onSuccess, Consumer<String> onError)
	{
		if (!isEnabled())
		{
			onError.accept("The community repository is turned off. Enable it in the plugin settings.");
			return;
		}

		final HttpUrl base = HttpUrl.parse(baseUrl() + "/api/templates");
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
				onError.accept("Could not reach the repository.");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						onError.accept("Repository returned an error (" + r.code() + ").");
						return;
					}
					final Page page = gson.fromJson(r.body().string(), PAGE_TYPE);
					onSuccess.accept(page != null ? page : new Page());
				}
				catch (IOException | JsonSyntaxException e)
				{
					onError.accept("Could not read the repository response.");
				}
			}
		});
	}

	/** Creates a new shared template. {@code onSuccess} receives the new repo id. */
	void create(BankTemplate template, String author, boolean anonymous, Consumer<Long> onSuccess, Consumer<String> onError)
	{
		final String bodyJson = gson.toJson(payload(template, author, anonymous));
		final Request.Builder rb = new Request.Builder()
			.url(baseUrl() + "/api/templates")
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
			.url(baseUrl() + "/api/templates/" + repoId)
			.put(RequestBody.create(JSON, bodyJson));
		addSig(rb, bodyJson);
		send(rb.build(), body -> onSuccess.run(), onError);
	}

	void delete(long repoId, Runnable onSuccess, Consumer<String> onError)
	{
		final HttpUrl url = HttpUrl.parse(baseUrl() + "/api/templates/" + repoId)
			.newBuilder().addQueryParameter("clientId", clientId()).build();
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
			.url(baseUrl() + "/api/templates/" + repoId + "/" + subPath)
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
			.url(baseUrl() + "/api/templates/" + repoId + "/report")
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

	static class Page
	{
		List<RemoteTemplate> templates;
		boolean hasMore;
		// Total number of templates matching the current search/filter (across all pages), for the
		// "Count: N" label and "Page X of N" pager. Older servers omit it, so it defaults to 0.
		int total;
	}
}
