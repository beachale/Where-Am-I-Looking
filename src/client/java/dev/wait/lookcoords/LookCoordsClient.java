package dev.wait.lookcoords;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public final class LookCoordsClient implements ClientModInitializer {
    private static final double MAX_DISTANCE = 256.0;
    private static final double EPSILON = 1.0e-7;
    private static final int DEFAULT_DECIMAL_AMOUNT = 4;
    private static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final String[] COLOR_NAMES = {
            "white", "black", "red", "green", "blue", "yellow", "cyan", "magenta", "gray", "orange"
    };

    private boolean enabled = true;
    private int decimalAmount = DEFAULT_DECIMAL_AMOUNT;
    private String coordinateFormat = coordinateFormat(DEFAULT_DECIMAL_AMOUNT);
    private int textColor = DEFAULT_TEXT_COLOR;
    private Path configPath;

    @Override
    public void onInitializeClient() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve("wail.properties");
        loadConfig();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("wail")
                        .executes(this::showStatus)
                        .then(ClientCommandManager.literal("on")
                                .executes(context -> setEnabled(context, true)))
                        .then(ClientCommandManager.literal("off")
                                .executes(context -> setEnabled(context, false)))
                        .then(ClientCommandManager.literal("decimalamount")
                                .executes(this::showStatus)
                                .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(0, 10))
                                        .executes(this::setDecimalAmount)))
                        .then(ClientCommandManager.literal("color")
                                .executes(this::showStatus)
                                .then(ClientCommandManager.argument("value", StringArgumentType.greedyString())
                                        .suggests((context, builder) -> CommandSource.suggestMatching(COLOR_NAMES, builder))
                                        .executes(this::setColor)))
        ));
        HudRenderCallback.EVENT.register(this::renderHud);
    }

    private void renderHud(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || client.options.hudHidden || client.world == null || client.player == null || client.gameRenderer == null) {
            return;
        }

        Vec3d hit = traceVisibleBlockModel(client);
        if (hit == null) {
            return;
        }

        String text = String.format(Locale.ROOT, coordinateFormat, hit.x, hit.y, hit.z);
        TextRenderer textRenderer = client.textRenderer;
        int width = textRenderer.getWidth(text);
        int x = context.getScaledWindowWidth() - width - 6;
        int y = 6;

        context.fill(x - 3, y - 3, x + width + 3, y + textRenderer.fontHeight + 3, 0x80000000);
        context.drawTextWithShadow(textRenderer, Text.literal(text), x, y, textColor);
    }

    private int showStatus(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Text.literal("WAIL is " + (enabled ? "on" : "off")
                + ", decimalamount is " + decimalAmount
                + ", color is #" + String.format(Locale.ROOT, "%06X", textColor & 0xFFFFFF) + "."));
        return 1;
    }

    private int setEnabled(CommandContext<FabricClientCommandSource> context, boolean value) {
        enabled = value;
        saveConfig();
        context.getSource().sendFeedback(Text.literal("WAIL is now " + (enabled ? "on." : "off.")));
        return 1;
    }

    private int setDecimalAmount(CommandContext<FabricClientCommandSource> context) {
        decimalAmount = IntegerArgumentType.getInteger(context, "amount");
        coordinateFormat = coordinateFormat(decimalAmount);
        saveConfig();
        context.getSource().sendFeedback(Text.literal("WAIL decimalamount set to " + decimalAmount + "."));
        return 1;
    }

    private int setColor(CommandContext<FabricClientCommandSource> context) {
        String value = StringArgumentType.getString(context, "value");
        Integer color = parseColor(value);
        if (color == null) {
            context.getSource().sendError(Text.literal("Invalid WAIL color. Use a name or hex like #55FFFF."));
            return 0;
        }

        textColor = 0xFF000000 | color;
        saveConfig();
        context.getSource().sendFeedback(Text.literal("WAIL color set to #" + String.format(Locale.ROOT, "%06X", textColor & 0xFFFFFF) + "."));
        return 1;
    }

    private Integer parseColor(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "white" -> 0xFFFFFF;
            case "black" -> 0x000000;
            case "red" -> 0xFF5555;
            case "green" -> 0x55FF55;
            case "blue" -> 0x5555FF;
            case "yellow" -> 0xFFFF55;
            case "cyan" -> 0x55FFFF;
            case "magenta" -> 0xFF55FF;
            case "gray", "grey" -> 0xAAAAAA;
            case "orange" -> 0xFFAA00;
            default -> parseHexColor(normalized);
        };
    }

    private Integer parseHexColor(String value) {
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() != 6) {
            return null;
        }

        try {
            return Integer.parseUnsignedInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void loadConfig() {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(configPath)) {
            properties.load(reader);
        } catch (IOException ignored) {
            return;
        }

        enabled = Boolean.parseBoolean(properties.getProperty("enabled", Boolean.toString(enabled)));
        decimalAmount = clamp(parseInt(properties.getProperty("decimalAmount"), decimalAmount), 0, 10);
        coordinateFormat = coordinateFormat(decimalAmount);
        textColor = 0xFF000000 | (parseInt(properties.getProperty("textColor"), textColor) & 0xFFFFFF);
    }

    private void saveConfig() {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("decimalAmount", Integer.toString(decimalAmount));
        properties.setProperty("textColor", String.format(Locale.ROOT, "%06X", textColor & 0xFFFFFF));

        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                properties.store(writer, "Where Am I Looking (WAIL) client settings");
            }
        } catch (IOException ignored) {
        }
    }

    private int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseUnsignedInt(value, value.matches("[0-9A-Fa-f]{6}") ? 16 : 10);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String coordinateFormat(int decimals) {
        return "x %1$." + decimals + "f  y %2$." + decimals + "f  z %3$." + decimals + "f";
    }

    private Vec3d traceVisibleBlockModel(MinecraftClient client) {
        ClientWorld world = client.world;
        Camera camera = client.gameRenderer.getCamera();
        Vec3d start = camera.getPos();
        Vec3d direction = Vec3d.fromPolar(camera.getPitch(), camera.getYaw()).normalize();

        ModelHit modelHit = traceModels(client, world, start, direction);
        return modelHit == null ? null : modelHit.pos();
    }

    private ModelHit traceModels(MinecraftClient client, ClientWorld world, Vec3d start, Vec3d direction) {
        ModelHit best = null;
        int x = MathHelper.floor(start.x);
        int y = MathHelper.floor(start.y);
        int z = MathHelper.floor(start.z);

        int stepX = direction.x > 0.0 ? 1 : -1;
        int stepY = direction.y > 0.0 ? 1 : -1;
        int stepZ = direction.z > 0.0 ? 1 : -1;

        double tMaxX = firstBoundaryDistance(start.x, direction.x, stepX);
        double tMaxY = firstBoundaryDistance(start.y, direction.y, stepY);
        double tMaxZ = firstBoundaryDistance(start.z, direction.z, stepZ);
        double tDeltaX = direction.x == 0.0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.x);
        double tDeltaY = direction.y == 0.0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.y);
        double tDeltaZ = direction.z == 0.0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.z);
        double t = 0.0;
        BlockPos.Mutable pos = new BlockPos.Mutable();

        while (t <= MAX_DISTANCE) {
            pos.set(x, y, z);
            BlockState state = world.getBlockState(pos);
            if (!state.isAir()) {
                ModelHit hit = traceBlockModel(client, state, pos, start, direction);
                if (hit != null && (best == null || hit.distance() < best.distance())) {
                    best = hit;
                }
            }

            double bestDistance = best == null ? Double.POSITIVE_INFINITY : best.distance();
            double nextT = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (bestDistance <= nextT + EPSILON) {
                return best;
            }

            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }
        return best;
    }

    private ModelHit traceBlockModel(MinecraftClient client, BlockState state, BlockPos pos, Vec3d start, Vec3d direction) {
        BlockStateModel model = client.getBlockRenderManager().getModel(state);
        ModelHit best = null;

        for (BlockModelPart part : model.getParts(Random.create(state.getRenderingSeed(pos)))) {
            for (Direction side : DIRECTIONS) {
                best = traceQuads(part.getQuads(side), pos, start, direction, best);
            }
            best = traceQuads(part.getQuads(null), pos, start, direction, best);
        }

        return best;
    }

    private ModelHit traceQuads(List<BakedQuad> quads, BlockPos pos, Vec3d start, Vec3d direction, ModelHit best) {
        for (BakedQuad quad : quads) {
            int[] data = quad.vertexData();
            Vec3d v0 = vertex(data, 0).add(Vec3d.of(pos));
            Vec3d v1 = vertex(data, 1).add(Vec3d.of(pos));
            Vec3d v2 = vertex(data, 2).add(Vec3d.of(pos));
            Vec3d v3 = vertex(data, 3).add(Vec3d.of(pos));

            best = closer(intersectTriangle(start, direction, v0, v1, v2), best);
            best = closer(intersectTriangle(start, direction, v0, v2, v3), best);
        }

        return best;
    }

    private Vec3d vertex(int[] data, int vertex) {
        int base = vertex * 8;
        return new Vec3d(
                Float.intBitsToFloat(data[base]),
                Float.intBitsToFloat(data[base + 1]),
                Float.intBitsToFloat(data[base + 2])
        );
    }

    private ModelHit intersectTriangle(Vec3d origin, Vec3d direction, Vec3d v0, Vec3d v1, Vec3d v2) {
        Vec3d edge1 = v1.subtract(v0);
        Vec3d edge2 = v2.subtract(v0);
        Vec3d h = direction.crossProduct(edge2);
        double a = edge1.dotProduct(h);
        if (Math.abs(a) < EPSILON) {
            return null;
        }

        double f = 1.0 / a;
        Vec3d s = origin.subtract(v0);
        double u = f * s.dotProduct(h);
        if (u < -EPSILON || u > 1.0 + EPSILON) {
            return null;
        }

        Vec3d q = s.crossProduct(edge1);
        double v = f * direction.dotProduct(q);
        if (v < -EPSILON || u + v > 1.0 + EPSILON) {
            return null;
        }

        double distance = f * edge2.dotProduct(q);
        if (distance < EPSILON || distance > MAX_DISTANCE) {
            return null;
        }

        Vec3d hit = origin.add(direction.multiply(distance));
        return new ModelHit(hit, distance);
    }

    private ModelHit closer(ModelHit candidate, ModelHit best) {
        if (candidate == null) {
            return best;
        }
        if (best == null || candidate.distance() < best.distance()) {
            return candidate;
        }
        return best;
    }

    private double firstBoundaryDistance(double start, double direction, int step) {
        if (direction == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double boundary = step > 0 ? Math.floor(start) + 1.0 : Math.floor(start);
        return (boundary - start) / direction;
    }

    private record ModelHit(Vec3d pos, double distance) {
    }
}
