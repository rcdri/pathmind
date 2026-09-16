package com.pathmind.schematic;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Reads Sponge, Litematica, and vanilla structure schematic formats into build plans. */
public final class SchematicLoader {
    private static final long MAX_SCHEMATIC_VOLUME = 4_000_000L;

    private SchematicLoader() {
    }

    public static SchematicBuildPlan load(Path source) throws SchematicLoadException {
        if (source == null) {
            throw new SchematicLoadException("No schematic was selected.");
        }
        String filename = source.getFileName() == null ? "" : source.getFileName().toString();
        String extension = filename.toLowerCase(Locale.ROOT);
        if (!extension.endsWith(".schem") && !extension.endsWith(".schematic") && !extension.endsWith(".litematic") && !extension.endsWith(".nbt")) {
            throw new SchematicLoadException("Unsupported schematic format: " + filename
                + ". Supported formats are .schem, .schematic, .litematic, and .nbt.");
        }

        CompoundTag root;
        try {
            root = NbtIo.readCompressed(source, NbtAccounter.create(64L * 1024L * 1024L));
        } catch (IOException exception) {
            throw new SchematicLoadException("Could not read " + filename + " as a compressed NBT schematic.", exception);
        }

        if (extension.endsWith(".litematic")) {
            return loadLitematic(source, root, filename);
        }
        if (extension.endsWith(".nbt")) {
            return loadStructure(source, root, filename);
        }

        // Sponge v2 stores the payload at the root. Newer exporters (including
        // Litematica's Sponge export) wrap it in a `Schematic` compound and put
        // palette/data in a nested `Blocks` compound. Normalize both layouts
        // before parsing so a valid .schem never looks dimensionless.
        CompoundTag schematicPayload = readCompound(root, "Schematic");
        if (schematicPayload == null) {
            schematicPayload = root;
        }

        int width = readPositiveDimension(schematicPayload, "Width", filename);
        int height = readPositiveDimension(schematicPayload, "Height", filename);
        int length = readPositiveDimension(schematicPayload, "Length", filename);
        long volume = (long) width * height * length;
        if (volume > MAX_SCHEMATIC_VOLUME) {
            throw new SchematicLoadException(filename + " contains " + volume + " blocks; the current planning limit is "
                + MAX_SCHEMATIC_VOLUME + ".");
        }

        CompoundTag blocksPayload = readCompound(schematicPayload, "Blocks");
        if (blocksPayload == null) {
            blocksPayload = schematicPayload;
        }
        Map<Integer, PaletteEntry> palette = readPalette(blocksPayload, filename);
        byte[] blockData = readByteArray(blocksPayload, "BlockData");
        if (blockData.length == 0) {
            blockData = readByteArray(blocksPayload, "Data");
        }
        if (blockData.length == 0 && volume != 0) {
            throw new SchematicLoadException(filename + " has no BlockData payload.");
        }
        List<Integer> paletteIndices = decodeVarInts(blockData, volume, filename);
        BlockPos offset = readOffset(schematicPayload);

        List<SchematicBuildPlan.Placement> placements = new ArrayList<>();
        Map<String, Integer> materials = new HashMap<>();
        int ignoredAirBlocks = 0;
        for (int linearIndex = 0; linearIndex < paletteIndices.size(); linearIndex++) {
            PaletteEntry paletteEntry = palette.get(paletteIndices.get(linearIndex));
            if (paletteEntry == null) {
                throw new SchematicLoadException(filename + " references missing palette entry " + paletteIndices.get(linearIndex)
                    + " at block index " + linearIndex + ".");
            }
            if (paletteEntry.state().isAir()) {
                ignoredAirBlocks++;
                continue;
            }
            int x = linearIndex % width;
            int z = (linearIndex / width) % length;
            int y = linearIndex / (width * length);
            placements.add(new SchematicBuildPlan.Placement(new BlockPos(x, y, z), paletteEntry.state(), paletteEntry.stateId()));
            String materialId = BuiltInRegistries.ITEM.getKey(paletteEntry.state().getBlock().asItem()).toString();
            materials.merge(materialId, 1, Integer::sum);
        }

        // Bottom-up gives later construction passes a deterministic dependency-friendly starting order.
        placements.sort(Comparator
            .comparingInt((SchematicBuildPlan.Placement placement) -> placement.relativePosition().getY())
            .thenComparingInt(placement -> placement.relativePosition().getZ())
            .thenComparingInt(placement -> placement.relativePosition().getX()));
        return new SchematicBuildPlan(source, new SchematicBuildPlan.Dimensions(width, height, length), offset,
            placements, new LinkedHashMap<>(materials), ignoredAirBlocks);
    }

