package fi.dy.masa.litematica.schematic;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityStructure;
import net.minecraft.util.Mirror;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.structure.template.PlacementSettings;
import net.minecraft.world.gen.structure.template.Template;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.schematic.container.ILitematicaBlockStatePalette;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.interfaces.IStringConsumer;
import fi.dy.masa.malilib.util.Constants;
import fi.dy.masa.malilib.util.NBTUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class SchematicaSchematic
{
    public static final String FILE_EXTENSION = ".schematic";
    private LitematicaBlockStateContainer blocks;
    private Block[] palette;
    private Map<BlockPos, NBTTagCompound> tiles = new HashMap<>();
    private List<NBTTagCompound> entities = new ArrayList<>();
    private Vec3i size = Vec3i.NULL_VECTOR;
    private String fileName;

    private SchematicaSchematic()
    {
    }

    public Vec3i getSize()
    {
        return this.size;
    }

    public Map<BlockPos, NBTTagCompound> getTiles()
    {
        return this.tiles;
    }

    public List<EntityInfo> getEntities()
    {
        List<EntityInfo> entityList = new ArrayList<>();
        final int size = this.entities.size();

        for (int i = 0; i < size; ++i)
        {
            NBTTagCompound entityData = this.entities.get(i);
            Vec3d posVec = NBTUtils.readEntityPositionFromTag(entityData);

            if (posVec != null && entityData.isEmpty() == false)
            {
                entityList.add(new EntityInfo(posVec, entityData));
            }
        }

        return entityList;
    }

    public void placeSchematicToWorld(World world, BlockPos posStart, PlacementSettings placement, int setBlockStateFlags)
    {
        final int width = this.size.getX();
        final int height = this.size.getY();
        final int length = this.size.getZ();
        final int numBlocks = width * height * length;

        if (this.blocks != null && numBlocks > 0 && this.blocks.getSize().equals(this.size))
        {
            final Block ignoredBlock = placement.getReplacedBlock();
            final Rotation rotation = placement.getRotation();
            final Mirror mirror = placement.getMirror();

            // Place blocks and read any TileEntity data
            for (int y = 0; y < height; ++y)
            {
                for (int z = 0; z < length; ++z)
                {
                    for (int x = 0; x < width; ++x)
                    {
                        IBlockState state = this.blocks.get(x, y, z);

                        if (ignoredBlock != null && state.getBlock() == ignoredBlock)
                        {
                            continue;
                        }

                        BlockPos pos = new BlockPos(x, y, z);
                        NBTTagCompound teNBT = this.tiles.get(pos);

                        pos = Template.transformedBlockPos(placement, pos).add(posStart);

                        state = state.withMirror(mirror);
                        state = state.withRotation(rotation);

                        if (teNBT != null)
                        {
                            TileEntity te = world.getTileEntity(pos);

                            if (te != null)
                            {
                                if (te instanceof IInventory)
                                {
                                    ((IInventory) te).clear();
                                }

                                world.setBlockState(pos, Blocks.BARRIER.getDefaultState(), 0x14);
                            }
                        }

                        if (world.setBlockState(pos, state, setBlockStateFlags) && teNBT != null)
                        {
                            TileEntity te = world.getTileEntity(pos);

                            if (te != null)
                            {
                                teNBT.setInteger("x", pos.getX());
                                teNBT.setInteger("y", pos.getY());
                                teNBT.setInteger("z", pos.getZ());

                                try
                                {
                                    te.readFromNBT(teNBT);
                                    te.mirror(mirror);
                                    te.rotate(rotation);
                                }
                                catch (Exception e)
                                {
                                    Litematica.logger.warn("Failed to load TileEntity data for {} @ {}", state, pos);
                                }
                            }
                        }
                    }
                }
            }

            if ((setBlockStateFlags & 0x01) != 0)
            {
                // Update blocks
                for (int y = 0; y < height; ++y)
                {
                    for (int z = 0; z < length; ++z)
                    {
                        for (int x = 0; x < width; ++x)
                        {
                            BlockPos pos = new BlockPos(x, y, z);
                            NBTTagCompound teNBT = this.tiles.get(pos);

                            pos = Template.transformedBlockPos(placement, pos).add(posStart);
                            world.notifyNeighborsRespectDebug(pos, world.getBlockState(pos).getBlock(), false);

                            if (teNBT != null)
                            {
                                TileEntity te = world.getTileEntity(pos);

                                if (te != null)
                                {
                                    te.markDirty();
                                }
                            }
                        }
                    }
                }
            }

            if (placement.getIgnoreEntities() == false)
            {
                this.addEntitiesToWorld(world, posStart, placement);
            }
        }
    }

    public void placeSchematicDirectlyToChunks(World world, BlockPos posStart, PlacementSettings placement)
    {
        final int width = this.size.getX();
        final int height = this.size.getY();
        final int length = this.size.getZ();
        final int numBlocks = width * height * length;
        BlockPos posEnd = posStart.add(this.size).add(-1, -1, -1);
        //BlockPos posEnd = Template.transformedBlockPos(placement, (new BlockPos(this.size)).add(-1, -1, -1)).add(posStart);

        if (this.blocks != null && numBlocks > 0 && this.blocks.getSize().equals(this.size) &&
            PositionUtils.arePositionsWithinWorld(world, posStart, posEnd))
        {
            final BlockPos posMin = PositionUtils.getMinCorner(posStart, posEnd);
            final BlockPos posMax = PositionUtils.getMaxCorner(posStart, posEnd);
            final Block ignoredBlock = placement.getReplacedBlock();
            final Rotation rotation = placement.getRotation();
            final Mirror mirror = placement.getMirror();
            final int cxStart = posMin.getX() >> 4;
            final int czStart = posMin.getZ() >> 4;
            final int cxEnd = posMax.getX() >> 4;
            final int czEnd = posMax.getZ() >> 4;

            for (int cz = czStart; cz <= czEnd; ++cz)
            {
                for (int cx = cxStart; cx <= cxEnd; ++cx)
                {
                    final int xMinChunk = Math.max(cx << 4, posMin.getX());
                    final int zMinChunk = Math.max(cz << 4, posMin.getZ());
                    final int xMaxChunk = Math.min((cx << 4) + 15, posMax.getX());
                    final int zMaxChunk = Math.min((cz << 4) + 15, posMax.getZ());
                    Chunk chunk = world.getChunk(cx, cz);

                    for (int y = posMin.getY(), ySrc = 0; ySrc < height; ++y, ++ySrc)
                    {
                        for (int z = zMinChunk, zSrc = zMinChunk - posStart.getZ(); z <= zMaxChunk; ++z, ++zSrc)
                        {
                            for (int x = xMinChunk, xSrc = xMinChunk - posStart.getX(); x <= xMaxChunk; ++x, ++xSrc)
                            {
                                IBlockState state = this.blocks.get(xSrc, ySrc, zSrc);

                                if (state.getBlock() == ignoredBlock)
                                {
                                    continue;
                                }

                                BlockPos pos = new BlockPos(x, y, z);
                                NBTTagCompound teNBT = this.tiles.get(pos);

                                // TODO The rotations need to be transformed back to get the correct source position in the schematic...
                                /*
                                pos = Template.transformedBlockPos(placement, pos).add(posStart);

                                state = state.withMirror(mirror);
                                state = state.withRotation(rotation);
                                */

                                if (teNBT != null)
                                {
                                    TileEntity te = chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);

                                    if (te != null)
                                    {
                                        if (te instanceof IInventory)
                                        {
                                            ((IInventory) te).clear();
                                        }

                                        world.setBlockState(pos, Blocks.BARRIER.getDefaultState(), 0x14);
                                    }
                                }

                                chunk.setBlockState(pos, state);

                                if (teNBT != null)
                                {
                                    TileEntity te = chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);

                                    if (te != null)
                                    {
                                        teNBT.setInteger("x", pos.getX());
                                        teNBT.setInteger("y", pos.getY());
                                        teNBT.setInteger("z", pos.getZ());

                                        try
                                        {
                                            te.readFromNBT(teNBT);
                                            te.mirror(mirror);
                                            te.rotate(rotation);
                                        }
                                        catch (Exception e)
                                        {
                                            Litematica.logger.warn("Failed to load TileEntity data for {} @ {}", state, pos);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (placement.getIgnoreEntities() == false)
            {
                this.addEntitiesToWorld(world, posStart, placement);
            }
        }
    }

    private void addEntitiesToWorld(World world, BlockPos posStart, PlacementSettings placement)
    {
        Mirror mirror = placement.getMirror();
        Rotation rotation = placement.getRotation();

        for (NBTTagCompound tag : this.entities)
        {
            Vec3d relativePos = NBTUtils.readEntityPositionFromTag(tag);
            Vec3d transformedRelativePos = PositionUtils.getTransformedPosition(relativePos, mirror, rotation);
            Vec3d realPos = transformedRelativePos.add(posStart.getX(), posStart.getY(), posStart.getZ());
            Entity entity = EntityUtils.createEntityAndPassengersFromNBT(tag, world);

            if (entity != null)
            {
                float rotationYaw = entity.getMirroredYaw(mirror);
                rotationYaw = rotationYaw + (entity.rotationYaw - entity.getRotatedYaw(rotation));
                entity.setLocationAndAngles(realPos.x, realPos.y, realPos.z, rotationYaw, entity.rotationPitch);
                EntityUtils.spawnEntityAndPassengersInWorld(entity, world);
            }
        }
    }

    public Map<BlockPos, String> getDataStructureBlocks(BlockPos posStart, PlacementSettings placement)
    {
        Map<BlockPos, String> map = new HashMap<>();

        for (Map.Entry<BlockPos, NBTTagCompound> entry : this.tiles.entrySet())
        {
            NBTTagCompound tag = entry.getValue();

            if (tag.getString("id").equals("minecraft:structure_block") &&
                TileEntityStructure.Mode.valueOf(tag.getString("mode")) == TileEntityStructure.Mode.DATA)
            {
                BlockPos pos = entry.getKey();
                pos = Template.transformedBlockPos(placement, pos).add(posStart);
                map.put(pos, tag.getString("metadata"));
            }
        }

        return map;
    }

    private void readBlocksFromWorld(World world, BlockPos posStart, BlockPos size)
    {
        final int startX = posStart.getX();
        final int startY = posStart.getY();
        final int startZ = posStart.getZ();
        final int endX = startX + size.getX();
        final int endY = startY + size.getY();
        final int endZ = startZ + size.getZ();
        BlockPos.MutableBlockPos posMutable = new BlockPos.MutableBlockPos(0, 0, 0);

        this.blocks = new LitematicaBlockStateContainer(size.getX(), size.getY(), size.getZ());
        this.tiles.clear();
        this.size = size;

        for (int y = startY; y < endY; ++y)
        {
            for (int z = startZ; z < endZ; ++z)
            {
                for (int x = startX; x < endX; ++x)
                {
                    int relX = x - startX;
                    int relY = y - startY;
                    int relZ = z - startZ;

                    posMutable.setPos(x, y, z);
                    IBlockState state = world.getBlockState(posMutable);
                    this.blocks.set(relX, relY, relZ, state);

                    TileEntity te = world.getTileEntity(posMutable);

                    if (te != null)
                    {
                        try
                        {
                            NBTTagCompound nbt = te.writeToNBT(new NBTTagCompound());

                            nbt.setInteger("x", relX);
                            nbt.setInteger("y", relY);
                            nbt.setInteger("z", relZ);

                            this.tiles.put(new BlockPos(relX, relY, relZ), nbt);
                        }
                        catch (Exception e)
                        {
                            Litematica.logger.warn("SchematicaSchematic: Exception while trying to store TileEntity data for block '{}' at {}",
                                    state, posMutable.toString(), e);
                        }
                    }
                }
            }
        }
    }

    private void readEntitiesFromWorld(World world, BlockPos posStart, BlockPos size)
    {
        this.entities.clear();
        List<Entity> entities = world.getEntitiesWithinAABBExcludingEntity(null, new AxisAlignedBB(posStart, posStart.add(size)));

        for (Entity entity : entities)
        {
            NBTTagCompound tag = new NBTTagCompound();

            if (entity.writeToNBTOptional(tag))
            {
                Vec3d pos = new Vec3d(entity.posX - posStart.getX(), entity.posY - posStart.getY(), entity.posZ - posStart.getZ());
                NBTUtils.writeEntityPositionToTag(pos, tag);

                this.entities.add(tag);
            }
        }
    }

    public static SchematicaSchematic createFromWorld(World world, BlockPos posStart, BlockPos size, boolean ignoreEntities)
    {
        SchematicaSchematic schematic = new SchematicaSchematic();

        schematic.readBlocksFromWorld(world, posStart, size);

        if (ignoreEntities == false)
        {
            schematic.readEntitiesFromWorld(world, posStart, size);
        }

        return schematic;
    }

    @Nullable
    public static SchematicaSchematic createFromFile(File file)
    {
        SchematicaSchematic schematic = new SchematicaSchematic();

        if (schematic.readFromFile(file))
        {
            return schematic;
        }

        return null;
    }

    public boolean readFromNBT(NBTTagCompound nbt)
    {
        if (this.readBlocksFromNBT(nbt))
        {
            this.readEntitiesFromNBT(nbt);
            this.readTileEntitiesFromNBT(nbt);

            return true;
        }
        else
        {
            Litematica.logger.error("SchematicaSchematic: Missing block data in the schematic '{}'", this.fileName);
            return false;
        }
    }

    private boolean readPaletteFromNBT(NBTTagCompound nbt)
    {
        final Block air = Blocks.AIR;
        this.palette = new Block[4096];
        Arrays.fill(this.palette, air);

        // Schematica palette
        if (nbt.hasKey("SchematicaMapping", Constants.NBT.TAG_COMPOUND))
        {
            NBTTagCompound tag = nbt.getCompoundTag("SchematicaMapping");
            Set<String> keys = tag.getKeySet();

            for (String key : keys)
            {
                int id = tag.getShort(key);

                if (id >= this.palette.length)
                {
                    Litematica.logger.error("SchematicaSchematic: Invalid ID '{}' in SchematicaMapping for block '{}', max = 4095", id, key);
                    return false;
                }

                Block block = Block.REGISTRY.getObject(new ResourceLocation(key));

                if (block != null)
                {
                    this.palette[id] = block;
                }
                else
                {
                    Litematica.logger.error("SchematicaSchematic: Missing/non-existing block '{}' in SchematicaMapping", key);
                }
            }
        }
        // MCEdit2 palette
        else if (nbt.hasKey("BlockIDs", Constants.NBT.TAG_COMPOUND))
        {
            NBTTagCompound tag = nbt.getCompoundTag("BlockIDs");
            Set<String> keys = tag.getKeySet();

            for (String idStr : keys)
            {
                String key = tag.getString(idStr);
                int id;

                try
                {
                    id = Integer.parseInt(idStr);
                }
                catch (NumberFormatException e)
                {
                    Litematica.logger.error("SchematicaSchematic: Invalid ID '{}' (not a number) in MCEdit2 palette for block '{}'", idStr, key);
                    continue;
                }

                if (id >= this.palette.length)
                {
                    Litematica.logger.error("SchematicaSchematic: Invalid ID '{}' in MCEdit2 palette for block '{}', max = 4095", id, key);
                    return false;
                }

                Block block = Block.REGISTRY.getObject(new ResourceLocation(key));

                if (block != null)
                {
                    this.palette[id] = block;
                }
                else
                {
                    Litematica.logger.error("SchematicaSchematic: Missing/non-existing block '{}' in MCEdit2 palette", key);
                }
            }
        }
        // No palette, use the current registry IDs directly
        else
        {
            for (ResourceLocation key : Block.REGISTRY.getKeys())
            {
                Block block = Block.REGISTRY.getObject(key);

                if (block != null)
                {
                    int id = Block.getIdFromBlock(block);

                    if (id >= 0 && id < this.palette.length)
                    {
                        this.palette[id] = block;
                    }
                    else
                    {
                        Litematica.logger.error("SchematicaSchematic: Invalid ID {} for block '{}' from the registry", id, key);
                    }
                }
            }
        }

        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean readBlocksFromNBT(NBTTagCompound nbt)
    {
        if (nbt.hasKey("Blocks", Constants.NBT.TAG_BYTE_ARRAY) == false ||
            nbt.hasKey("Data", Constants.NBT.TAG_BYTE_ARRAY) == false ||
            nbt.hasKey("Width", Constants.NBT.TAG_SHORT) == false ||
            nbt.hasKey("Height", Constants.NBT.TAG_SHORT) == false ||
            nbt.hasKey("Length", Constants.NBT.TAG_SHORT) == false)
        {
            return false;
        }

        // This method was implemented based on
        // https://minecraft.gamepedia.com/Schematic_file_format
        // as it was on 2018-04-18.

        final int sizeX = nbt.getShort("Width");
        final int sizeY = nbt.getShort("Height");
        final int sizeZ = nbt.getShort("Length");
        final byte[] blockIdsByte = nbt.getByteArray("Blocks");
        final byte[] metaArr = nbt.getByteArray("Data");
        final int numBlocks = blockIdsByte.length;
        final int layerSize = sizeX * sizeZ;

        if (numBlocks != (sizeX * sizeY * sizeZ))
        {
            Litematica.logger.error("SchematicaSchematic: Mismatched block array size compared to the width/height/length, blocks: {}, W x H x L: {} x {} x {}",
                    numBlocks, sizeX, sizeY, sizeZ);
            return false;
        }

        if (numBlocks != metaArr.length)
        {
            Litematica.logger.error("SchematicaSchematic: Mismatched block ID and metadata array sizes, blocks: {}, meta: {}", numBlocks, metaArr.length);
            return false;
        }

        if (this.readPaletteFromNBT(nbt) == false)
        {
            Litematica.logger.error("SchematicaSchematic: Failed to read the block palette");
            return false;
        }

        this.size = new Vec3i(sizeX, sizeY, sizeZ);
        this.blocks = new LitematicaBlockStateContainer(sizeX, sizeY, sizeZ);

        if (nbt.hasKey("AddBlocks", Constants.NBT.TAG_BYTE_ARRAY))
        {
            byte[] add = nbt.getByteArray("AddBlocks");
            final int expectedAddLength = (int) Math.ceil((double) blockIdsByte.length / 2D);

            if (add.length != expectedAddLength)
            {
                Litematica.logger.error("SchematicaSchematic: Add array size mismatch, blocks: {}, add: {}, expected add: {}",
                        numBlocks, add.length, expectedAddLength);
                return false;
            }

            final int loopMax;

            // Even number of blocks, we can handle two position (meaning one full add byte) at a time
            if ((numBlocks % 2) == 0)
            {
                loopMax = numBlocks - 1;
            }
            else
            {
                loopMax = numBlocks - 2;
            }

            Block block;
            int byteId;
            int bi, ai;

            // Handle two positions per iteration, ie. one full byte of the add array
            for (bi = 0, ai = 0; bi < loopMax; bi += 2, ai++)
            {
                final int addValue = add[ai];

                byteId = blockIdsByte[bi    ] & 0xFF;
                block = this.palette[(addValue & 0xF0) << 4 | byteId];
                int x = bi % sizeX;
                int y = bi / layerSize;
                int z = (bi % layerSize) / sizeX;
                this.blocks.set(x, y, z, block.getStateFromMeta(metaArr[bi    ]));

                x = (bi + 1) % sizeX;
                y = (bi + 1) / layerSize;
                z = ((bi + 1) % layerSize) / sizeX;
                byteId = blockIdsByte[bi + 1] & 0xFF;
                block = this.palette[(addValue & 0x0F) << 8 | byteId];
                this.blocks.set(x, y, z, block.getStateFromMeta(metaArr[bi + 1]));
            }

            // Odd number of blocks, handle the last position
            if ((numBlocks % 2) != 0)
            {
                final int addValue = add[ai];
                byteId = blockIdsByte[bi    ] & 0xFF;
                block = this.palette[(addValue & 0xF0) << 4 | byteId];
                int x = bi % sizeX;
                int y = bi / layerSize;
                int z = (bi % layerSize) / sizeX;
                this.blocks.set(x, y, z, block.getStateFromMeta(metaArr[bi    ]));
            }
        }
        // Old Schematica format
        else if (nbt.hasKey("Add", Constants.NBT.TAG_BYTE_ARRAY))
        {
            // FIXME is this array 4 or 8 bits per block?
            Litematica.logger.error("SchematicaSchematic: Old Schematica format detected, not currently implemented...");
            return false;
        }
        // No palette, use the registry IDs directly
        else
        {
            for (int i = 0; i < numBlocks; i++)
            {
                Block block = this.palette[blockIdsByte[i] & 0xFF];
                int x = i % sizeX;
                int y = i / layerSize;
                int z = (i % layerSize) / sizeX;
                this.blocks.set(x, y, z, block.getStateFromMeta(metaArr[i]));
            }
        }

        return true;
    }

    private void readEntitiesFromNBT(NBTTagCompound nbt)
    {
        this.entities.clear();
        NBTTagList tagList = nbt.getTagList("Entities", Constants.NBT.TAG_COMPOUND);

        for (int i = 0; i < tagList.tagCount(); ++i)
        {
            this.entities.add(tagList.getCompoundTagAt(i));
        }
    }

    private void readTileEntitiesFromNBT(NBTTagCompound nbt)
    {
        this.tiles.clear();
        NBTTagList tagList = nbt.getTagList("TileEntities", Constants.NBT.TAG_COMPOUND);

        for (int i = 0; i < tagList.tagCount(); ++i)
        {
            NBTTagCompound tag = tagList.getCompoundTagAt(i);
            BlockPos pos = new BlockPos(tag.getInteger("x"), tag.getInteger("y"), tag.getInteger("z"));
            this.tiles.put(pos, tag);
        }
    }

    public boolean readFromFile(File file)
    {
        if (file.exists() && file.isFile() && file.canRead())
        {
            this.fileName = file.getName();

            try
            {
                FileInputStream is = new FileInputStream(file);
                NBTTagCompound nbt = CompressedStreamTools.readCompressed(is);
                is.close();

                return this.readFromNBT(nbt);
            }
            catch (Exception e)
            {
                Litematica.logger.error("SchematicaSchematic: Failed to read Schematic data from file '{}'", file.getAbsolutePath());
            }
        }

        return false;
    }

    private void createPalette()
    {
        if (this.palette == null)
        {
            this.palette = new Block[4096];
            ILitematicaBlockStatePalette litematicaPalette = this.blocks.getPalette();
            final int numBlocks = litematicaPalette.getPaletteSize();

            for (int i = 0; i < numBlocks; ++i)
            {
                IBlockState state = litematicaPalette.getBlockState(i);
                Block block = state.getBlock();
                int id = Block.getIdFromBlock(block);

                if (id >= this.palette.length)
                {
                    throw new IllegalArgumentException(String.format("Block id %d for block '%s' is out of range, max allowed = %d!",
                            id, state, this.palette.length - 1));
                }

                this.palette[id] = block;
            }
        }
    }

    private void writePaletteToNBT(NBTTagCompound nbt)
    {
        NBTTagCompound tag = new NBTTagCompound();

        for (int i = 0; i < this.palette.length; ++i)
        {
            Block block = this.palette[i];

            if (block != null)
            {
                ResourceLocation rl = Block.REGISTRY.getNameForObject(block);

                if (rl != null)
                {
                    tag.setShort(rl.toString(), (short) (i & 0xFFF));
                }
            }
        }

        nbt.setTag("SchematicaMapping", tag);
    }

    private void writeBlocksToNBT(NBTTagCompound nbt)
    {
        nbt.setShort("Width", (short) this.size.getX());
        nbt.setShort("Height", (short) this.size.getY());
        nbt.setShort("Length", (short) this.size.getZ());
        nbt.setString("Materials", "Alpha");

        final int numBlocks = this.size.getX() * this.size.getY() * this.size.getZ();
        final int loopMax = (int) Math.floor((double) numBlocks / 2D);
        final int addSize = (int) Math.ceil((double) numBlocks / 2D);
        final byte[] blockIdsArr = new byte[numBlocks];
        final byte[] metaArr = new byte[numBlocks];
        final byte[] addArr = new byte[addSize];
        final int sizeX = this.size.getX();
        final int sizeZ = this.size.getZ();
        final int layerSize = sizeX * sizeZ;
        int numAdd = 0;
        int bi, ai;

        for (bi = 0, ai = 0; ai < loopMax; bi += 2, ++ai)
        {
            int x = bi % sizeX;
            int y = bi / layerSize;
            int z = (bi % layerSize) / sizeX;
            IBlockState state1 = this.blocks.get(x, y, z);

            x = (bi + 1) % sizeX;
            y = (bi + 1) / layerSize;
            z = ((bi + 1) % layerSize) / sizeX;
            IBlockState state2 = this.blocks.get(x, y, z);

            int id1 = Block.getIdFromBlock(state1.getBlock());
            int id2 = Block.getIdFromBlock(state2.getBlock());
            int add = ((id1 >>> 4) & 0xF0) | ((id2 >>> 8) & 0x0F);
            blockIdsArr[bi    ] = (byte) (id1 & 0xFF);
            blockIdsArr[bi + 1] = (byte) (id2 & 0xFF);

            if (add != 0)
            {
                addArr[ai] = (byte) add;
                numAdd++;
            }

            metaArr[bi    ] = (byte) state1.getBlock().getMetaFromState(state1);
            metaArr[bi + 1] = (byte) state2.getBlock().getMetaFromState(state2);
        }

        // Odd number of blocks, handle the last position
        if ((numBlocks % 2) != 0)
        {
            int x = bi % sizeX;
            int y = bi / layerSize;
            int z = (bi % layerSize) / sizeX;
            IBlockState state = this.blocks.get(x, y, z);

            int id = Block.getIdFromBlock(state.getBlock());
            int add = (id >>> 4) & 0xF0;
            blockIdsArr[bi] = (byte) (id & 0xFF);

            if (add != 0)
            {
                addArr[ai] = (byte) add;
                numAdd++;
            }

            metaArr[bi] = (byte) state.getBlock().getMetaFromState(state);
        }

        nbt.setByteArray("Blocks", blockIdsArr);
        nbt.setByteArray("Data", metaArr);

        if (numAdd > 0)
        {
            nbt.setByteArray("AddBlocks", addArr);
        }
    }

    private NBTTagCompound writeToNBT()
    {
        NBTTagCompound nbt = new NBTTagCompound();

        this.createPalette();
        this.writeBlocksToNBT(nbt);
        this.writePaletteToNBT(nbt);

        NBTTagList tagListTiles = new NBTTagList();
        NBTTagList tagListEntities = new NBTTagList();

        for (NBTTagCompound tag : this.entities)
        {
            tagListEntities.appendTag(tag);
        }

        for (NBTTagCompound tag : this.tiles.values())
        {
            tagListTiles.appendTag(tag);
        }

        nbt.setTag("TileEntities", tagListTiles);
        nbt.setTag("Entities", tagListEntities);

        return nbt;
    }

    public boolean writeToFile(File dir, String fileNameIn, boolean override, IStringConsumer feedback)
    {
        String fileName = fileNameIn;

        if (fileName.endsWith(FILE_EXTENSION) == false)
        {
            fileName = fileName + FILE_EXTENSION;
        }

        File fileSchematic = new File(dir, fileName);

        try
        {
            if (dir.exists() == false && dir.mkdirs() == false)
            {
                feedback.setString(StringUtils.translate("litematica.error.schematic_write_to_file_failed.directory_creation_failed", dir.getAbsolutePath()));
                return false;
            }

            if (override == false && fileSchematic.exists())
            {
                feedback.setString(StringUtils.translate("litematica.error.schematic_write_to_file_failed.exists", fileSchematic.getAbsolutePath()));
                return false;
            }

            FileOutputStream os = new FileOutputStream(fileSchematic);
            CompressedStreamTools.writeCompressed(this.writeToNBT(), os);
            os.close();

            return true;
        }
        catch (Exception e)
        {
            feedback.setString(StringUtils.translate("litematica.error.schematic_write_to_file_failed.exception", fileSchematic.getAbsolutePath()));
        }

        return false;
    }
}
