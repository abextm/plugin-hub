package net.runelite.pluginhub.packager;

import com.google.common.io.ByteStreams;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CLOC
{
	public static void main(String... args) throws Exception
	{
		Map<String, Integer> files = new HashMap<>();
		Map<String, Integer> code = new HashMap<>();
		for (File f : Packager.listAllPlugins())
		{
			try (Plugin p = new Plugin(f))
			{
				p.download();
				List<String> paths = Stream.of(new String[]{"build.gradle", "settings.gradle", "runelite-plugin.properties", "src/"})
					.map(v -> new File(p.repositoryDirectory, v).getAbsolutePath())
					.collect(Collectors.toList());
				paths.add(0, "--csv");
				paths.add(0, "--quiet");
				paths.add(0, "cloc");
				Process proc = new ProcessBuilder(paths.toArray(new String[0]))
					.redirectOutput(ProcessBuilder.Redirect.PIPE)
					.start();
				String s = new String(ByteStreams.toByteArray(proc.getInputStream()));
				String[] lines = s.trim().split("\n");
				for (int i = 1; i< lines.length; i++)
				{
					String line = lines[i];
					String[] bits = line.split(",");
					// files type blank commment code
					files.compute(bits[1], (k, v) -> (v == null ? 0 : v) + Integer.parseInt(bits[0]));
					code.compute(bits[1], (k, v) -> (v == null ? 0 : v) + Integer.parseInt(bits[4]));
				}
			}
		}

		System.out.printf("type\tfiles\tcode\n");
		for (String type : files.keySet())
		{
			System.out.printf("%s\t%d\t%d\n", type, files.get(type), code.get(type));
		}
	}
}
