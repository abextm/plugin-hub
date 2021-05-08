package net.runelite.pluginhub.packager;

import com.google.common.io.ByteStreams;
import com.google.common.primitives.Bytes;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.pluginhub.uploader.ExternalPluginManifest;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;

@Slf4j
public class Deprecate
{
	public static void main(String... args) throws Exception
	{
		OkHttpClient client = new OkHttpClient.Builder().build();
		String version;
		try (Response res = client.newCall(new Request.Builder()
			.url("https://raw.githubusercontent.com/runelite/plugin-hub/master/runelite.version")
			.build())
			.execute())
		{
			version = res.body().string().trim();
		}

		if (version.length() > 10)
		{
			throw new RuntimeException(version);
		}

		HttpUrl root = HttpUrl.get("https://repo.runelite.net/plugins/")
			.newBuilder()
			.addPathSegment(version)
			.build();

		Gson gson = new Gson();

		List<ExternalPluginManifest> manifests;
		try (Response res = client.newCall(new Request.Builder()
			.url(root.newBuilder().addPathSegment("manifest.js").build())
			.build())
			.execute())
		{

			BufferedSource src = res.body().source();

			byte[] signature = new byte[src.readInt()];
			src.readFully(signature);

			byte[] data = src.readByteArray();

			manifests = gson.fromJson(new String(data, StandardCharsets.UTF_8),
				new TypeToken<List<ExternalPluginManifest>>()
				{
				}.getType());
		}

		int tests = 0;
		int matches = 0;
		int plugins = 0;
		for (ExternalPluginManifest man : manifests)
		{
			boolean matchedPlugin = false;
			try (Response res = client.newCall(new Request.Builder()
				.url(root.newBuilder()
					.addPathSegment(man.getInternalName())
					.addPathSegment(man.getCommit() + ".jar")
					.build())
				.build())
				.execute())
			{
				ZipInputStream jis = new ZipInputStream(new BufferedInputStream(res.body().byteStream()));
				for (ZipEntry je; (je = jis.getNextEntry()) != null; )
				{
					if (test(man, je.getName(), jis))
					{
						matches++;
						if (!matchedPlugin)
						{
							plugins++;
							matchedPlugin = true;
						}
					}
					tests++;
				}
			}
		}

		log.info("tested {} files {} matched in {} plugins", tests, matches, plugins);
		System.exit(0);
	}

	private static boolean test(ExternalPluginManifest manifest, String filePath, InputStream is) throws Exception
	{
		byte[] data = ByteStreams.toByteArray(is);
		if (Bytes.indexOf(data, "net/runelite/api/widgets/WidgetInfo".getBytes(StandardCharsets.UTF_8)) != -1)
		{
			log.warn("{}: {} match", manifest.getSupport(), filePath);
			return true;
		}
		return false;
	}
}
