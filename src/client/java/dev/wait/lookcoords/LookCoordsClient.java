package dev.wait.lookcoords;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.wait.lookcoords.mixin.GameRendererAccessor;
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
    private static final double SIXTEENTH_STEP = 1.0 / 16.0;
    private static final double MAX_CURSOR_SNAPPING_SENSITIVITY = SIXTEENTH_STEP / 2.0;
    private static final double DEFAULT_CURSOR_SNAPPING_SENSITIVITY = MAX_CURSOR_SNAPPING_SENSITIVITY;
    private static final double SNAP_RETICLE_MARGIN = 16.0;
    private static final double SNAP_ANIMATION_MAX_DELTA_SECONDS = 0.1;
    private static final double SNAP_POSITION_RESPONSE_SECONDS = 0.16;
    private static final double SNAP_ALPHA_RESPONSE_SECONDS = 0.10;
    private static final int DEFAULT_DECIMAL_AMOUNT = 4;
    private static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF;
    private static final int SNAP_RETICLE_MAX_ALPHA = 220;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final String[] COLOR_NAMES = {
            "white", "black", "red", "green", "blue", "yellow", "cyan", "magenta", "gray", "orange"
    };
    private static final String[] DECIMAL_TYPE_NAMES = {
            "0.1", "0.0625"
    };

    private boolean enabled = true;
    private int decimalAmount = DEFAULT_DECIMAL_AMOUNT;
    private String coordinateFormat = coordinateFormat(DEFAULT_DECIMAL_AMOUNT);
    private DecimalType decimalType = DecimalType.DECIMAL;
    private boolean cursorSnapping = false;
    private double cursorSnappingSensitivity = DEFAULT_CURSOR_SNAPPING_SENSITIVITY;
    private int textColor = DEFAULT_TEXT_COLOR;
    private Path configPath;
    private long lastSnapAnimationNanos;
    private double snapReticleX = Double.NaN;
    private double snapReticleY = Double.NaN;
    private double snapReticleAlpha;

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
                        .then(ClientCommandManager.literal("decimaltype")
                                .executes(this::showStatus)
                                .then(ClientCommandManager.argument("type", StringArgumentType.word())
                                        .suggests((context, builder) -> CommandSource.suggestMatching(DECIMAL_TYPE_NAMES, builder))
                                        .executes(this::setDecimalType)))
                        .then(ClientCommandManager.literal("cursorsnapping")
                                .executes(this::toggleCursorSnapping)
                                .then(ClientCommandManager.literal("on")
                                        .executes(context -> setCursorSnapping(context, true)))
                                .then(ClientCommandManager.literal("off")
                                        .executes(context -> setCursorSnapping(context, false)))
                                .then(ClientCommandManager.literal("sensitivity")
                                        .executes(this::showCursorSnappingSensitivity)
                                        .then(ClientCommandManager.argument("value", DoubleArgumentType.doubleArg(0.0, MAX_CURSOR_SNAPPING_SENSITIVITY))
                                                .executes(this::setCursorSnappingSensitivity))))
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

        DisplayPoint displayPoint = displayPoint(hit);
        renderSnapReticle(context, client, tickCounter.getTickProgress(false), displayPoint);
        String text = formatCoordinates(displayPoint);
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
                + ", decimaltype is " + decimalType.commandValue()
                + ", cursorsnapping is " + (cursorSnapping ? "on" : "off")
                + ", cursorsnapping sensitivity is " + formatSetting(cursorSnappingSensitivity)
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

    private int setDecimalType(CommandContext<FabricClientCommandSource> context) {
        String value = StringArgumentType.getString(context, "type");
        DecimalType parsed = DecimalType.parse(value);
        if (parsed == null) {
            context.getSource().sendError(Text.literal("Invalid WAIL decimaltype. Use 0.1 or 0.0625."));
            return 0;
        }

        decimalType = parsed;
        saveConfig();
        context.getSource().sendFeedback(Text.literal("WAIL decimaltype set to " + decimalType.commandValue() + "."));
        return 1;
    }

    private int toggleCursorSnapping(CommandContext<FabricClientCommandSource> context) {
        return setCursorSnapping(context, !cursorSnapping);
    }

    private int setCursorSnapping(CommandContext<FabricClientCommandSource> context, boolean value) {
        cursorSnapping = value;
        saveConfig();
        context.getSource().sendFeedback(Text.literal("WAIL cursorsnapping is now " + (cursorSnapping ? "on." : "off.")));
        return 1;
    }

    private int showCursorSnappingSensitivity(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Text.literal("WAIL cursorsnapping sensitivity is "
                + formatSetting(cursorSnappingSensitivity) + "."));
        return 1;
    }

    private int setCursorSnappingSensitivity(CommandContext<FabricClientCommandSource> context) {
        cursorSnappingSensitivity = DoubleArgumentType.getDouble(context, "value");
        saveConfig();
        context.getSource().sendFeedback(Text.literal("WAIL cursorsnapping sensitivity set to "
                + formatSetting(cursorSnappingSensitivity) + "."));
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
        decimalType = DecimalType.parse(properties.getProperty("decimalType"), decimalType);
        cursorSnapping = Boolean.parseBoolean(properties.getProperty("cursorSnapping", Boolean.toString(cursorSnapping)));
        cursorSnappingSensitivity = clamp(parseDouble(properties.getProperty("cursorSnappingSensitivity"), cursorSnappingSensitivity), 0.0, MAX_CURSOR_SNAPPING_SENSITIVITY);
        textColor = 0xFF000000 | (parseInt(properties.getProperty("textColor"), textColor) & 0xFFFFFF);
    }

    private void saveConfig() {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("decimalAmount", Integer.toString(decimalAmount));
        properties.setProperty("decimalType", decimalType.commandValue());
        properties.setProperty("cursorSnapping", Boolean.toString(cursorSnapping));
        properties.setProperty("cursorSnappingSensitivity", formatSetting(cursorSnappingSensitivity));
        properties.setProperty("textColor", String.format(Locale.ROOT, "%06X", textColor & 0xFFFFFF));

        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                properties.store(writer, "Where Am I Looking (WAIL) client settings");
            }
        } catch (IOException ignored) {
        }
    }

    private double parseDouble(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return fallback;
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

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String coordinateFormat(int decimals) {
        return "x %1$." + decimals + "f  y %2$." + decimals + "f  z %3$." + decimals + "f";
    }

    private DisplayPoint displayPoint(Vec3d hit) {
        Vec3d snapped = snapToSixteenth(hit);
        if (cursorSnapping && isWithinSnappingSensitivity(hit, snapped)) {
            return new DisplayPoint(snapped, true, true);
        }
        if (decimalType == DecimalType.SIXTEENTH) {
            return new DisplayPoint(snapped, true, false);
        }
        return new DisplayPoint(hit, false, false);
    }

    private Vec3d snapToSixteenth(Vec3d hit) {
        return new Vec3d(
                snapToSixteenth(hit.x),
                snapToSixteenth(hit.y),
                snapToSixteenth(hit.z)
        );
    }

    private double snapToSixteenth(double value) {
        double snapped = Math.round(value / SIXTEENTH_STEP) * SIXTEENTH_STEP;
        return Math.abs(snapped) < EPSILON ? 0.0 : snapped;
    }

    private boolean isWithinSnappingSensitivity(Vec3d hit, Vec3d snapped) {
        return Math.abs(hit.x - snapped.x) <= cursorSnappingSensitivity
                && Math.abs(hit.y - snapped.y) <= cursorSnappingSensitivity
                && Math.abs(hit.z - snapped.z) <= cursorSnappingSensitivity;
    }

    private String formatCoordinates(DisplayPoint displayPoint) {
        Vec3d pos = displayPoint.pos();
        if (displayPoint.sixteenthGrid()) {
            return "x " + formatSixteenthCoordinate(pos.x)
                    + "  y " + formatSixteenthCoordinate(pos.y)
                    + "  z " + formatSixteenthCoordinate(pos.z);
        }
        return String.format(Locale.ROOT, coordinateFormat, pos.x, pos.y, pos.z);
    }

    private void renderSnapReticle(DrawContext context, MinecraftClient client, float tickProgress, DisplayPoint displayPoint) {
        int width = context.getScaledWindowWidth();
        int height = context.getScaledWindowHeight();
        double centerX = width / 2.0;
        double centerY = height / 2.0;
        ScreenPoint target = displayPoint.cursorSnapped()
                ? projectToScreen(client, tickProgress, displayPoint.pos(), width, height)
                : null;

        boolean active = target != null;
        double targetX = active ? target.x() : centerX;
        double targetY = active ? target.y() : centerY;
        updateSnapReticleAnimation(targetX, targetY, active);
        if (snapReticleAlpha <= 0.01) {
            return;
        }

        int alpha = clamp((int) Math.round(snapReticleAlpha * SNAP_RETICLE_MAX_ALPHA), 0, SNAP_RETICLE_MAX_ALPHA);
        int strongColor = (alpha << 24) | 0x55FFFF;
        context.getMatrices().pushMatrix();
        context.getMatrices().translate((float) snapReticleX, (float) snapReticleY);
        drawSnapMarker(context, strongColor);
        context.getMatrices().popMatrix();
    }

    private ScreenPoint projectToScreen(MinecraftClient client, float tickProgress, Vec3d point, int width, int height) {
        Camera camera = client.gameRenderer.getCamera();
        Vec3d relative = point.subtract(camera.getPos());
        float yaw = camera.getYaw();
        float pitch = camera.getPitch();
        Vec3d forward = Vec3d.fromPolar(pitch, yaw).normalize();
        Vec3d right = Vec3d.fromPolar(0.0F, yaw + 90.0F).normalize();
        Vec3d up = right.crossProduct(forward).normalize();

        double depth = relative.dotProduct(forward);
        if (depth <= EPSILON) {
            return null;
        }

        double cameraX = relative.dotProduct(right);
        double cameraY = relative.dotProduct(up);
        double fov = ((GameRendererAccessor) client.gameRenderer).wail$getFov(camera, tickProgress, true);
        double tanHalfFov = Math.tan(Math.toRadians(fov) / 2.0);
        double aspect = (double) width / (double) height;
        double screenX = width / 2.0 + cameraX / (depth * tanHalfFov * aspect) * width / 2.0;
        double screenY = height / 2.0 - cameraY / (depth * tanHalfFov) * height / 2.0;

        if (screenX < -SNAP_RETICLE_MARGIN
                || screenX > width + SNAP_RETICLE_MARGIN
                || screenY < -SNAP_RETICLE_MARGIN
                || screenY > height + SNAP_RETICLE_MARGIN) {
            return null;
        }
        return new ScreenPoint(screenX, screenY);
    }

    private void updateSnapReticleAnimation(double targetX, double targetY, boolean active) {
        long now = System.nanoTime();
        double deltaSeconds = lastSnapAnimationNanos == 0L ? 1.0 / 60.0 : (now - lastSnapAnimationNanos) / 1_000_000_000.0;
        lastSnapAnimationNanos = now;
        double clampedDelta = clamp(deltaSeconds, 0.0, SNAP_ANIMATION_MAX_DELTA_SECONDS);
        double positionEase = animationEase(clampedDelta, SNAP_POSITION_RESPONSE_SECONDS);
        double alphaEase = animationEase(clampedDelta, SNAP_ALPHA_RESPONSE_SECONDS);

        if (Double.isNaN(snapReticleX) || Double.isNaN(snapReticleY)) {
            snapReticleX = targetX;
            snapReticleY = targetY;
        }

        snapReticleX += (targetX - snapReticleX) * positionEase;
        snapReticleY += (targetY - snapReticleY) * positionEase;
        snapReticleAlpha += ((active ? 1.0 : 0.0) - snapReticleAlpha) * alphaEase;
    }

    private double animationEase(double deltaSeconds, double responseSeconds) {
        return 1.0 - Math.pow(0.001, deltaSeconds / responseSeconds);
    }

    private void drawSnapMarker(DrawContext context, int color) {
        context.fill(-1, -1, 1, 1, color);
        context.fill(-4, -1, -2, 1, color);
        context.fill(2, -1, 4, 1, color);
        context.fill(-1, -4, 1, -2, color);
        context.fill(-1, 2, 1, 4, color);
    }

    private String formatSixteenthCoordinate(double value) {
        String formatted = String.format(Locale.ROOT, "%.4f", value);
        while (formatted.contains(".") && formatted.endsWith("0")) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }
        if (formatted.endsWith(".")) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }
        return formatted.equals("-0") ? "0" : formatted;
    }

    private String formatSetting(double value) {
        return formatSixteenthCoordinate(value);
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
        Vec3d blockOffset = Vec3d.of(pos);
        for (BakedQuad quad : quads) {
            int[] data = quad.vertexData();
            Vec3d v0 = vertex(data, 0).add(blockOffset);
            Vec3d v1 = vertex(data, 1).add(blockOffset);
            Vec3d v2 = vertex(data, 2).add(blockOffset);
            Vec3d v3 = vertex(data, 3).add(blockOffset);

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

    private record DisplayPoint(Vec3d pos, boolean sixteenthGrid, boolean cursorSnapped) {
    }

    private record ScreenPoint(double x, double y) {
    }

    private enum DecimalType {
        DECIMAL("0.1"),
        SIXTEENTH("0.0625");

        private final String commandValue;

        DecimalType(String commandValue) {
            this.commandValue = commandValue;
        }

        private String commandValue() {
            return commandValue;
        }

        private static DecimalType parse(String value) {
            return parse(value, null);
        }

        private static DecimalType parse(String value, DecimalType fallback) {
            if (value == null) {
                return fallback;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "0.1", "decimal", "decimals" -> DECIMAL;
                case "0.0625", "1/16", "sixteenth", "sixteenths" -> SIXTEENTH;
                default -> fallback;
            };
        }
    }
}