    /** Loads Litematica's native multi-region .litematic container. */
    private static SchematicBuildPlan loadLitematic(Path source, CompoundTag root, String filename) throws SchematicLoadException {
        CompoundTag regions = readCompound(root, "Regions");
        if (regions == null || regions.keySet().isEmpty()) {
            throw new SchematicLoadException(filename + " has no Litematica Regions.");
        }

        List<SchematicBuildPlan.Placement> placements = new ArrayList<>();
        Map<String, Integer> materials = new HashMap<>();
        int ignoredAirBlocks = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        long totalVolume = 0;

        for (String name : regions.keySet()) {
            CompoundTag region = readCompound(regions, name);
            if (region == null) continue;
            BlockPos position = readBlockPos(region, "Position");
            BlockPos size = readBlockPos(region, "Size");
            int width = Math.abs(size.getX());
            int height = Math.abs(size.getY());
            int length = Math.abs(size.getZ());
            if (width == 0 || height == 0 || length == 0) {
                throw new SchematicLoadException(filename + " has region " + name + " with an invalid Size.");
            }
            long volume = (long) width * height * length;
            totalVolume += volume;
            if (totalVolume > MAX_SCHEMATIC_VOLUME) {
                throw new SchematicLoadException(filename + " contains more than " + MAX_SCHEMATIC_VOLUME + " blocks.");
            }
            ListTag paletteTag = readList(region, "BlockStatePalette");
            if (paletteTag == null || paletteTag.isEmpty()) {
                throw new SchematicLoadException(filename + " region " + name + " has no BlockStatePalette.");
            }
            List<PaletteEntry> palette = new ArrayList<>();
            for (int index = 0; index < paletteTag.size(); index++) {
                CompoundTag state = readListCompound(paletteTag, index);
                palette.add(new PaletteEntry(parseBlockState(toStateId(state, filename), filename), toStateId(state, filename)));
            }
            long[] data = readLongArray(region, "BlockStates");
            List<Integer> indices = decodePackedLongs(data, volume, palette.size(), filename, name);
            for (int index = 0; index < indices.size(); index++) {
                PaletteEntry entry = indices.get(index) >= 0 && indices.get(index) < palette.size() ? palette.get(indices.get(index)) : null;
                if (entry == null) throw new SchematicLoadException(filename + " region " + name + " references palette index " + indices.get(index) + ".");
                int localX = index % width;
                int localZ = (index / width) % length;
                int localY = index / (width * length);
                // Litematica preserves selection direction in Size, but stores
                // BlockStates from the region's minimum corner in +X/+Y/+Z order.
                int originX = position.getX() + (size.getX() < 0 ? size.getX() + 1 : 0);
                int originY = position.getY() + (size.getY() < 0 ? size.getY() + 1 : 0);
                int originZ = position.getZ() + (size.getZ() < 0 ? size.getZ() + 1 : 0);
                int x = originX + localX;
                int y = originY + localY;
                int z = originZ + localZ;
                minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
                if (entry.state().isAir()) { ignoredAirBlocks++; continue; }
                placements.add(new SchematicBuildPlan.Placement(new BlockPos(x, y, z), entry.state(), entry.stateId()));
                materials.merge(BuiltInRegistries.ITEM.getKey(entry.state().getBlock().asItem()).toString(), 1, Integer::sum);
            }
        }
        if (minX == Integer.MAX_VALUE) throw new SchematicLoadException(filename + " contains no Litematica block data.");
        BlockPos origin = new BlockPos(minX, minY, minZ);
        List<SchematicBuildPlan.Placement> normalized = placements.stream()
            .map(p -> new SchematicBuildPlan.Placement(p.relativePosition().subtract(origin), p.state(), p.stateId()))
            .sorted(Comparator.comparingInt((SchematicBuildPlan.Placement p) -> p.relativePosition().getY()).thenComparingInt(p -> p.relativePosition().getZ()).thenComparingInt(p -> p.relativePosition().getX()))
            .toList();
        return new SchematicBuildPlan(source, new SchematicBuildPlan.Dimensions(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1),
            origin, normalized, new LinkedHashMap<>(materials), ignoredAirBlocks);
    }

