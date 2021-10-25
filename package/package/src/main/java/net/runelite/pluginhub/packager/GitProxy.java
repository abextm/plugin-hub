package net.runelite.pluginhub.packager;

import com.google.common.io.ByteStreams;
import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

@Slf4j
public class GitProxy implements Closeable
{
	private final HttpServer server;
	private final String targetHost;
	private final String ourHost;

	public GitProxy(OkHttpClient okHttpClient, String targetHost) throws IOException
	{
		assert !targetHost.endsWith("/");
		this.targetHost = targetHost;

		server = HttpServer.create(new InetSocketAddress("localhost", 0), 4);
		server.createContext("/", ex ->
		{
			try
			{
				Request.Builder oReq = new Request.Builder()
					.url(targetHost + ex.getRequestURI().getPath() + "?" + ex.getRequestURI().getRawQuery());

				for (Map.Entry<String, List<String>> entry : ex.getRequestHeaders().entrySet())
				{
					String key = entry.getKey();
					if ("host".equalsIgnoreCase(key))
					{
						continue;
					}
					for (String v : entry.getValue())
					{
						oReq.addHeader(key, v);
					}
				}

				oReq.method(ex.getRequestMethod(), "HEAD".equals(ex.getRequestMethod()) || "GET".equals(ex.getRequestMethod())
					? null
					: new RequestBody()
				{
					@Nullable
					@Override
					public MediaType contentType()
					{
						return MediaType.parse(ex.getRequestHeaders().getFirst("Content-Type"));
					}

					@Override
					public void writeTo(BufferedSink sink) throws IOException
					{
						ByteStreams.copy(ex.getRequestBody(), sink.outputStream());
					}
				});

				okHttpClient.newCall(oReq.build()).enqueue(new Callback()
				{
					@SneakyThrows
					@Override
					public void onFailure(Call call, IOException e)
					{
						log.info("http proxy failed", e);
						ex.sendResponseHeaders(500, 0);
						ex.close();
					}

					@Override
					public void onResponse(Call call, Response response) throws IOException
					{
						try
						{
							ex.getResponseHeaders().putAll(response.headers().toMultimap());
							ex.sendResponseHeaders(response.code(), 0);
							if (response.body() != null)
							{
								ByteStreams.copy(response.body().byteStream(), ex.getResponseBody());
							}
						}
						finally
						{
							ex.close();
						}
					}
				});
			}
			catch (Exception e)
			{
				log.warn("unable to service request", e);
				ex.sendResponseHeaders(500, 0);
				ex.close();
			}
		});
		server.start();

		ourHost = "http://localhost:" + server.getAddress().getPort();
	}

	@Override
	public void close()
	{
		server.stop(1);
	}

	public String rewriteUrl(String url)
	{
		if (url.startsWith(targetHost))
		{
			return ourHost + url.substring(targetHost.length());
		}

		return url;
	}
}
