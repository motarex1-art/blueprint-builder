package dev.pic.blueprint.core;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Reads Sponge .schem (v1/v2/v3, WorldEdit), Litematica .litematic and vanilla structure .nbt
 * into a {@link Blueprint}. Format is detected from the content, not the file name.
 */
public final class SchematicReader {
	private SchematicReader() {}

	public static Blueprint read(String name, byte[] data) throws IOException {
		Map<String, Object> root = Nbt.read(data);
		if (root.containsKey("Regions")) return readLitematic(name, root);
		Map<String, Object> sponge = Nbt.compound(root, "Schematic");
		if (sponge != null && sponge.containsKey("Width")) return readSponge(name, sponge);
		if (root.containsKey("Width") && (root.containsKey("Palette") || root.containsKey("Blocks"))) {
			if (root.get("Blocks") instanceof byte[]) {
				throw new IOException("Old MCEdit .schematic (pre-1.13) is not supported. Re-save it as .schem with WorldEdit or Amulet.");
			}
			return readSponge(name, root);
		}
		if (root.containsKey("size") && root.containsKey("blocks")) return readStructure(name, root);
		throw new IOException("Unknown schematic format");
	}

	/** File extension matching the content (used when a download has no usable name). */
	public static String extensionFor(byte[] data) {
		try {
			Map<String, Object> root = Nbt.read(data);
			if (root.containsKey("Regions")) return ".litematic";
			if (root.containsKey("size") && root.containsKey("blocks")) return ".nbt";
			return ".schem";
		} catch (IOException e) {
			return ".schem";
		}
	}

	// ---------------- Sponge (.schem) ----------------

	private static Blueprint readSponge(String name, Map<String, Object> s) throws IOException {
		int w = Nbt.integer(s, "Width", 0) & 0xFFFF;
		int h = Nbt.integer(s, "Height", 0) & 0xFFFF;
		int l = Nbt.integer(s, "Length", 0) & 0xFFFF;
		Map<String, Object> palette;
		byte[] data;
		Map<String, Object> blocks = Nbt.compound(s, "Blocks"); // v3
		if (blocks != null) {
			palette = Nbt.compound(blocks, "Palette");
			data = blocks.get("Data") instanceof byte[] b ? b : null;
		} else { // v1/v2
			palette = Nbt.compound(s, "Palette");
			data = s.get("BlockData") instanceof byte[] b ? b : null;
		}
		if (palette == null || data == null) throw new IOException("Sponge schematic has no block data");

		String[] byId = new String[palette.size() + 1];
		int maxId = 0;
		for (Map.Entry<String, Object> e : palette.entrySet()) {
			int id = ((Number) e.getValue()).intValue();
			if (id >= byId.length) {
				String[] n = new String[id + 1];
				System.arraycopy(byId, 0, n, 0, byId.length);
				byId = n;
			}
			byId[id] = e.getKey();
			maxId = Math.max(maxId, id);
		}

		Blueprint.Collector c = new Blueprint.Collector();
		int[] map = new int[byId.length];
		for (int i = 0; i < byId.length; i++) map[i] = byId[i] == null ? -1 : c.paletteId(byId[i]);

		c.include(0, 0, 0);
		c.include(w - 1, h - 1, l - 1);
		int pos = 0;
		int total = w * h * l;
		for (int i = 0; i < total; i++) {
			int value = 0, shift = 0;
			while (true) {
				if (pos >= data.length) throw new IOException("Block data ended early");
				int b = data[pos++] & 0xFF;
				value |= (b & 0x7F) << shift;
				if ((b & 0x80) == 0) break;
				shift += 7;
				if (shift > 28) throw new IOException("Bad varint");
			}
			int y = i / (w * l);
			int rem = i % (w * l);
			int z = rem / w;
			int x = rem % w;
			int pid = value < map.length ? map[value] : -1;
			if (pid >= 0) c.put(x, y, z, pid);
		}
		return c.build(name);
	}

	// ---------------- Litematica (.litematic) ----------------

