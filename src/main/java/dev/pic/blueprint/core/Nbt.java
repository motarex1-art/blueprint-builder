package dev.pic.blueprint.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Tiny standalone NBT reader (no Minecraft classes), so schematic parsing does
 * not depend on version-specific NBT APIs.
 * Compounds -> Map<String,Object>, lists -> List<Object>, arrays -> byte[]/int[]/long[].
 */
public final class Nbt {
	private Nbt() {}

	public static Map<String, Object> read(byte[] data) throws IOException {
		InputStream in = new ByteArrayInputStream(data);
		if (data.length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
			in = new GZIPInputStream(in);
		} else if (data.length >= 2 && (data[0] & 0xFF) == 0x78) {
			in = new InflaterInputStream(in);
		}
		DataInputStream din = new DataInputStream(new BufferedInputStream(in));
		int type = din.readUnsignedByte();
		if (type != 10) throw new IOException("Not an NBT file (root tag type " + type + ")");
		readString(din); // root name
		return readCompound(din, 0);
	}

	private static String readString(DataInputStream in) throws IOException {
		int len = in.readUnsignedShort();
		byte[] b = new byte[len];
		in.readFully(b);
		return decodeModifiedUtf8(b);
	}

	private static String decodeModifiedUtf8(byte[] b) {
		// Java's modified UTF-8 differs only for NUL and supplementary chars; plain UTF-8 is fine for ids.
		return new String(b, StandardCharsets.UTF_8);
	}

	private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
		if (depth > 512) throw new IOException("NBT too deep");
		Map<String, Object> map = new LinkedHashMap<>();
		while (true) {
			int type = in.readUnsignedByte();
			if (type == 0) return map;
			String name = readString(in);
			map.put(name, readPayload(in, type, depth + 1));
		}
	}

	private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
		switch (type) {
			case 1: return in.readByte();
			case 2: return in.readShort();
			case 3: return in.readInt();
			case 4: return in.readLong();
			case 5: return in.readFloat();
			case 6: return in.readDouble();
			case 7: {
				int n = checkLen(in.readInt());
				byte[] a = new byte[n];
				in.readFully(a);
				return a;
			}
			case 8: return readString(in);
			case 9: {
				int elemType = in.readUnsignedByte();
				int n = checkLen(in.readInt());
				List<Object> list = new ArrayList<>(Math.min(n, 1 << 16));
				for (int i = 0; i < n; i++) list.add(readPayload(in, elemType, depth + 1));
				return list;
			}
			case 10: return readCompound(in, depth);
			case 11: {
				int n = checkLen(in.readInt());
				int[] a = new int[n];
				for (int i = 0; i < n; i++) a[i] = in.readInt();
				return a;
			}
			case 12: {
				int n = checkLen(in.readInt());
				long[] a = new long[n];
				for (int i = 0; i < n; i++) a[i] = in.readLong();
				return a;
			}
			default: throw new IOException("Unknown NBT tag type " + type);
		}
	}

	private static int checkLen(int n) throws IOException {
		if (n < 0 || n > 256_000_000) throw new IOException("Bad NBT length " + n);
		return n;
	}

	// ---- helpers ----

	@SuppressWarnings("unchecked")
	public static Map<String, Object> compound(Map<String, Object> m, String key) {
		Object o = m.get(key);
		return o instanceof Map ? (Map<String, Object>) o : null;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> list(Map<String, Object> m, String key) {
		Object o = m.get(key);
		return o instanceof List ? (List<Object>) o : null;
	}

	public static int integer(Map<String, Object> m, String key, int def) {
		Object o = m.get(key);
		return o instanceof Number n ? n.intValue() : def;
	}

	public static String string(Map<String, Object> m, String key, String def) {
		Object o = m.get(key);
		return o instanceof String s ? s : def;
	}
}