    /** Loads the vanilla structure-block .nbt format. */
    private static SchematicBuildPlan loadStructure(Path source, CompoundTag root, String filename) throws SchematicLoadException {
        ListTag paletteTag = readList(root, "palette");
        ListTag blocksTag = readList(root, "blocks");
        if (paletteTag == null || blocksTag == null) throw new SchematicLoadException(filename + " is not a vanilla structure NBT file.");
        List<PaletteEntry> palette = new ArrayList<>();
        for (int index = 0; index < paletteTag.size(); index++) {
            CompoundTag state = readListCompound(paletteTag, index);
            String stateId = toStateId(state, filename);
            palette.add(new PaletteEntry(parseBlockState(stateId, filename), stateId));
        }
        int[] size = readIntArray(root, "size");
        if (size.length != 3 || size[0] <= 0 || size[1] <= 0 || size[2] <= 0) throw new SchematicLoadException(filename + " has an invalid size.");
        List<SchematicBuildPlan.Placement> placements = new ArrayList<>();
        Map<String, Integer> materials = new HashMap<>(); int ignoredAirBlocks = 0;
        for (int index = 0; index < blocksTag.size(); index++) {
            CompoundTag block = readListCompound(blocksTag, index);
            int paletteIndex = readNumber(block, "getInt", "state");
            if (paletteIndex < 0 || paletteIndex >= palette.size()) throw new SchematicLoadException(filename + " references palette index " + paletteIndex + ".");
            int[] pos = readIntArray(block, "pos");
            if (pos.length != 3) throw new SchematicLoadException(filename + " has a block without a valid position.");
            PaletteEntry entry = palette.get(paletteIndex);
            if (entry.state().isAir()) { ignoredAirBlocks++; continue; }
            placements.add(new SchematicBuildPlan.Placement(new BlockPos(pos[0], pos[1], pos[2]), entry.state(), entry.stateId()));
            materials.merge(BuiltInRegistries.ITEM.getKey(entry.state().getBlock().asItem()).toString(), 1, Integer::sum);
        }
        placements.sort(Comparator.comparingInt((SchematicBuildPlan.Placement p) -> p.relativePosition().getY()).thenComparingInt(p -> p.relativePosition().getZ()).thenComparingInt(p -> p.relativePosition().getX()));
        return new SchematicBuildPlan(source, new SchematicBuildPlan.Dimensions(size[0], size[1], size[2]), BlockPos.ZERO,
            placements, new LinkedHashMap<>(materials), ignoredAirBlocks);
    }

    private static int readPositiveDimension(CompoundTag root, String key, String filename) throws SchematicLoadException {
        int value = readNumber(root, "getShort", key);
        if (value <= 0) {
            value = readNumber(root, "getInt", key);
        }
        if (value <= 0) {
            throw new SchematicLoadException(filename + " has an invalid " + key + " dimension.");
        }
        return value;
    }

    private static Map<Integer, PaletteEntry> readPalette(CompoundTag root, String filename) throws SchematicLoadException {
        CompoundTag paletteTag = readCompound(root, "Palette");
        if (paletteTag == null) {
            throw new SchematicLoadException(filename + " has no Sponge Palette.");
        }
        Map<Integer, PaletteEntry> palette = new HashMap<>();
        for (String stateId : paletteTag.keySet()) {
            int index = readNumber(paletteTag, "getInt", stateId);
            if (index < 0 || palette.put(index, new PaletteEntry(parseBlockState(stateId, filename), stateId)) != null) {
                throw new SchematicLoadException(filename + " has an invalid or duplicate palette index: " + index + ".");
            }
        }
        if (palette.isEmpty()) {
            throw new SchematicLoadException(filename + " has an empty Palette.");
        }
        return palette;
    }

