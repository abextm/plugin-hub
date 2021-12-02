/*
 * Copyright (c) 2021 Abex
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
package net.runelite.pluginhub.apirecorder;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import lombok.Getter;
import org.objectweb.asm.ClassReader;

public class ClassReferenceTester
{
	private final TreeSet<String> api;
	private final Map<String, Boolean> shouldRecord = new HashMap<>();

	@Getter
	private final SortedSet<String> failures = new TreeSet<>();

	public ClassReferenceTester(API api)
	{
		this.api = new TreeSet<>(api.getApis());
	}

	public void ignoreClass(String className)
	{
		String prefix = "L" + className + ";";
		failures.subSet(prefix, prefix + Character.MAX_VALUE).clear();
	}

	public void recordReferences(ClassReader cr)
	{
		char[] buffer = new char[cr.getMaxStringLength()];

		for (int i = 1; i < cr.getItemCount(); i++)
		{
			int offset = cr.getItem(i);
			if (offset <= 0)
			{
				// size 2
				continue;
			}
			int type = cr.readByte(offset - 1);
			switch (type)
			{
				case 9: //fieldref info
				case 10: // methodref info
				case 11: // interface methodref info
				{
					String clazz = cr.readClass(offset, buffer);
					int nat = cr.getItem(cr.readUnsignedShort(offset + 2));
					String name = cr.readUTF8(nat, buffer);
					String desc = cr.readUTF8(nat + 2, buffer);
					if (type == 9)
					{
						testFieldInfo(clazz, name, desc);
					}
					else
					{
						testMethodInfo(clazz, name, desc);
					}
				}
				break;
			}
		}
	}

	private boolean shouldRecord(String clazz)
	{
		if (clazz.startsWith("java/") || clazz.startsWith("["))
		{
			return false;
		}

		return shouldRecord.computeIfAbsent(clazz, name ->
			ClassLoader.getPlatformClassLoader()
				.getResource(name + ".class") == null);
	}

	private void testFieldInfo(String clazz, String name, String desc)
	{
		if (!shouldRecord(clazz))
		{
			return;
		}

		String apiName = "L" + clazz + ";." + name + ":" + desc + ":";
		if (!api.subSet(apiName, apiName + Character.MAX_VALUE)
			.stream()
			.anyMatch(l -> l.charAt(apiName.length()) != '.'))
		{
			failures.add(apiName);
		}
	}

	private void testMethodInfo(String clazz, String name, String desc)
	{
		if (!shouldRecord(clazz))
		{
			return;
		}

		String apiName = "L" + clazz + ";." + name + desc + ":";
		if (!api.subSet(apiName, apiName + Character.MAX_VALUE)
			.stream()
			.anyMatch(l -> l.charAt(apiName.length()) != '.'))
		{
			failures.add(apiName);
		}
	}
}
