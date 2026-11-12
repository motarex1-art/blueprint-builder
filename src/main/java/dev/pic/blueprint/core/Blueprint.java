package dev.pic.blueprint.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A loaded build: a box of sizeX * sizeY * sizeZ cells, each pointing into a palette
 * of block-state strings like "minecraft:oak_stairs[facing=north,half=bottom]".
 * Cell value -1 means "not part of the build" (structure void / outside regions).
 */
public final class Blueprint {
	public final String name;
	public final int sizeX, sizeY, sizeZ;
	public final List<String> palette;
	public final int[] cells;
	public String sourceUrl;
	public String videoUrl;

	public Blueprint(String name, int sizeX, int sizeY, int sizeZ, List<String> palette, int[] cells) {
		if ((long) sizeX * sizeY * sizeZ != cells.length) throw new IllegalArgumentException("size mismatch");
		this.name = name;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.palette = palette;
		this.cells = cells;
	}

	public int index(int x, int y, int z) {
		return (y * sizeZ + z) * sizeX + x;
	}

	/** Palette string at a cell, or null if empty/void/air. */
	public String stateAt(int x, int y, int z) {
		int p = cells[index(x, y, z)];
		if (p < 0) return null;
		String s = palette.get(p);
		return isAir(s) ? null : s;
	}

	public static boolean isAir(String state) {
		String id = blockId(state);
		return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air")
				|| id.equals("minecraft:structure_void");
	}

	/** "minecraft:oak_stairs[facing=north]" -> "minecraft:oak_stairs" (adds namespace if missing). */
	public static String blockId(String state) {
		int b = state.indexOf('[');
		String id = (b < 0 ? state : state.substring(0, b)).trim();
		return id.contains(":") ? id : "minecraft:" + id;
	}

	/** "id[a=b,c=d]" -> {a=b, c=d} (ordered). */
	public static Map<String, String> properties(String state) {
		Map<String, String> out = new LinkedHashMap<>();
		int b = state.indexOf('[');
		int e = state.lastIndexOf(']');
		if (b < 0 || e <= b) return out;
		for (String part : state.substring(b + 1, e).split(",")) {
			int eq = part.indexOf('=');
			if (eq > 0) out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
		}
		return out;
	}

	public static String stateString(String id, Map<String, String> props) {
		if (props == null || props.isEmpty()) return id;
		StringBuilder sb = new StringBuilder(id).append('[');
		boolean first = true;
		for (Map.Entry<String, String> en : props.entrySet()) {
			if (!first) sb.append(',');
			sb.append(en.getKey()).append('=').append(en.getValue());
			first = false;
		}
		return sb.append(']').toString();
	}

	/** SNBT block_state compound for /summon block_display. */
	public static String toSnbt(String state) {
		StringBuilder sb = new StringBuilder("{Name:\"").append(blockId(state)).append('"');
		Map<String, String> props = properties(state);
		if (!props.isEmpty()) {
			sb.append(",Properties:{");
			boolean first = true;
			for (Map.Entry<String, String> en : props.entrySet()) {
				if (!first) sb.append(',');
				sb.append(en.getKey().replaceAll("[^a-z0-9_]", "")).append(":\"")
						.append(en.getValue().replaceAll("[^a-z0-9_]", "")).append('"');
				first = false;
			}
			sb.append('}');
		}
		return sb.append('}').toString();
	}

	/** Number of non-air blocks. */
	public int blockCount() {
		boolean[] air = new boolean[palette.size()];
		for (int i = 0; i < air.length; i++) air[i] = isAir(palette.get(i));
		int n = 0;
		for (int c : cells) if (c >= 0 && !air[c]) n++;
		return n;
	}

	/** Count of each palette entry (non-air). */
	public Map<String, Integer> stateCounts() {
		int[] counts = new int[palette.size()];
		for (int c : cells) if (c >= 0) counts[c]++;
		Map<String, Integer> out = new HashMap<>();
		for (int i = 0; i < counts.length; i++) {
			if (counts[i] > 0 && !isAir(palette.get(i))) out.merge(palette.get(i), counts[i], Integer::sum);
		}
		return out;
	}

	/** Lowest and highest layer (y) that contain blocks; {-1,-1} if empty. */
	public int[] usedLayers() {
		int lo = -1, hi = -1;
		for (int y = 0; y < sizeY; y++) {
			boolean any = false;
			for (int z = 0; z < sizeZ && !any; z++)
				for (int x = 0; x < sizeX && !any; x++)
					if (stateAt(x, y, z) != null) any = true;
			if (any) {
				if (lo < 0) lo = y;
				hi = y;
			}
		}
		return new int[] {lo, hi};
	}

	/** Builder that collects blocks at arbitrary coordinates then normalises them. */
	public static final class Collector {
		private final Map<String, Integer> paletteIndex = new HashMap<>();
		private final List<String> palette = new ArrayList<>();
		private final List<int[]> entries = new ArrayList<>();
		private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

		public int paletteId(String state) {
			return paletteIndex.computeIfAbsent(state, s -> {
				palette.add(s);
				return palette.size() - 1;
			});
		}

		public void put(int x, int y, int z, int paletteId) {
			entries.add(new int[] {x, y, z, paletteId});
			minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
			maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
		}

		/** Extend bounds without placing anything (keeps the original box size). */
		public void include(int x, int y, int z) {
			minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
			maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
		}

		public Blueprint build(String name) {
			if (minX == Integer.MAX_VALUE) throw new IllegalStateException("The schematic is empty");
			int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
			long vol = (long) sx * sy * sz;
			if (vol > 64_000_000L) throw new IllegalStateException("Schematic too large (" + sx + "x" + sy + "x" + sz + ")");
			int[] cells = new int[(int) vol];
			java.util.Arrays.fill(cells, -1);
			for (int[] e : entries) {
				cells[((e[1] - minY) * sz + (e[2] - minZ)) * sx + (e[0] - minX)] = e[3];
			}
			return new Blueprint(name, sx, sy, sz, palette, cells);
		}
	}
}