    private static List<Integer> decodeVarInts(byte[] data, long expectedCount, String filename) throws SchematicLoadException {
        List<Integer> values = new ArrayList<>((int) expectedCount);
        int value = 0;
        int shift = 0;
        for (byte raw : data) {
            int unsigned = raw & 0xFF;
            value |= (unsigned & 0x7F) << shift;
            if ((unsigned & 0x80) == 0) {
                values.add(value);
                value = 0;
                shift = 0;
                if (values.size() > expectedCount) {
                    throw new SchematicLoadException(filename + " has more BlockData entries than its dimensions allow.");
                }
            } else {
                shift += 7;
                if (shift >= 35) {
                    throw new SchematicLoadException(filename + " has an invalid BlockData varint.");
                }
            }
        }
        if (shift != 0 || values.size() != expectedCount) {
            throw new SchematicLoadException(filename + " BlockData does not match its dimensions (expected " + expectedCount
                + " entries, found " + values.size() + ").");
        }
        return values;
    }

    private static BlockPos readOffset(CompoundTag root) {
        int[] offset = readIntArray(root, "Offset");
        return offset.length == 3 ? new BlockPos(offset[0], offset[1], offset[2]) : BlockPos.ZERO;
    }

    /**
     * CompoundTag changed its typed getters from direct values to Optional values
     * during the supported 1.21 range. Keep that version seam out of the parser.
     */
    private static Object readTagValue(CompoundTag tag, String methodName, String key) throws SchematicLoadException {
        try {
            Method method = CompoundTag.class.getMethod(methodName, String.class);
            Object value = method.invoke(tag, key);
            return value instanceof Optional<?> optional ? optional.orElse(null) : value;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new SchematicLoadException("Could not read schematic NBT field " + key + ".", exception);
        }
    }

