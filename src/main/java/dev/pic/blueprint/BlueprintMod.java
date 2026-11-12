package dev.pic.blueprint;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.pic.blueprint.core.Blueprint;
import dev.pic.blueprint.core.Links;
import dev.pic.blueprint.core.SchematicReader;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlueprintMod implements ModInitializer {
	public static final String MOD_ID = "blueprint_builder";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int PREVIEW_LIMIT = 6000;
	private static final int PREVIEW_PER_TICK = 150;
	private static final int PAGE_SIZE = 8;

	private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
		ServerTickEvents.END_SERVER_TICK.register(this::tick);
		LOGGER.info("Blueprint Builder ready. Put schematics in {}", schematicsDir());
	}

	// =====================================================================
	// Commands
	// =====================================================================

	private void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("bp")
				.executes(this::help)
				.then(Commands.literal("help").executes(this::help))
				.then(Commands.literal("list").executes(this::list))
				.then(Commands.literal("load")
						.then(Commands.argument("file", StringArgumentType.greedyString())
								.suggests((ctx, b) -> SharedSuggestionProvider.suggest(schematicFiles(), b))
								.executes(ctx -> load(ctx, StringArgumentType.getString(ctx, "file")))))
				.then(Commands.literal("url")
						.then(Commands.argument("link", StringArgumentType.greedyString())
								.executes(ctx -> url(ctx, StringArgumentType.getString(ctx, "link")))))
				.then(Commands.literal("video")
						.then(Commands.argument("link", StringArgumentType.greedyString())
								.executes(ctx -> video(ctx, StringArgumentType.getString(ctx, "link")))))
				.then(Commands.literal("info").executes(this::info))
				.then(Commands.literal("materials")
						.executes(ctx -> materials(ctx, 1))
						.then(Commands.argument("page", IntegerArgumentType.integer(1))
								.executes(ctx -> materials(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
				.then(Commands.literal("here").executes(this::here))
				.then(Commands.literal("rotate").executes(this::rotate))
				.then(Commands.literal("move")
						.then(Commands.argument("dx", IntegerArgumentType.integer())
								.then(Commands.argument("dy", IntegerArgumentType.integer())
										.then(Commands.argument("dz", IntegerArgumentType.integer())
												.executes(ctx -> move(ctx, IntegerArgumentType.getInteger(ctx, "dx"),
														IntegerArgumentType.getInteger(ctx, "dy"), IntegerArgumentType.getInteger(ctx, "dz")))))))
				.then(Commands.literal("preview")
						.executes(ctx -> preview(ctx, null))
						.then(Commands.literal("on").executes(ctx -> preview(ctx, true)))
						.then(Commands.literal("off").executes(ctx -> preview(ctx, false))))
				.then(Commands.literal("layer")
						.then(Commands.literal("all").executes(ctx -> layer(ctx, -1, false)))
						.then(Commands.literal("up").executes(ctx -> layer(ctx, 1, true)))
						.then(Commands.literal("down").executes(ctx -> layer(ctx, -1, true)))
						.then(Commands.argument("n", IntegerArgumentType.integer(1))
								.executes(ctx -> layer(ctx, IntegerArgumentType.getInteger(ctx, "n") - 1, false))))
				.then(Commands.literal("build")
						.executes(ctx -> build(ctx, 10, false))
						.then(Commands.literal("clear").executes(ctx -> build(ctx, 10, true)))
						.then(Commands.argument("speed", IntegerArgumentType.integer(1, 2000))
								.executes(ctx -> build(ctx, IntegerArgumentType.getInteger(ctx, "speed"), false))
								.then(Commands.literal("clear")
										.executes(ctx -> build(ctx, IntegerArgumentType.getInteger(ctx, "speed"), true)))))
				.then(Commands.literal("pause").executes(ctx -> pause(ctx, true)))
				.then(Commands.literal("resume").executes(ctx -> pause(ctx, false)))
				.then(Commands.literal("cancel").executes(this::cancel))
				.then(Commands.literal("undo").executes(this::undo))
				.then(Commands.literal("unload").executes(this::unload)));
	}

	private Session session(ServerPlayer p) {
		return sessions.computeIfAbsent(p.getUUID(), u -> new Session());
	}

	// ---------------------------------------------------------------- help

	private int help(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		p.sendSystemMessage(title("Blueprint Builder"));
		helpLine(p, "/bp list", "Show schematics in your schematics folder");
		helpLine(p, "/bp url <link>", "Download a .schem / .litematic / .nbt from a link");
		helpLine(p, "/bp video <link>", "Find the schematic download inside a video's description");
		helpLine(p, "/bp materials", "What blocks you need / have / are missing");
		helpLine(p, "/bp preview", "Ghost preview of the build (toggle)");
		helpLine(p, "/bp layer 1", "Show one layer only  (/bp layer up, down, all)");
		helpLine(p, "/bp here", "Move the build in front of you");
		helpLine(p, "/bp rotate", "Rotate 90 degrees");
		helpLine(p, "/bp move 0 1 0", "Shift the build");
		helpLine(p, "/bp build", "Build it!  (/bp build 40 = faster, /bp build clear = also clear air)");
		helpLine(p, "/bp pause | resume | cancel | undo", "Control the build");
		p.sendSystemMessage(Component.literal("Folder: " + schematicsDir()).withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	private static void helpLine(ServerPlayer p, String cmd, String desc) {
		String suggest = cmd.contains("<") ? cmd.substring(0, cmd.indexOf('<')) : cmd.split(" \\|")[0];
		p.sendSystemMessage(Component.literal(" " + cmd).withStyle(s -> s.withColor(ChatFormatting.AQUA)
						.withClickEvent(new ClickEvent.SuggestCommand(suggest)))
				.append(Component.literal("  " + desc).withStyle(ChatFormatting.GRAY)));
	}

	// ---------------------------------------------------------------- loading

	private static Path schematicsDir() {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
		}
		return dir;
	}

	private static boolean isSchematic(String n) {
		String l = n.toLowerCase(Locale.ROOT);
		return l.endsWith(".schem") || l.endsWith(".litematic") || l.endsWith(".nbt");
	}

	private static List<String> schematicFiles() {
		Path dir = schematicsDir();
		List<String> out = new ArrayList<>();
		try (Stream<Path> s = Files.walk(dir, 3)) {
			s.filter(Files::isRegularFile)
					.filter(f -> isSchematic(f.getFileName().toString()))
					.forEach(f -> out.add(dir.relativize(f).toString().replace('\\', '/')));
		} catch (IOException ignored) {
		}
		out.sort(String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	private int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		List<String> files = schematicFiles();
		p.sendSystemMessage(title("Schematics (" + files.size() + ")"));
		if (files.isEmpty()) {
			p.sendSystemMessage(Component.literal("Nothing yet. Put files in " + schematicsDir() + " or use /bp url <link>").withStyle(ChatFormatting.GRAY));
		}
		for (String f : files.subList(0, Math.min(files.size(), 30))) {
			p.sendSystemMessage(button(" [Load] ", "/bp load " + f, "Load " + f, ChatFormatting.GREEN)
					.append(Component.literal(f).withStyle(ChatFormatting.WHITE)));
		}
		return files.size();
	}

	private int load(CommandContext<CommandSourceStack> ctx, String file) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Path dir = schematicsDir();
		Path f = dir.resolve(file).normalize();
		if (!f.startsWith(dir) || !Files.isRegularFile(f)) {
			p.sendSystemMessage(err("File not found: " + file));
			return 0;
		}
		MinecraftServer server = ctx.getSource().getServer();
		UUID id = p.getUUID();
		CompletableFuture.supplyAsync(() -> {
			try {
				return SchematicReader.read(f.getFileName().toString(), Files.readAllBytes(f));
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}).whenComplete((bp, ex) -> server.execute(() -> finishLoad(server, id, bp, ex, null)));
		p.sendSystemMessage(Component.literal("Loading " + file + "...").withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private int url(CommandContext<CommandSourceStack> ctx, String link) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		MinecraftServer server = ctx.getSource().getServer();
		UUID id = p.getUUID();
		p.sendSystemMessage(Component.literal("Downloading...").withStyle(ChatFormatting.GRAY));
		CompletableFuture.supplyAsync(() -> {
			try {
				Links.Download dl = Links.download(link);
				Blueprint bp = SchematicReader.read(dl.fileName, dl.data);
				String name = dl.fileName;
				if (!isSchematic(name)) name = name + SchematicReader.extensionFor(dl.data);
				Path target = schematicsDir().resolve("downloads").resolve(Links.safeFileName(name));
				Files.createDirectories(target.getParent());
				Files.write(target, dl.data);
				bp.sourceUrl = link;
				return bp;
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}).whenComplete((bp, ex) -> server.execute(() -> finishLoad(server, id, bp, ex, link)));
		return 1;
	}

	private void finishLoad(MinecraftServer server, UUID id, Blueprint bp, Throwable ex, String link) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		if (p == null) return;
		if (ex != null || bp == null) {
			Throwable c = ex;
			while (c != null && c.getCause() != null && c.getMessage() != null && c.getMessage().contains("Exception")) c = c.getCause();
			p.sendSystemMessage(err("Could not load: " + (c == null ? "unknown error" : c.getMessage())));
			return;
		}
		Session s = session(p);
		clearPreview(s, p);
		if (s.job != null) s.job = null;
		s.blueprint = bp;
		if (s.videoUrl != null) bp.videoUrl = s.videoUrl;
		s.states = new BlockState[bp.palette.size()];
		s.unknownBlocks = 0;
		Map<String, Integer> counts = bp.stateCounts();
		for (int i = 0; i < bp.palette.size(); i++) {
			s.states[i] = parseState(bp.palette.get(i));
			if (s.states[i] == null) s.unknownBlocks += counts.getOrDefault(bp.palette.get(i), 0);
		}
		s.layer = -1;
		s.rotation = Rotation.NONE;
		placeInFront(s, p);

		p.sendSystemMessage(title("Loaded: " + bp.name));
		sendInfo(p, s);
		p.sendSystemMessage(button("[Materials]", "/bp materials", "Show what you need", ChatFormatting.GOLD)
				.append(Component.literal(" "))
				.append(button("[Preview]", "/bp preview on", "Show a ghost of the build", ChatFormatting.AQUA))
				.append(Component.literal(" "))
				.append(button("[Layer 1]", "/bp layer 1", "View layer by layer", ChatFormatting.LIGHT_PURPLE))
				.append(Component.literal(" "))
				.append(button("[Rotate]", "/bp rotate", "Rotate 90 degrees", ChatFormatting.YELLOW))
				.append(Component.literal(" "))
				.append(button("[BUILD]", "/bp build", "Start building", ChatFormatting.GREEN)));
	}

	private int video(CommandContext<CommandSourceStack> ctx, String link) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		MinecraftServer server = ctx.getSource().getServer();
		UUID id = p.getUUID();
		Session s = session(p);
		s.videoUrl = link.trim();
		if (s.blueprint != null) s.blueprint.videoUrl = s.videoUrl;
		p.sendSystemMessage(Component.literal("Looking for the schematic in the video description...").withStyle(ChatFormatting.GRAY));
		CompletableFuture.supplyAsync(() -> {
			try {
				return Links.scanVideo(link);
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}).whenComplete((links, ex) -> server.execute(() -> {
			ServerPlayer pl = server.getPlayerList().getPlayer(id);
			if (pl == null) return;
			Session ss = session(pl);
			if (ex != null) {
				pl.sendSystemMessage(err("Could not open the video page: " + ex.getMessage()));
				return;
			}
			ss.videoLinks = links;
			if (links.isEmpty()) {
				pl.sendSystemMessage(err("No download link found in the description."));
				pl.sendSystemMessage(Component.literal("Open the video, copy the .schem/.litematic link and use /bp url <link>").withStyle(ChatFormatting.GRAY));
				pl.sendSystemMessage(openLink("[Open video]", s.videoUrl));
				return;
			}
			pl.sendSystemMessage(title("Links found in the video"));
			for (String l : links) {
				pl.sendSystemMessage(button(" [Use] ", "/bp url " + l, "Download and load this", ChatFormatting.GREEN)
						.append(openLink("[Open] ", l))
						.append(Component.literal(shorten(l, 50)).withStyle(ChatFormatting.WHITE)));
			}
		}));
		return 1;
	}

	private int unload(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Session s = session(p);
		clearPreview(s, p);
		s.blueprint = null;
		s.job = null;
		p.sendSystemMessage(ok("Unloaded."));
		return 1;
	}

	/** Block state from "id[props]"; null if the block does not exist in this version. */
	static BlockState parseState(String str) {
		Identifier id = Identifier.tryParse(Blueprint.blockId(str));
		if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
		Block block = BuiltInRegistries.BLOCK.getValue(id);
		BlockState state = block.defaultBlockState();
		StateDefinition<Block, BlockState> def = block.getStateDefinition();
		for (Map.Entry<String, String> e : Blueprint.properties(str).entrySet()) {
			Property<?> prop = def.getProperty(e.getKey());
			if (prop != null) state = withValue(state, prop, e.getValue());
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> prop, String value) {
		return prop.getValue(value).map(v -> state.setValue(prop, v)).orElse(state);
	}

	// ---------------------------------------------------------------- info

	private int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Session s = session(p);
		if (!requireBlueprint(p, s)) return 0;
		p.sendSystemMessage(title(s.blueprint.name));
		sendInfo(p, s);
		return 1;
	}

	private void sendInfo(ServerPlayer p, Session s) {
		Blueprint bp = s.blueprint;
		p.sendSystemMessage(kv("Size", s.sizeXRot() + " x " + bp.sizeY + " x " + s.sizeZRot() + "  (" + bp.sizeY + " layers)"));
		p.sendSystemMessage(kv("Blocks", String.format(Locale.ROOT, "%,d", bp.blockCount())));
		if (s.placed()) {
			BlockPos o = s.origin;
			p.sendSystemMessage(kv("Position", o.getX() + " " + o.getY() + " " + o.getZ() + "   rotation " + rotName(s.rotation)));
		}
		if (s.unknownBlocks > 0) {
			p.sendSystemMessage(Component.literal(" " + s.unknownBlocks + " blocks don't exist in this Minecraft version and will be skipped.").withStyle(ChatFormatting.RED));
		}
		if (bp.videoUrl != null) p.sendSystemMessage(Component.literal(" Video: ").withStyle(ChatFormatting.GRAY).append(openLink("[Watch]", bp.videoUrl)));
		if (bp.sourceUrl != null) p.sendSystemMessage(Component.literal(" Source: ").withStyle(ChatFormatting.GRAY).append(openLink("[Link]", bp.sourceUrl)));
	}

	// ---------------------------------------------------------------- materials

	private static final class MaterialRow {
		final Item item;
		int need;
		int have;
		MaterialRow(Item item) { this.item = item; }
		int missing() { return Math.max(0, need - have); }
	}

	/** How many items a placed block costs (door tops, bed heads = 0, double slabs = 2). */
	static int itemCost(String paletteState) {
		Map<String, String> pr = Blueprint.properties(paletteState);
		if ("upper".equals(pr.get("half"))) return 0;
		if ("head".equals(pr.get("part"))) return 0;
		if ("double".equals(pr.get("type")) && Blueprint.blockId(paletteState).endsWith("_slab")) return 2;
		return 1;
	}

	private int materials(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Session s = session(p);
		if (!requireBlueprint(p, s)) return 0;
		Blueprint bp = s.blueprint;

		// count what is still needed (cells not already correct in the world, if placed)
		int[] perPalette = new int[bp.palette.size()];
		boolean checkWorld = s.placed();
		for (int y = 0; y < bp.sizeY; y++) {
			for (int z = 0; z < bp.sizeZ; z++) {
				for (int x = 0; x < bp.sizeX; x++) {
					int pi = bp.cells[bp.index(x, y, z)];
					if (pi < 0 || s.states[pi] == null || s.states[pi].isAir()) continue;
					if (checkWorld) {
						BlockState t = s.target(x, y, z);
						if (s.level.getBlockState(s.worldPos(x, y, z)) == t) continue;
					}
					perPalette[pi]++;
				}
			}
		}
		Map<Item, MaterialRow> rows = new LinkedHashMap<>();
		int noItem = 0;
		for (int i = 0; i < perPalette.length; i++) {
			if (perPalette[i] == 0) continue;
			Item item = s.states[i].getBlock().asItem();
			if (item == Items.AIR) {
				noItem += perPalette[i];
				continue;
			}
			int cost = itemCost(bp.palette.get(i));
			if (cost == 0) continue;
			rows.computeIfAbsent(item, MaterialRow::new).need += perPalette[i] * cost;
		}
		Map<Item, Integer> inv = inventoryCounts(p);
		for (MaterialRow r : rows.values()) r.have = inv.getOrDefault(r.item, 0);
		List<MaterialRow> sorted = new ArrayList<>(rows.values());
		sorted.sort(Comparator.comparingInt(MaterialRow::missing).reversed().thenComparing(Comparator.comparingInt((MaterialRow r) -> r.need).reversed()));

		int totalNeed = 0, totalMissing = 0;
		for (MaterialRow r : sorted) { totalNeed += r.need; totalMissing += r.missing(); }
		int pages = Math.max(1, (sorted.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		page = Math.min(page, pages);

		p.sendSystemMessage(title("Materials - " + bp.name + "  (" + page + "/" + pages + ")"));
		if (sorted.isEmpty()) {
			p.sendSystemMessage(ok(checkWorld ? "Nothing left to place - the build is complete!" : "No blocks needed."));
		}
		for (MaterialRow r : sorted.subList((page - 1) * PAGE_SIZE, Math.min(sorted.size(), page * PAGE_SIZE))) {
			boolean enough = r.missing() == 0;
			MutableComponent line = Component.literal(enough ? " ✔ " : " ✖ ").withStyle(enough ? ChatFormatting.GREEN : ChatFormatting.RED)
					.append(new ItemStack(r.item).getHoverName().copy().withStyle(ChatFormatting.WHITE))
					.append(Component.literal("  " + stacks(r.need)).withStyle(ChatFormatting.GRAY))
					.append(Component.literal("  have " + r.have).withStyle(enough ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
			if (!enough) line.append(Component.literal("  missing " + r.missing()).withStyle(ChatFormatting.RED));
			p.sendSystemMessage(line);
		}
		MutableComponent nav = Component.literal(" ");
		if (page > 1) nav.append(button("[< Prev] ", "/bp materials " + (page - 1), "Previous page", ChatFormatting.AQUA));
		if (page < pages) nav.append(button("[Next >] ", "/bp materials " + (page + 1), "Next page", ChatFormatting.AQUA));
		nav.append(Component.literal("Total " + totalNeed + ", missing " + totalMissing).withStyle(totalMissing == 0 ? ChatFormatting.GREEN : ChatFormatting.GOLD));
		p.sendSystemMessage(nav);
		if (noItem > 0) {
			p.sendSystemMessage(Component.literal(" + " + noItem + " blocks with no item (water, fire, ...) - placed only in creative").withStyle(ChatFormatting.DARK_GRAY));
		}
		return 1;
	}

	private static String stacks(int n) {
		if (n < 64) return "x" + n;
		int st = n / 64, rem = n % 64;
		return "x" + n + " (" + st + " stack" + (st > 1 ? "s" : "") + (rem > 0 ? " + " + rem : "") + ")";
	}

	private static Map<Item, Integer> inventoryCounts(ServerPlayer p) {
		Map<Item, Integer> out = new HashMap<>();
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty()) out.merge(st.getItem(), st.getCount(), Integer::sum);
		}
		return out;
	}

	private static boolean takeItem(ServerPlayer p, Item item, int amount) {
		Inventory inv = p.getInventory();
		int have = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty() && st.getItem() == item) have += st.getCount();
		}
		if (have < amount) return false;
		int left = amount;
		for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty() && st.getItem() == item) {
				int t = Math.min(left, st.getCount());
				st.shrink(t);
				left -= t;
			}
		}
		inv.setChanged();
		return true;
	}

	// ---------------------------------------------------------------- placement

	private void placeInFront(Session s, ServerPlayer p) {
		s.level = p.level();
		s.origin = p.blockPosition().relative(p.getDirection(), 2);
	}

	private int here(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Session s = session(p);
		if (!requireBlueprint(p, s) || !requireIdle(p, s)) return 0;
		placeInFront(s, p);
		moved(p, s);
		return 1;
	}

	private int rotate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Session s = session(p);
		if (!requireBlueprint(p, s) || !requireIdle(p, s)) return 0;
		s.rotation = s.rotation.getRotated(Rotation.CLOCKWISE_90);
		moved(p, s);
		return 1;
	}

	private int move(CommandContext<CommandSourceStack> ctx, int dx, int dy, int dz) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Session s = session(p);
		if (!requireBlueprint(p, s) || !requireIdle(p, s)) return 0;
		if (!s.placed()) placeInFront(s, p);
		s.origin = s.origin.offset(dx, dy, dz);
		moved(p, s);
		return 1;
	}

	private void moved(ServerPlayer p, Session s) {
		p.sendSystemMessage(ok("Build at " + s.origin.getX() + " " + s.origin.getY() + " " + s.origin.getZ()
				+ ", rotation " + rotName(s.rotation) + ", size " + s.sizeXRot() + "x" + s.blueprint.sizeY + "x" + s.sizeZRot()));
		if (s.previewOn) refreshPreview(s, p);
	}

	private static String rotName(Rotation r) {
		return switch (r) {
			case CLOCKWISE_90 -> "90";
			case CLOCKWISE_180 -> "180";
			case COUNTERCLOCKWISE_90 -> "270";
			default -> "0";
		};
	}

	// ---------------------------------------------------------------- preview

	private static String previewTag(ServerPlayer p) {
		return "bpv_" + p.getUUID().toString().substring(0, 8);
	}

	private int preview(CommandContext<CommandSourceStack> ctx, Boolean on) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Session s = session(p);
		if (!requireBlueprint(p, s)) return 0;
		boolean target = on == null ? !s.previewOn : on;
		if (!target) {
			clearPreview(s, p);
			p.sendSystemMessage(ok("Preview off."));
			return 1;
		}
		if (!s.placed()) placeInFront(s, p);
		s.previewOn = true;
		refreshPreview(s, p);
		return 1;
	}

	private int layer(CommandContext<CommandSourceStack> ctx, int value, boolean relative) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Session s = session(p);
		if (!requireBlueprint(p, s)) return 0;
		int max = s.blueprint.sizeY - 1;
		if (relative) {
			int cur = s.layer < 0 ? (value > 0 ? -1 : max + 1) : s.layer;
			s.layer = Math.max(0, Math.min(max, cur + value));
		} else {
			s.layer = value < 0 ? -1 : Math.min(max, value);
		}
		if (!s.placed()) placeInFront(s, p);
		s.previewOn = true;
		refreshPreview(s, p);
		if (s.layer >= 0) {
			p.sendSystemMessage(Component.literal(" Layer " + (s.layer + 1) + "/" + (max + 1) + "  ").withStyle(ChatFormatting.LIGHT_PURPLE)
					.append(button("[▼ Down] ", "/bp layer down", "Previous layer", ChatFormatting.AQUA))
					.append(button("[▲ Up] ", "/bp layer up", "Next layer", ChatFormatting.AQUA))
					.append(button("[All]", "/bp layer all", "Show everything", ChatFormatting.GRAY)));
		}
		return 1;
	}

	private void clearPreview(Session s, ServerPlayer p) {
		s.previewQueue.clear();
		s.previewOn = false;
		if (s.level != null) runSilently(s.level, "kill @e[type=minecraft:block_display,tag=" + previewTag(p) + "]");
	}

	private void refreshPreview(Session s, ServerPlayer p) {
		ServerLevel level = s.level;
		runSilently(level, "kill @e[type=minecraft:block_display,tag=" + previewTag(p) + "]");
		s.previewQueue.clear();
		Blueprint bp = s.blueprint;
		boolean layerMode = s.layer >= 0;
		float scale = layerMode ? 0.9f : 0.5f;
		float off = (1f - scale) / 2f;
		String tag = previewTag(p);
		String transform = String.format(Locale.ROOT,
				"transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[%.3ff,%.3ff,%.3ff],scale:[%.3ff,%.3ff,%.3ff]}",
				off, off, off, scale, scale, scale);
		int yFrom = layerMode ? s.layer : 0, yTo = layerMode ? s.layer : bp.sizeY - 1;
		int count = 0, alreadyDone = 0;
		outer:
		for (int y = yFrom; y <= yTo; y++) {
			for (int z = 0; z < bp.sizeZ; z++) {
				for (int x = 0; x < bp.sizeX; x++) {
					BlockState t = s.target(x, y, z);
					if (t == null || t.isAir()) continue;
					BlockPos pos = s.worldPos(x, y, z);
					if (level.getBlockState(pos) == t) {
						alreadyDone++;
						continue;
					}
					if (count >= PREVIEW_LIMIT) break outer;
					String snbt = Blueprint.toSnbt(BlockStateParser.serialize(t));
					s.previewQueue.add(String.format(Locale.ROOT,
							"summon minecraft:block_display %d %d %d {Tags:[\"bp_preview\",\"%s\"],block_state:%s,%s,brightness:{sky:15,block:15}%s}",
							pos.getX(), pos.getY(), pos.getZ(), tag, snbt, transform, layerMode ? ",Glowing:1b" : ""));
					count++;
				}
			}
		}
		if (count >= PREVIEW_LIMIT) {
			p.sendSystemMessage(Component.literal(" Big build: showing the first " + PREVIEW_LIMIT + " blocks. Use ").withStyle(ChatFormatting.YELLOW)
					.append(button("[/bp layer 1]", "/bp layer 1", "Layer view", ChatFormatting.AQUA))
					.append(Component.literal(" to see every layer.").withStyle(ChatFormatting.YELLOW)));
		}
		p.sendSystemMessage(ok("Preview: " + count + " blocks to place" + (alreadyDone > 0 ? ", " + alreadyDone + " already correct" : "")
				+ (layerMode ? " (layer " + (s.layer + 1) + ")" : "")));
	}

	private static void runSilently(ServerLevel level, String command) {
		MinecraftServer server = level.getServer();
		CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput().withLevel(level);
		server.getCommands().performPrefixedCommand(src, command);
	}

	// ---------------------------------------------------------------- building

	private int build(CommandContext<CommandSourceStack> ctx, int speed, boolean clearAir) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Session s = session(p);
		if (!requireBlueprint(p, s)) return 0;
		if (s.job != null && !s.job.done()) {
			s.job.speed = speed;
			s.job.paused = false;
			p.sendSystemMessage(ok("Already building - speed set to " + speed + " blocks/tick."));
			return 1;
		}
		if (!s.placed()) placeInFront(s, p);
		Blueprint bp = s.blueprint;
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < bp.cells.length; i++) {
			int pi = bp.cells[i];
			if (pi < 0 || s.states[pi] == null) continue;
			if (s.states[pi].isAir() && !clearAir) continue;
			order.add(i);
		}
		int[] arr = new int[order.size()];
		for (int i = 0; i < arr.length; i++) arr[i] = order.get(i);
		Session.BuildJob job = new Session.BuildJob(arr, speed);
		job.clearAir = clearAir;
		job.survival = !p.getAbilities().instabuild;
		s.job = job;
		clearPreview(s, p);
		p.sendSystemMessage(ok("Building " + bp.name + " (" + arr.length + " blocks, " + speed + " per tick)"
				+ (job.survival ? " - survival: uses blocks from your inventory" : "")));
		p.sendSystemMessage(button("[Pause]", "/bp pause", "Pause", ChatFormatting.YELLOW).append(Component.literal(" "))
				.append(button("[Cancel]", "/bp cancel", "Stop", ChatFormatting.RED)).append(Component.literal(" "))
				.append(button("[Faster]", "/bp build " + Math.min(2000, speed * 4), "Build faster", ChatFormatting.AQUA)));
		return 1;
	}

	private int pause(CommandContext<CommandSourceStack> ctx, boolean pause) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Session s = session(p);
		if (s.job == null || s.job.done()) {
			p.sendSystemMessage(err("Nothing is being built."));
			return 0;
		}
		s.job.paused = pause;
		p.sendSystemMessage(ok(pause ? "Paused." : "Resumed."));
		return 1;
	}

	private int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Session s = session(p);
		if (s.job == null) {
			p.sendSystemMessage(err("Nothing is being built."));
			return 0;
		}
		s.lastFinished = s.job;
		s.job = null;
		p.sendSystemMessage(ok("Cancelled. Use /bp undo to remove what was placed."));
		return 1;
	}

	private int undo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Session s = session(p);
		Session.BuildJob j = s.job != null ? s.job : s.lastFinished;
		if (j == null || j.undoPos.isEmpty() || s.level == null) {
			p.sendSystemMessage(err("Nothing to undo."));
			return 0;
		}
		s.job = null;
		for (int i = j.undoPos.size() - 1; i >= 0; i--) {
			s.level.setBlock(j.undoPos.get(i), j.undoState.get(i), 0x12);
		}
		int n = j.undoPos.size();
		j.undoPos.clear();
		j.undoState.clear();
		s.lastFinished = null;
		p.sendSystemMessage(ok("Undid " + n + " blocks."));
		return 1;
	}

	private void tick(MinecraftServer server) {
		for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
			Session s = e.getValue();
			if (!s.previewQueue.isEmpty() && s.level != null) {
				for (int i = 0; i < PREVIEW_PER_TICK && !s.previewQueue.isEmpty(); i++) {
					runSilently(s.level, s.previewQueue.poll());
				}
			}
			Session.BuildJob job = s.job;
			if (job == null || job.paused || s.blueprint == null || s.level == null) continue;
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null) continue;
			try {
				stepBuild(s, job, p);
			} catch (Exception ex) {
				LOGGER.error("Build failed", ex);
				p.sendSystemMessage(err("Build stopped: " + ex.getMessage()));
				s.lastFinished = job;
				s.job = null;
			}
		}
	}

	private void stepBuild(Session s, Session.BuildJob job, ServerPlayer p) {
		Blueprint bp = s.blueprint;
		ServerLevel level = s.level;
		int budget = job.speed;
		int work = 0;
		while (budget > 0 && !job.done() && work < job.speed * 20) {
			int idx = job.order[job.cursor++];
			work++;
			int x = idx % bp.sizeX;
			int z = (idx / bp.sizeX) % bp.sizeZ;
			int y = idx / (bp.sizeX * bp.sizeZ);
			BlockState t = s.target(x, y, z);
			if (t == null) continue;
			BlockPos pos = s.worldPos(x, y, z);
			BlockState cur = level.getBlockState(pos);
			if (cur == t) continue;
			if (t.isAir() && cur.isAir()) continue;

			if (job.survival) {
				if (!cur.isAir() && !cur.is(BlockTags.REPLACEABLE) && cur.getFluidState().isEmpty()) {
					job.skippedBlocked++;
					continue;
				}
				if (!t.isAir()) {
					String pal = bp.palette.get(bp.cells[idx]);
					Item item = t.getBlock().asItem();
					if (item == Items.AIR) {
						job.skippedNoItem++;
						continue;
					}
					int cost = itemCost(pal);
					if (cost > 0 && !takeItem(p, item, cost)) {
						job.skippedMissing++;
						continue;
					}
				}
			}
			job.undoPos.add(pos.immutable());
			job.undoState.add(cur);
			level.setBlock(pos, t, 0x12);
			if (!t.isAir() && job.placed % 3 == 0) level.levelEvent(2001, pos, Block.getId(t));
			job.placed++;
			budget--;
		}
		if (job.done()) {
			s.job = null;
			s.lastFinished = job;
			p.sendSystemMessage(title("Build finished!"));
			p.sendSystemMessage(ok("Placed " + job.placed + " blocks."));
			if (job.skippedMissing > 0) {
				p.sendSystemMessage(Component.literal(" " + job.skippedMissing + " blocks skipped - not enough items. ").withStyle(ChatFormatting.YELLOW)
						.append(button("[Materials]", "/bp materials", "See what's missing", ChatFormatting.GOLD))
						.append(Component.literal(" then ").withStyle(ChatFormatting.YELLOW))
						.append(button("[Build again]", "/bp build", "Places only what's left", ChatFormatting.GREEN)));
			}
			if (job.skippedBlocked > 0) p.sendSystemMessage(Component.literal(" " + job.skippedBlocked + " blocks skipped - something was in the way.").withStyle(ChatFormatting.YELLOW));
			if (job.skippedNoItem > 0) p.sendSystemMessage(Component.literal(" " + job.skippedNoItem + " blocks (water, fire...) need creative mode.").withStyle(ChatFormatting.GRAY));
			p.sendSystemMessage(button("[Undo]", "/bp undo", "Remove the build", ChatFormatting.RED));
		} else if (job.cursor % 2000 < job.speed && job.order.length > 4000) {
			p.sendSystemMessage(Component.literal(" " + (job.cursor * 100 / job.order.length) + "%").withStyle(ChatFormatting.DARK_GREEN));
		}
	}

	// ---------------------------------------------------------------- guards & text helpers

	private boolean requireBlueprint(ServerPlayer p, Session s) {
		if (s.hasBlueprint()) return true;
		p.sendSystemMessage(err("No build loaded. ").append(button("[/bp list]", "/bp list", "Show files", ChatFormatting.AQUA))
				.append(Component.literal(" or /bp url <link>").withStyle(ChatFormatting.GRAY)));
		return false;
	}

	private boolean requireIdle(ServerPlayer p, Session s) {
		if (s.job == null || s.job.done()) return true;
		p.sendSystemMessage(err("Can't move it while building. /bp cancel first."));
		return false;
	}

	private static MutableComponent title(String t) {
		return Component.literal("■ " + t).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
	}

	private static MutableComponent kv(String k, String v) {
		return Component.literal(" " + k + ": ").withStyle(ChatFormatting.GRAY).append(Component.literal(v).withStyle(ChatFormatting.WHITE));
	}

	private static MutableComponent ok(String t) {
		return Component.literal(" " + t).withStyle(ChatFormatting.GREEN);
	}

	private static MutableComponent err(String t) {
		return Component.literal(" " + t).withStyle(ChatFormatting.RED);
	}

	private static MutableComponent button(String label, String command, String hover, ChatFormatting color) {
		return Component.literal(label).withStyle(st -> st.withColor(color)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	private static MutableComponent openLink(String label, String url) {
		try {
			URI uri = URI.create(url.startsWith("http") ? url : "https://" + url);
			return Component.literal(label).withStyle(ChatFormatting.BLUE, ChatFormatting.UNDERLINE).withStyle(st -> st
					.withClickEvent(new ClickEvent.OpenUrl(uri))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(url))));
		} catch (IllegalArgumentException e) {
			return Component.literal(label + " " + url).withStyle(ChatFormatting.BLUE);
		}
	}

	private static String shorten(String s, int n) {
		return s.length() <= n ? s : s.substring(0, n - 3) + "...";
	}
}