	@SuppressWarnings("unchecked")
	private static Blueprint readLitematic(String name, Map<String, Object> root) throws IOException {
		Map<String, Object> regions = Nbt.compound(root, "Regions");
		Blueprint.Collector c = new Blueprint.Collector();
		boolean any = false;
		for (Object ro : regions.values()) {
			if (!(ro instanceof Map)) continue;
			Map<String, Object> r = (Map<String, Object>) ro;
			Map<String, Object> pos = Nbt.compound(r, "Position");
			Map<String, Object> size = Nbt.compound(r, "Size");
			List<Object> pal = Nbt.list(r, "BlockStatePalette");
			Object bs = r.get("BlockStates");
			if (pos == null || size == null || pal == null || !(bs instanceof long[] arr)) continue;

			int sx = Nbt.integer(size, "x", 0), sy = Nbt.integer(size, "y", 0), sz = Nbt.integer(size, "z", 0);
			int px = Nbt.integer(pos, "x", 0), py = Nbt.integer(pos, "y", 0), pz = Nbt.integer(pos, "z", 0);
			// Negative sizes mean the region extends towards negative axis from Position.
			int ox = px + (sx < 0 ? sx + 1 : 0);
			int oy = py + (sy < 0 ? sy + 1 : 0);
			int oz = pz + (sz < 0 ? sz + 1 : 0);
			int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
			if (ax == 0 || ay == 0 || az == 0) continue;

			int[] map = new int[pal.size()];
			for (int i = 0; i < pal.size(); i++) {
				Map<String, Object> e = (Map<String, Object>) pal.get(i);
				String id = Nbt.string(e, "Name", "minecraft:air");
				Map<String, Object> props = Nbt.compound(e, "Properties");
				java.util.Map<String, String> p = new java.util.TreeMap<>();
				if (props != null) for (Map.Entry<String, Object> pe : props.entrySet()) p.put(pe.getKey(), String.valueOf(pe.getValue()));
				map[i] = c.paletteId(Blueprint.stateString(id, p));
			}

			int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(pal.size() - 1));
			long mask = (1L << bits) - 1L;
			long volume = (long) ax * ay * az;
			if ((volume * bits + 63) / 64 > arr.length) throw new IOException("Litematic block array too short");

			c.include(ox, oy, oz);
			c.include(ox + ax - 1, oy + ay - 1, oz + az - 1);
			for (int y = 0; y < ay; y++) {
				for (int z = 0; z < az; z++) {
					for (int x = 0; x < ax; x++) {
						long index = ((long) y * az + z) * ax + x;
						int v = (int) getPacked(arr, index, bits, mask);
						if (v < map.length) {
							c.put(ox + x, oy + y, oz + z, map[v]);
							any = true;
						}
					}
				}
			}
		}
		if (!any) throw new IOException("Litematic has no readable regions");
		return c.build(name);
	}

	static long getPacked(long[] arr, long index, int bits, long mask) {
		long startBit = index * bits;
		int startLong = (int) (startBit >> 6);
		int endLong = (int) (((index + 1) * bits - 1) >> 6);
		int offset = (int) (startBit & 63);
		if (startLong == endLong) {
			return (arr[startLong] >>> offset) & mask;
		}
		int endOffset = 64 - offset;
		return ((arr[startLong] >>> offset) | (arr[endLong] << endOffset)) & mask;
	}

	// ---------------- Vanilla structure (.nbt) ----------------

	@SuppressWarnings("unchecked")
	private static Blueprint readStructure(String name, Map<String, Object> root) throws IOException {
		List<Object> size = Nbt.list(root, "size");
		List<Object> pal = Nbt.list(root, "palette");
		if (pal == null) {
			List<Object> pals = Nbt.list(root, "palettes");
			if (pals != null && !pals.isEmpty()) pal = (List<Object>) pals.get(0);
		}
		List<Object> blocks = Nbt.list(root, "blocks");
		if (size == null || size.size() < 3 || pal == null || blocks == null) throw new IOException("Bad structure file");

		Blueprint.Collector c = new Blueprint.Collector();
		int[] map = new int[pal.size()];
		for (int i = 0; i < pal.size(); i++) {
			Map<String, Object> e = (Map<String, Object>) pal.get(i);
			String id = Nbt.string(e, "Name", "minecraft:air");
			Map<String, Object> props = Nbt.compound(e, "Properties");
			java.util.Map<String, String> p = new java.util.TreeMap<>();
			if (props != null) for (Map.Entry<String, Object> pe : props.entrySet()) p.put(pe.getKey(), String.valueOf(pe.getValue()));
			map[i] = c.paletteId(Blueprint.stateString(id, p));
		}
		c.include(0, 0, 0);
		c.include(((Number) size.get(0)).intValue() - 1, ((Number) size.get(1)).intValue() - 1, ((Number) size.get(2)).intValue() - 1);
		for (Object bo : blocks) {
			Map<String, Object> b = (Map<String, Object>) bo;
			List<Object> p = Nbt.list(b, "pos");
			int st = Nbt.integer(b, "state", -1);
			if (p == null || p.size() < 3 || st < 0 || st >= map.length) continue;
			c.put(((Number) p.get(0)).intValue(), ((Number) p.get(1)).intValue(), ((Number) p.get(2)).intValue(), map[st]);
		}
		return c.build(name);
	}
}
