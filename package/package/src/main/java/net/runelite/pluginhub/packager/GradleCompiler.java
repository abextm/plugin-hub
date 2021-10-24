/*
 * Copyright (c) 2020 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.pluginhub.packager;

import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import net.runelite.pluginhub.uploader.Util;
import org.gradle.tooling.CancellationTokenSource;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.ResultHandler;

@RequiredArgsConstructor
public class GradleCompiler implements Compiler
{
	private static final File GRADLE_HOME;

	static
	{
		try
		{
			GRADLE_HOME = new File(com.google.common.io.Files.asCharSource(new File(Packager.PACKAGE_ROOT, "build/gradleHome"), StandardCharsets.UTF_8).read().trim());
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
		if (!GRADLE_HOME.exists())
		{
			throw new RuntimeException("gradle home has moved");
		}
	}

	private final String runeliteVersion;

	@Override
	public boolean compile(Plugin plugin) throws PluginBuildException, IOException
	{
		try (InputStream is = GradleCompiler.class.getResourceAsStream("verification-metadata.xml"))
		{
			File metadataFile = plugin.file("gradle/verification-metadata.xml");
			metadataFile.getParentFile().mkdir();
			Files.copy(is, metadataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}

		try (ProjectConnection con = GradleConnector.newConnector()
			.forProjectDirectory(plugin.getRepositoryDirectory())
			.useInstallation(GRADLE_HOME)
			.connect())
		{
			CancellationTokenSource cancel = GradleConnector.newCancellationTokenSource();
			BlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);
			String buildSuccess = "success";

			con.newBuild()
				.withArguments(
					"--no-build-cache",
					"--console=plain",
					"--init-script", new File("./package/target_init.gradle").getAbsolutePath())
				.setEnvironmentVariables(ImmutableMap.of(
					"runelite.pluginhub.package.lib", new File(Packager.PACKAGE_ROOT, "initLib/build/libs/initLib.jar").toString(),
					"runelite.pluginhub.package.buildDir", plugin.getBuildDirectory().getAbsolutePath(),
					"runelite.pluginhub.package.runeliteVersion", runeliteVersion))
				.setJvmArguments("-Xmx768M", "-XX:+UseParallelGC")
				.setStandardOutput(plugin.getLog())
				.setStandardError(plugin.getLog())
				.forTasks("runelitePluginHubPackage", "runelitePluginHubManifest")
				.withCancellationToken(cancel.token())
				.run(new ResultHandler<Void>()
				{
					@Override
					public void onComplete(Void result)
					{
						queue.add(buildSuccess);
					}

					@Override
					public void onFailure(GradleConnectionException failure)
					{
						queue.add(failure);
					}
				});
			plugin.getLog().flush();

			Object output = queue.poll(5, TimeUnit.MINUTES);
			if (output == null)
			{
				cancel.cancel();
				throw PluginBuildException.of(plugin, "build did not complete within 5 minutes");
			}
			if (output == buildSuccess)
			{
				Properties chunk = Util.loadProperties(new File(plugin.getBuildDirectory(), "chunk.properties"));

				plugin.setVersion(chunk.getProperty("version"));
				return true;
			}
			else if (output instanceof GradleConnectionException)
			{
				throw PluginBuildException.of(plugin, "build failed", output);
			}
			throw new IllegalStateException(output.toString());
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException(e);
		}
	}
}
