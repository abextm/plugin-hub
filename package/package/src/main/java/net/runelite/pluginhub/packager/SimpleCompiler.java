package net.runelite.pluginhub.packager;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SimpleCompiler implements Compiler
{
	private static final Pattern WHITESPACE_PATTERN = Pattern.compile("[ \t\n\r]+");
	private static final Pattern TAG_PATTERN = Pattern.compile("<([A-Z]+)>");

	private static final Pattern SETTINGS_PATTERN = loadPattern("settings.gradle");
	private static final Pattern BUILD_PATTERN = loadPattern("build.gradle");

	// There is some particular magic that goes on with this constant, see Gradle's ZipCopyAction for details
	public static final long JAR_ZERO_TIME = new GregorianCalendar(1980, Calendar.FEBRUARY, 1, 0, 0, 0).getTimeInMillis();

	private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

	@Override
	public synchronized boolean compile(Plugin plugin) throws PluginBuildException, IOException
	{
		Context c = new Context(plugin);
		return c.compile();
	}

	@RequiredArgsConstructor
	class Context
	{
		private final Plugin plugin;

		boolean compile() throws IOException, PluginBuildException
		{
			if (!testFile(plugin.file("settings.gradle"), SETTINGS_PATTERN)
				|| !testFile(plugin.file("build.gradle"), BUILD_PATTERN)
				|| plugin.file("gradle.properties").exists())
			{
				return false;
			}

			Writer writer = new OutputStreamWriter(plugin.getLog());
			try
			{
				DiagnosticListener<? super JavaFileObject> diagnosticListener = diag -> plugin.writeLog("javac: {}", diag);
				StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnosticListener, Locale.ROOT, StandardCharsets.UTF_8);
				List<String> options = ImmutableList.of(
					"--release", "8",
					"--class-path", new File(Packager.PACKAGE_ROOT, "platformLib/build/libs/platformLib.jar").toString(),
					"-g",
					"-Xlint:deprecation"
				);

				Path[] sources = Files.walk(plugin.file("src/main/java").toPath(), FileVisitOption.FOLLOW_LINKS)
					.filter(p -> !Files.isDirectory(p) && p.toString().endsWith(".java"))
					.sorted(Comparator.comparing(Path::toString))
					.toArray(Path[]::new);
				Iterable<? extends JavaFileObject> cus = fileManager.getJavaFileObjects(sources);
				Map<String, byte[]> files = new LinkedHashMap<>();

				JavaFileManager realFileManager = new ForwardingJavaFileManager<>(fileManager)
				{
					@Override
					public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) throws IOException
					{
						if (location == StandardLocation.CLASS_OUTPUT)
						{
							String fileName = className.replace('.', '/') + (kind == JavaFileObject.Kind.CLASS ? ".class" : "");
							return new SimpleJavaFileObject(URI.create("memory:///" + fileName), kind)
							{
								@Override
								public OutputStream openOutputStream() throws IOException
								{
									return new ByteArrayOutputStream()
									{
										@Override
										public void close() throws IOException
										{
											super.close();
											files.put(fileName, toByteArray());
										}
									};
								}
							};
						}
						return super.getJavaFileForOutput(location, className, kind, sibling);
					}
				};

				JavaCompiler.CompilationTask task = compiler.getTask(writer, realFileManager, diagnosticListener, options, null, cus);
				if (!task.call())
				{
					throw PluginBuildException.of(plugin, "javac failed");
				}

				{
					Manifest manifest = new Manifest();
					manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
					ByteArrayOutputStream bos = new ByteArrayOutputStream();
					manifest.write(bos);
					files.put("META-INF/MANIFEST.MF", bos.toByteArray());
				}

				try(JarOutputStream jos = new JarOutputStream(new FileOutputStream(plugin.getJarFile())))
				{
					for(Map.Entry<String, byte[]> entry : files.entrySet().stream()
						.sorted(Comparator.comparing(Map.Entry::getKey))
						.collect(Collectors.toList()))
					{
						JarEntry je = new JarEntry(entry.getKey());
						je.setTime(JAR_ZERO_TIME);
						jos.putNextEntry(je);
						jos.write(entry.getValue());
					}
				}
			}
			finally
			{
				writer.flush();
			}

			return true;
		}

		boolean testFile(File file, Pattern pattern) throws IOException
		{
			if (!file.exists())
			{
				return false;
			}

			String s = Files.readString(file.toPath(), StandardCharsets.UTF_8);
			Matcher m = pattern.matcher(s);
			if (!m.find())
			{
				return false;
			}

			withGroup(m, "version", version ->
			{
				version = version.substring(1, version.length() - 1);
				plugin.setVersion(version);
			});

			return true;
		}
	}

	private static void withGroup(Matcher m, String name, Consumer<String> consumer)
	{
		String v;
		try
		{
			v = m.group(name);
		}
		catch (IllegalArgumentException ignored)
		{
			return;
		}

		if (Strings.isNullOrEmpty(v))
		{
			return;
		}

		consumer.accept(v);
	}

	private static Pattern loadPattern(String name)
	{
		try
		{
			byte[] data = ByteStreams.toByteArray(SimpleCompiler.class.getResourceAsStream(name + ".regex"));
			String regex = new String(data, StandardCharsets.UTF_8);
			regex = replaceAllLiteral(WHITESPACE_PATTERN, regex, r -> "[ \\t\\n\\r]*");
			regex = replaceAllLiteral(TAG_PATTERN, regex, r ->
			{
				String tag = r.group(1);
				switch (tag)
				{
					case "STRING":
						return "(\"[^\"$]*\"|'[^']*')";
					default:
						throw new RuntimeException("bad tag" + tag);
				}
			});
			return Pattern.compile("^" + regex + "$");
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	private static String replaceAllLiteral(Pattern pattern, String string, Function<MatchResult, String> replacer)
	{
		Matcher m = pattern.matcher(string);
		StringBuilder sb = new StringBuilder();
		for (; m.find(); )
		{
			m.appendReplacement(sb, "");
			sb.append(replacer.apply(m));
		}
		m.appendTail(sb);
		return sb.toString();
	}
}
