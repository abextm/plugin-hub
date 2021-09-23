package net.runelite.pluginhub.packager;

import com.google.common.base.Splitter;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Bytes;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.pluginhub.uploader.ExternalPluginManifest;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

@Slf4j
public class Deprecate
{
	private static OkHttpClient client;
	private static int tests = 0;
	private static int matches = 0;
	private static int plugins = 0;

	public static void main(String... args) throws Exception
	{
		client = new OkHttpClient.Builder().build();
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

		for (ExternalPluginManifest man : manifests)
		{
			try (Response res = client.newCall(new Request.Builder()
					.url(root.newBuilder()
						.addPathSegment(man.getInternalName())
						.addPathSegment(man.getCommit() + ".jar")
						.build())
					.build())
				.execute())
			{
				testJar(man, res.body());
			}
		}

		//log.info("tested {} files {} matched in {} plugins", tests, matches, plugins);
		log.info("needed {}", needed);
		log.info("needed {}", needed.keySet());
		System.exit(0);
	}

	private static String pathToGH(ExternalPluginManifest manifest, String path)
	{
		path = path.replace(".class", ".java");
		try (Response res = client.newCall(new Request.Builder()
				.url(HttpUrl.get("https://raw.githubusercontent.com/runelite/plugin-hub/master/plugins/")
					.newBuilder()
					.addPathSegment(manifest.getInternalName())
					.build())
				.build())
			.execute())
		{
			Properties p = new Properties();
			p.load(res.body().charStream());
			String repo = (String) p.get("repository");
			return repo.substring(0, repo.length() - 4) + "/tree/" + manifest.getCommit() + "/src/main/java/" + path;
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	static Multimap<String, String> needed = HashMultimap.create();

	private static void testJar(ExternalPluginManifest man, ResponseBody body) throws Exception
	{
		File f = File.createTempFile("plugin", ".jar");
		try
		{
			try (FileOutputStream fos = new FileOutputStream(f))
			{
				ByteStreams.copy(body.byteStream(), fos);
			}
			Process proc = new ProcessBuilder("jdeps", f.toString())
				.inheritIO()
				.redirectOutput(ProcessBuilder.Redirect.PIPE)
				.start();
			new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))
				.lines()
				.map(line -> {
					if (line.endsWith("not found")) {
						return null;
					}
					List<String> v = Splitter.on(' ').omitEmptyStrings().splitToList(line);
					log.info("\t {}", v);
					String module = v.get(v.size() - 1);
					if (module.endsWith(".jar")) {
						return null;
					}
					return module;
				})
				.forEach(m -> needed.put(m, man.getInternalName()));
			int out = proc.waitFor();
			if (out != 0)
			{
				throw new RuntimeException("" + out);
			}
		}
		finally
		{
			f.delete();
		}
	}
/*
	private static void testJar(ExternalPluginManifest man, ResponseBody body) throws Exception
	{
		boolean matchedPlugin = false;
		ZipInputStream jis = new ZipInputStream(new BufferedInputStream(body.byteStream()));
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

	private static boolean test(ExternalPluginManifest manifest, String filePath, InputStream is) throws Exception
	{
		byte[] data = ByteStreams.toByteArray(is);
		if (Bytes.indexOf(data, "net/runelite/client/ui/overlay/worldmap/WorldMapPoint".getBytes(StandardCharsets.UTF_8)) != -1)
		{
			log.warn("{} match", pathToGH(manifest, filePath));
			return true;
		}
		return false;
	}
 //*/
}