    private static int readNumber(CompoundTag tag, String methodName, String key) throws SchematicLoadException {
        Object value = readTagValue(tag, methodName, key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static byte[] readByteArray(CompoundTag tag, String key) throws SchematicLoadException {
        Object value = readTagValue(tag, "getByteArray", key);
        return value instanceof byte[] array ? array : new byte[0];
    }

    private static int[] readIntArray(CompoundTag tag, String key) {
        try {
            Object value = readTagValue(tag, "getIntArray", key);
            return value instanceof int[] array ? array : new int[0];
        } catch (SchematicLoadException ignored) {
            return new int[0];
        }
    }

    private static long[] readLongArray(CompoundTag tag, String key) throws SchematicLoadException {
        Object value = readTagValue(tag, "getLongArray", key);
        return value instanceof long[] array ? array : new long[0];
    }

    private static ListTag readList(CompoundTag tag, String key) throws SchematicLoadException {
        Object value = readTagValue(tag, "getList", key);
        return value instanceof ListTag list ? list : null;
    }

    private static CompoundTag readListCompound(ListTag tag, int index) throws SchematicLoadException {
        try {
            Method method = ListTag.class.getMethod("getCompound", int.class);
            Object value = method.invoke(tag, index);
            if (value instanceof Optional<?> optional) value = optional.orElse(null);
            if (value instanceof CompoundTag compound) return compound;
            throw new SchematicLoadException("Schematic palette entry " + index + " is not a compound tag.");
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new SchematicLoadException("Could not read schematic palette entry.", exception);
        }
    }

    private static String readString(CompoundTag tag, String key) throws SchematicLoadException {
        Object value = readTagValue(tag, "getString", key);
        return value instanceof String string ? string : "";
    }

    private static BlockPos readBlockPos(CompoundTag root, String key) throws SchematicLoadException {
        int[] array = readIntArray(root, key);
        if (array.length == 3) return new BlockPos(array[0], array[1], array[2]);
        CompoundTag value = readCompound(root, key);
        if (value == null) return BlockPos.ZERO;
        return new BlockPos(readNumber(value, "getInt", "x"), readNumber(value, "getInt", "y"), readNumber(value, "getInt", "z"));
    }

    private static String toStateId(CompoundTag tag, String filename) throws SchematicLoadException {
        String name = readString(tag, "Name");
        if (name.isBlank()) throw new SchematicLoadException(filename + " has a palette entry without a Name.");
        CompoundTag properties = readCompound(tag, "Properties");
        if (properties == null || properties.keySet().isEmpty()) return name;
        List<String> assignments = new ArrayList<>();
        for (String property : properties.keySet()) {
            String value = readString(properties, property);
            if (value.isBlank()) throw new SchematicLoadException(filename + " has an invalid " + property + " property.");
            assignments.add(property + "=" + value);
        }
        assignments.sort(String::compareTo);
        return name + "[" + String.join(",", assignments) + "]";
    }

    private static List<Integer> decodePackedLongs(long[] data, long count, int paletteSize, String filename, String region)
        throws SchematicLoadException {
        if (count > Integer.MAX_VALUE) throw new SchematicLoadException(filename + " is too large to load.");
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
        long requiredBits = count * bits;
        if (data.length == 0 || requiredBits > (long) data.length * Long.SIZE) {
            throw new SchematicLoadException(filename + " region " + region + " has incomplete BlockStates data.");
        }
        long mask = (1L << bits) - 1;
        List<Integer> values = new ArrayList<>((int) count);
        for (int index = 0; index < count; index++) {
            long bitIndex = (long) index * bits;
            int word = (int) (bitIndex >>> 6);
            int shift = (int) (bitIndex & 63);
            long value = data[word] >>> shift;
            if (shift + bits > Long.SIZE) value |= data[word + 1] << (Long.SIZE - shift);
            values.add((int) (value & mask));
        }
        return values;
    }

    private static CompoundTag readCompound(CompoundTag tag, String key) throws SchematicLoadException {
        Object value = readTagValue(tag, "getCompound", key);
        return value instanceof CompoundTag compound ? compound : null;
    }

    private static BlockState parseBlockState(String stateId, String filename) throws SchematicLoadException {
        int propertyStart = stateId.indexOf('[');
        String blockId = propertyStart < 0 ? stateId : stateId.substring(0, propertyStart);
        if (propertyStart >= 0 && (!stateId.endsWith("]") || propertyStart == stateId.length() - 1)) {
            throw new SchematicLoadException(filename + " has malformed block state " + stateId + ".");
        }
        Identifier identifier = Identifier.tryParse(blockId);
        if (identifier == null || !BuiltInRegistries.BLOCK.containsKey(identifier)) {
            throw new SchematicLoadException(filename + " references unavailable block " + blockId + ".");
        }
        Block block = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
        if (block == null) {
            throw new SchematicLoadException(filename + " references unavailable block " + blockId + ".");
        }
        BlockState state = block.defaultBlockState();
        if (propertyStart < 0) {
            return state;
        }
        String properties = stateId.substring(propertyStart + 1, stateId.length() - 1);
        for (String assignment : properties.split(",")) {
            String[] pair = assignment.split("=", 2);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
                throw new SchematicLoadException(filename + " has malformed property in " + stateId + ".");
            }
            Property<?> property = state.getBlock().getStateDefinition().getProperty(pair[0]);
            if (property == null) {
                throw new SchematicLoadException(filename + " uses unknown property " + pair[0] + " for " + blockId + ".");
            }
            state = applyProperty(state, property, pair[1], filename, stateId);
        }
        return state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState applyProperty(BlockState state, Property property, String value, String filename, String stateId)
        throws SchematicLoadException {
        Optional<Comparable> parsed = property.getValue(value);
        if (parsed.isEmpty()) {
            throw new SchematicLoadException(filename + " uses invalid value " + value + " in " + stateId + ".");
        }
        return state.setValue(property, parsed.get());
    }

    private record PaletteEntry(BlockState state, String stateId) {
    }
}
