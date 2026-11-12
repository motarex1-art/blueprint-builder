package dev.pic.blueprint;

import dev.pic.blueprint.core.Blueprint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** Per-player state: the loaded blueprint, where it sits, preview and build progress. */
final class Session {
	Blueprint blueprint;
	/** Palette resolved to block states (null = unknown block in this version). */
	BlockState[] states;
	int unknownBlocks;

	ServerLevel level;
	BlockPos origin;
	Rotation rotation = Rotation.NONE;

	/** -1 = whole build, otherwise only this layer (0-based, bottom = 0). */
	int layer = -1;
	boolean previewOn;
	final ArrayDeque<String> previewQueue = new ArrayDeque<>();

	BuildJob job;
	BuildJob lastFinished;

	String videoUrl;
	List<String> videoLinks = new ArrayList<>();

	boolean hasBlueprint() {
		return blueprint != null;
	}

	boolean placed() {
		return origin != null && level != null;
	}

	/** Rotated footprint size along X / Z. */
	int sizeXRot() {
		return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90 ? blueprint.sizeZ : blueprint.sizeX;
	}

	int sizeZRot() {
		return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90 ? blueprint.sizeX : blueprint.sizeZ;
	}

	/** World position of blueprint cell (x,y,z) with the current rotation. */
	BlockPos worldPos(int x, int y, int z) {
		int sx = blueprint.sizeX, sz = blueprint.sizeZ;
		int rx, rz;
		switch (rotation) {
			case CLOCKWISE_90 -> { rx = sz - 1 - z; rz = x; }
			case CLOCKWISE_180 -> { rx = sx - 1 - x; rz = sz - 1 - z; }
			case COUNTERCLOCKWISE_90 -> { rx = z; rz = sx - 1 - x; }
			default -> { rx = x; rz = z; }
		}
		return origin.offset(rx, y, rz);
	}

	/** Target state for a cell (rotated), or null if nothing should be placed there. */
	BlockState target(int x, int y, int z) {
		int p = blueprint.cells[blueprint.index(x, y, z)];
		if (p < 0) return null;
		BlockState s = states[p];
		if (s == null) return null;
		return rotation == Rotation.NONE ? s : s.rotate(rotation);
	}

	static final class BuildJob {
		final int[] order; // cell indices to place, bottom layer first
		int cursor;
		int speed;
		boolean paused;
		boolean clearAir;
		boolean survival;
		int placed, skippedMissing, skippedBlocked, skippedNoItem;
		// undo data
		final List<BlockPos> undoPos = new ArrayList<>();
		final List<BlockState> undoState = new ArrayList<>();

		BuildJob(int[] order, int speed) {
			this.order = order;
			this.speed = speed;
		}

		boolean done() {
			return cursor >= order.length;
		}
	}
}
