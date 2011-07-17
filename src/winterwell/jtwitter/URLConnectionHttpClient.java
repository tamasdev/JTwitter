package winterwell.jtwitter;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import lgpl.haustein.Base64Encoder;

import org.json.JSONObject;

import winterwell.jtwitter.Twitter.KRequestType;
import winterwell.jtwitter.TwitterException.Timeout;

/**
 * A simple http client that uses the built in URLConnection class.
 * <p>
 * Provides Twitter-focused error-handling, generating the right
 * TwitterException. Also has a retry-on-error mode which can help smooth out
 * Twitter's sometimes intermittent service. See
 * {@link #setRetryOnError(boolean)}.
 *
 * @author Daniel Winterstein
 *
 */
public class URLConnectionHttpClient implements Twitter.IHttpClient,
		Serializable {
	private static final int dfltTimeOutMilliSecs = 10 * 1000;

	private static final long serialVersionUID = 1L;

	/**
	 * Close a reader/writer/stream, ignoring any exceptions that result. Also
	 * flushes if there is a flush() method.
	 *
	 * @param input
	 *            Can be null
	 */
	protected static void close(Closeable input) {
		if (input == null)
			return;
		// Flush (annoying that this is not part of Closeable)
		try {
			Method m = input.getClass().getMethod("flush");
			m.invoke(input);
		} catch (Exception e) {
			// Ignore
		}
		// Close
		try {
			input.close();
		} catch (IOException e) {
			// Ignore
		}
	}

	private static String encode(Object x) {
		try {
			return URLEncoder.encode(String.valueOf(x), "UTF-8");
		} catch (UnsupportedEncodingException e) {
			// This shouldn't happen as UTF-8 is standard
			return URLEncoder.encode(String.valueOf(x));
		}
	}

	/**
	 * Use a bufferred reader (preferably UTF-8) to extract the contents of the
	 * given stream. A convenience method for {@link #toString(Reader)}.
	 */
	protected static String toString(InputStream inputStream) {
		InputStreamReader reader;
		try {
			reader = new InputStreamReader(inputStream, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			reader = new InputStreamReader(inputStream);
		}
		return toString(reader);
	}

	/**
	 * Use a buffered reader to extract the contents of the given reader.
	 *
	 * @param reader
	 * @return The contents of this reader.
	 */
	private static String toString(Reader reader) throws RuntimeException {
		try {
			// Buffer if not already buffered
			reader = reader instanceof BufferedReader ? (BufferedReader) reader
					: new BufferedReader(reader);
			StringBuilder output = new StringBuilder();
			while (true) {
				int c = reader.read();
				if (c == -1) {
					break;
				}
				output.append((char) c);
			}
			return output.toString();
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		} finally {
			close(reader);
		}
	}

	private Map<String, List<String>> headers;

	protected String name;

	private final String password;

	private final Map<KRequestType, RateLimit> rateLimits = new EnumMap(
			KRequestType.class);

	/**
	 * true if we are in the middle of a retry attempt. false normally
	 */
	private boolean retryingFlag;

	/**
	 * If true, will wait 1/2 second and make a 2nd request when presented with a
	 * server error.
	 */
	private boolean retryOnError;

	protected int timeout = dfltTimeOutMilliSecs;

	public URLConnectionHttpClient() {
		this(null, null);
	}

	public URLConnectionHttpClient(String name, String password) {
		this.name = name;
		this.password = password;
		assert (name != null && password != null)
				|| (name == null && password == null);
	}

	@Override
	public boolean canAuthenticate() {
		return name != null && password != null;
	}

	@Override
	public HttpURLConnection connect(String url, Map<String, String> vars,
			boolean authenticate) throws IOException {
		if (vars != null && vars.size() != 0) {
			// add get variables
			StringBuilder uri = new StringBuilder(url);
			if (url.indexOf('?') == -1) {
				uri.append("?");
			} else if (!url.endsWith("&")) {
				uri.append("&");
			}
			for (Entry<String, String> e : vars.entrySet()) {
				if (e.getValue() == null) {
					continue;
				}
				String ek = encode(e.getKey());
				assert !url.contains(ek + "=") : url + " " + vars;
				uri.append(ek + "=" + encode(e.getValue()) + "&");
			}
			url = uri.toString();
		}
		// Setup a connection
		HttpURLConnection connection = (HttpURLConnection) new URL(url)
				.openConnection();
		// Authenticate
		if (authenticate) {
			setAuthentication(connection, name, password);
		}
		// To keep the search API happy - which wants either a referrer or a
		// user agent
		connection.setRequestProperty("User-Agent", "JTwitter/"
				+ Twitter.version);
		connection.setDoInput(true);
		connection.setConnectTimeout(timeout);
		connection.setReadTimeout(timeout);
		connection.setConnectTimeout(timeout);
		// Open a connection
		processError(connection);
		processHeaders(connection);
		return connection;
	}

	protected final void disconnect(HttpURLConnection connection) {
		if (connection == null)
			return;
		try {
			connection.disconnect();
		} catch (Throwable t) {
			// ignore
		}
	}

	private String getErrorStream(HttpURLConnection connection) {
		try {
			return toString(connection.getErrorStream());
		} catch (NullPointerException e) {
			return null;
		}
	}

	@Override
	public String getHeader(String headerName) {
		if (headers == null)
			return null;
		List<String> vals = headers.get(headerName);
		return vals == null || vals.isEmpty() ? null : vals.get(0);
	}

	String getName() {
		return name;
	}

	@Override
	public final String getPage(String url, Map<String, String> vars,
			boolean authenticate) throws TwitterException {
		assert url != null;
		HttpURLConnection connection = null;
		try {
			HttpURLConnection con = connect(url, vars, authenticate);
			InputStream inStream = con.getInputStream();
			// Read in the web page
			String page = toString(inStream);
			// Done
			return page;
		} catch (SocketTimeoutException e) {
			Timeout ex = new TwitterException.Timeout(url);
			return getPage2_retry(url, vars, authenticate, ex);
		} catch (IOException e) {
			throw new TwitterException.IO(e);
		} catch (TwitterException.E50X e) {
			return getPage2_retry(url, vars, authenticate, e);
		} finally {
			disconnect(connection);
		}
	}

	/**
	 * Should we retry? 
	 * @param url
	 * @param vars
	 * @param authenticate
	 * @param originalException
	 * @return page if successful
	 * @throws originalException
	 */
	private String getPage2_retry(String url, Map<String, String> vars,
			boolean authenticate, TwitterException.E50X originalException) 
	{
		if ( ! retryOnError || retryingFlag) throw originalException;
		try {
			retryingFlag = true;
			// wait half a second before retrying
			Thread.sleep(500);
			return getPage(url, vars, authenticate);
		} catch (InterruptedException ex) {
			// ignore the interruption & just throw the original error
			throw originalException;
		} finally {
			retryingFlag = false;
		}
	}

	@Override
	public RateLimit getRateLimit(KRequestType reqType) {
		return rateLimits.get(reqType);
	}

	@Override
	public String post(String uri, Map<String, String> vars,
			boolean authenticate) throws TwitterException {
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(uri).openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			if (authenticate) {
				setAuthentication(connection, name, password);
			}
			connection.setRequestProperty("Content-Type",
					"application/x-www-form-urlencoded");
			connection.setReadTimeout(timeout);
			connection.setConnectTimeout(timeout);
			// build the post body
			String payload = post2_getPayload(vars);
			connection.setRequestProperty("Content-Length",
					"" + payload.length());
			OutputStream os = connection.getOutputStream();
			os.write(payload.getBytes());
			close(os);
			// Get the response
			processError(connection);
			processHeaders(connection);
			String response = toString(connection.getInputStream());
			return response;
		} catch (IOException e) {
			throw new TwitterException(e);
		} catch (TwitterException.E50X e) {
			if (!retryOnError || retryingFlag)
				throw e;
			try {
				Thread.sleep(1000);
				retryingFlag = true;
				return post(uri, vars, authenticate);
			} catch (InterruptedException ex) {
				// ignore the interruption & just throw the original error
				throw e;
			} finally {
				retryingFlag = false;
			}
		} finally {
			disconnect(connection);
		}
	}

	protected String post2_getPayload(Map<String, String> vars) {
		if (vars == null || vars.isEmpty())
			return "";
		StringBuilder encodedData = new StringBuilder();

		for (String key : vars.keySet()) {
			String val = encode(vars.get(key));
			encodedData.append(encode(key));
			encodedData.append('=');
			encodedData.append(val);
			encodedData.append('&');
		}
		encodedData.deleteCharAt(encodedData.length() - 1);
		return encodedData.toString();
	}

	/**
	 * Throw an exception if the connection failed
	 *
	 * @param connection
	 */
	void processError(HttpURLConnection connection) {
		try {
			int code = connection.getResponseCode();
			if (code == 200)
				return;
			URL url = connection.getURL();
			// any explanation?
			String error = connection.getResponseMessage();
			Map<String, List<String>> headers = connection.getHeaderFields();
			List<String> errorMessage = headers.get(null);
			if (errorMessage != null && !errorMessage.isEmpty()) {
				error += "\n" + errorMessage.get(0);
			}
			InputStream es = connection.getErrorStream();
			String errorPage = null;
			if (es != null) {
				errorPage = read(es);
				error += "\n" + errorPage;
			}
			// which error?
			if (code == 401) {
				if (error.contains("Basic authentication is not supported"))
					throw new TwitterException.UpdateToOAuth();
				throw new TwitterException.E401(error + "\n" + url + " ("
						+ (name == null ? "anonymous" : name) + ")");
			}
			if (code == 403) {
				// separate out the 403 cases
				processError2_403(url, error, errorPage);
			}
			if (code == 404) {
				// user deleted?
				if (errorPage != null && errorPage.contains("deleted"))
					// Note: This is a 403 exception
					throw new TwitterException.SuspendedUser(errorPage + "\n"
							+ url);
				throw new TwitterException.E404(error + "\n" + url);
			}
			if (code >= 500 && code < 600)
				throw new TwitterException.E50X(error + "\n" + url);

			// Over the rate limit?
			processError2_rateLimit(connection, code, error);

			// just report it as a vanilla exception
			throw new TwitterException(code + " " + error + " " + url);

		} catch (SocketTimeoutException e) {
			URL url = connection.getURL();
			throw new TwitterException.Timeout(timeout + "milli-secs for "
					+ url);
		} catch (ConnectException e) {
			// probably also a time out
			URL url = connection.getURL();
			throw new TwitterException.Timeout(url.toString());
		} catch (SocketException e) {
			// treat as a server error - because it probably is
			// (yes, it could also be an error at your end)
			throw new TwitterException.E50X(e.toString());
		} catch (IOException e) {
			throw new TwitterException(e);
		}
	}

	private void processError2_403(URL url, String error, String errorPage) {
		// is this a "too old" exception?
		if (errorPage != null) {
			if (errorPage.contains("too old"))
				throw new TwitterException.BadParameter(errorPage + "\n" + url);
			// is this a suspended user exception?
			if (errorPage.contains("suspended"))
				throw new TwitterException.SuspendedUser(errorPage + "\n" + url);
			// this can be caused by looking up is-follower wrt a suspended
			// account
			if (errorPage.contains("Could not find"))
				throw new TwitterException.SuspendedUser(errorPage + "\n" + url);
			if (errorPage.contains("too recent"))
				throw new TwitterException.TooRecent(errorPage + "\n" + url);
			if (errorPage.contains("already requested to follow"))
				throw new TwitterException.Repetition(errorPage + "\n" + url);
			if (errorPage.contains("unable to follow more people"))
				throw new TwitterException.FollowerLimit(name + " " + errorPage);
			if (errorPage.contains("application is not allowed to access")) {
				throw new TwitterException.AccessLevel(name+" "+errorPage);
			}
		}
		throw new TwitterException.E403(error + "\n" + url + " (" + getName()
				+ ")");
	}

	private void processError2_rateLimit(HttpURLConnection connection,
			int code, String error)
	{
		boolean rateLimitExceeded = error.contains("Rate limit exceeded");
		if (rateLimitExceeded) {
			// store the rate limit info
			processHeaders(connection);
			for(KRequestType rt : KRequestType.values()) {
				updateRateLimits(rt);
			}
			throw new TwitterException.RateLimit(getName()+": "+error);
		}
		// The Rate limiter can sometimes cause a 400 Bad Request
		if (code == 400) {
			try {
				String json = getPage(
						"http://twitter.com/account/rate_limit_status.json",
						null, password != null);
				JSONObject obj = new JSONObject(json);
				int hits = obj.getInt("remaining_hits");
				if (hits < 1)
					throw new TwitterException.RateLimit(error);
			} catch (Exception e) {
				// oh well
			}
		}
	}

	/**
	 * Cache headers for {@link #getHeader(String)}
	 *
	 * @param connection
	 */
	protected void processHeaders(HttpURLConnection connection) {
		headers = connection.getHeaderFields();
	}

	private String read(InputStream stream) throws IOException {
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(
					stream));
			final int bufSize = 8192; // this is the default BufferredReader
			// buffer size
			StringBuilder sb = new StringBuilder(bufSize);
			char[] cbuf = new char[bufSize];
			while (true) {
				int chars = reader.read(cbuf);
				if (chars == -1) {
					break;
				}
				sb.append(cbuf, 0, chars);
			}
			return sb.toString();
		} finally {
			stream.close();
		}
	}

	/**
	 * Set a header for basic authentication login.
	 */
	protected void setAuthentication(URLConnection connection, String name,
			String password) {
		assert name != null && password != null : "Authentication requested but no login details are set!";
		String token = name + ":" + password;
		String encoding = Base64Encoder.encode(token);
		connection.setRequestProperty("Authorization", "Basic " + encoding);
	}

	/**
	 * False by default. Setting this to true switches on a robustness
	 * workaround: when presented with a 50X server error, the system will wait
	 * 1/2 a second and make a second attempt.
	 */
	public void setRetryOnError(boolean retryOnError) {
		this.retryOnError = retryOnError;
	}

	@Override
	public void setTimeout(int millisecs) {
		this.timeout = millisecs;
	}

	@Override
	public String toString() {
		return getClass().getName() + "[name=" + name + ", password="
				+ (password == null ? "null" : "XXX") + "]";
	}

	/*
	 * {@link #processHeaders(HttpURLConnection)} MUST have been
	 * called first.
	 */
	@Override
	public void updateRateLimits(KRequestType reqType) {
		String limit = null, remaining = null, reset = null;
		switch (reqType) {
		case NORMAL:
		case SHOW_USER:
			limit = getHeader("X-RateLimit-Limit");
			remaining = getHeader("X-RateLimit-Remaining");
			reset = getHeader("X-RateLimit-Reset");
			break;
		case SEARCH:
		case SEARCH_USERS:
			limit = getHeader("X-FeatureRateLimit-Limit");
			remaining = getHeader("X-FeatureRateLimit-Remaining");
			reset = getHeader("X-FeatureRateLimit-Reset");
			break;
		}
		if (limit != null) {
			rateLimits.put(reqType, new RateLimit(limit, remaining, reset));
		}
	}

}