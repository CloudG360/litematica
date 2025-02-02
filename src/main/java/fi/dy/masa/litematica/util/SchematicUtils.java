package fi.dy.masa.litematica.util;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.commons.lang3.tuple.Pair;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBlockSpecial;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.gui.GuiSchematicSave;
import fi.dy.masa.litematica.gui.GuiSchematicSave.InMemorySchematicCreator;
import fi.dy.masa.litematica.mixin.IMixinItemBlockSpecial;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.scheduler.tasks.TaskBase;
import fi.dy.masa.litematica.scheduler.tasks.TaskDeleteArea;
import fi.dy.masa.litematica.scheduler.tasks.TaskPasteSchematicDirect;
import fi.dy.masa.litematica.scheduler.tasks.TaskPasteSchematicSetblock;
import fi.dy.masa.litematica.scheduler.tasks.TaskSaveSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.SchematicMetadata;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager.PlacementPart;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.schematic.projects.SchematicProject;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.tool.ToolMode;
import fi.dy.masa.litematica.util.RayTraceUtils.RayTraceWrapper;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextInput;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.interfaces.IStringConsumerFeedback;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.LayerRange;
import fi.dy.masa.malilib.util.SubChunkPos;

public class SchematicUtils
{
    private static long areaMovedTime;

    public static boolean saveSchematic(boolean inMemoryOnly)
    {
        SelectionManager sm = DataManager.getSelectionManager();
        AreaSelection area = sm.getCurrentSelection();

        if (area != null)
        {
            if (DataManager.getSchematicProjectsManager().hasProjectOpen())
            {
                String title = "litematica.gui.title.schematic_projects.save_new_version";
                SchematicProject project = DataManager.getSchematicProjectsManager().getCurrentProject();
                GuiTextInput gui = new GuiTextInput(512, title, project.getCurrentVersionName(), GuiUtils.getCurrentScreen(), new SchematicVersionCreator());
                GuiBase.openGui(gui);
            }
            else if (inMemoryOnly)
            {
                String title = "litematica.gui.title.create_in_memory_schematic";
                GuiTextInput gui = new GuiTextInput(512, title, area.getName(), GuiUtils.getCurrentScreen(), new InMemorySchematicCreator(area));
                GuiBase.openGui(gui);
            }
            else
            {
                GuiSchematicSave gui = new GuiSchematicSave();
                gui.setParent(GuiUtils.getCurrentScreen());
                GuiBase.openGui(gui);
            }

            return true;
        }

        return false;
    }

    public static void unloadCurrentlySelectedSchematic()
    {
        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();

        if (placement != null)
        {
            SchematicHolder.getInstance().removeSchematic(placement.getSchematic());
        }
        else
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.message.error.no_placement_selected");
        }
    }

    public static boolean breakSchematicBlock(Minecraft mc)
    {
        return setTargetedSchematicBlockState(mc, Blocks.AIR.getDefaultState());
    }

    public static boolean placeSchematicBlock(Minecraft mc)
    {
        ReplacementInfo info = getTargetInfo(mc);

        // The state can be null in 1.13+
        if (info != null && info.stateNew != null)
        {
            BlockPos pos = info.pos.offset(info.side);
            WorldSchematic world = SchematicWorldHandler.getSchematicWorld();

            if (DataManager.getRenderLayerRange().isPositionWithinRange(pos) &&
                world != null && world.isAirBlock(pos))
            {
                return setTargetedSchematicBlockState(pos, info.stateNew);
            }
        }

        return true;
    }

    public static boolean replaceSchematicBlocksInDirection(Minecraft mc)
    {
        ReplacementInfo info = getTargetInfo(mc);

        // The state can be null in 1.13+
        if (info != null && info.stateNew != null)
        {
            EnumFacing playerFacingH = mc.player.getHorizontalFacing();
            EnumFacing direction = fi.dy.masa.malilib.util.PositionUtils.getTargetedDirection(info.side, playerFacingH, info.pos, info.hitVec);

            // Center region
            if (direction == info.side)
            {
                direction = direction.getOpposite();
            }

            BlockPos posEnd = getReplacementBoxEndPos(info.pos, direction);
            return setSchematicBlockStates(info.pos, posEnd, info.stateNew);
        }

        return false;
    }

    public static boolean replaceAllIdenticalSchematicBlocks(Minecraft mc)
    {
        ReplacementInfo info = getTargetInfo(mc);

        // The state can be null in 1.13+
        if (info != null && info.stateNew != null)
        {
            return setAllIdenticalSchematicBlockStates(info.pos, info.stateOriginal, info.stateNew);
        }

        return false;
    }

    public static boolean breakSchematicBlocks(Minecraft mc)
    {
        RayTraceWrapper wrapper = RayTraceUtils.getSchematicWorldTraceWrapperIfClosest(mc.world, mc.player, 20);

        if (wrapper != null)
        {
            RayTraceResult trace = wrapper.getRayTraceResult();
            BlockPos pos = trace.getBlockPos();
            EnumFacing playerFacingH = mc.player.getHorizontalFacing();
            EnumFacing direction = fi.dy.masa.malilib.util.PositionUtils.getTargetedDirection(trace.sideHit, playerFacingH, pos, trace.hitVec);

            // Center region
            if (direction == trace.sideHit)
            {
                direction = direction.getOpposite();
            }

            BlockPos posEnd = getReplacementBoxEndPos(pos, direction);

            return setSchematicBlockStates(pos, posEnd, Blocks.AIR.getDefaultState());
        }

        return false;
    }

    public static boolean breakAllIdenticalSchematicBlocks(Minecraft mc)
    {
        RayTraceWrapper wrapper = RayTraceUtils.getSchematicWorldTraceWrapperIfClosest(mc.world, mc.player, 20);

        // The state can be null in 1.13+
        if (wrapper != null)
        {
            RayTraceResult trace = wrapper.getRayTraceResult();
            BlockPos pos = trace.getBlockPos();
            IBlockState stateOriginal = SchematicWorldHandler.getSchematicWorld().getBlockState(pos);

            return setAllIdenticalSchematicBlockStates(pos, stateOriginal, Blocks.AIR.getDefaultState());
        }

        return false;
    }

    public static boolean placeSchematicBlocksInDirection(Minecraft mc)
    {
        ReplacementInfo info = getTargetInfo(mc);

        // The state can be null in 1.13+
        if (info != null && info.stateNew != null)
        {
            EnumFacing playerFacingH = mc.player.getHorizontalFacing();
            EnumFacing direction = fi.dy.masa.malilib.util.PositionUtils.getTargetedDirection(info.side, playerFacingH, info.pos, info.hitVec);
            BlockPos posStart = info.pos.offset(info.side); // offset to the adjacent air block

            if (SchematicWorldHandler.getSchematicWorld().getBlockState(posStart).getMaterial() == Material.AIR)
            {
                BlockPos posEnd = getReplacementBoxEndPos(posStart, direction);
                return setSchematicBlockStates(posStart, posEnd, info.stateNew);
            }
        }

        return false;
    }

    public static boolean fillAirWithBlocks(Minecraft mc)
    {
        ReplacementInfo info = getTargetInfo(mc);

        // The state can be null in 1.13+
        if (info != null && info.stateNew != null)
        {
            BlockPos posStart = info.pos.offset(info.side); // offset to the adjacent air block

            if (SchematicWorldHandler.getSchematicWorld().getBlockState(posStart).getMaterial() == Material.AIR)
            {
                return setAllIdenticalSchematicBlockStates(posStart, Blocks.AIR.getDefaultState(), info.stateNew);
            }
        }

        return false;
    }

    @Nullable
    private static ReplacementInfo getTargetInfo(Minecraft mc)
    {
        ItemStack stack = mc.player.getHeldItemMainhand();

        if ((stack.isEmpty() == false && (stack.getItem() instanceof ItemBlock || stack.getItem() instanceof ItemBlockSpecial)) ||
            (stack.isEmpty() && ToolMode.REBUILD.getPrimaryBlock() != null))
        {
            WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
            RayTraceWrapper traceWrapper = RayTraceUtils.getGenericTrace(mc.world, mc.player, 20, true);

            if (world != null && traceWrapper != null &&
                traceWrapper.getHitType() == RayTraceWrapper.HitType.SCHEMATIC_BLOCK)
            {
                RayTraceResult trace = traceWrapper.getRayTraceResult();
                EnumFacing side = trace.sideHit;
                Vec3d hitVec = trace.hitVec;
                int meta = stack.getItem().getMetadata(stack.getMetadata());
                BlockPos pos = trace.getBlockPos();
                IBlockState stateOriginal = world.getBlockState(pos);
                IBlockState stateNew = Blocks.AIR.getDefaultState();

                if (stack.getItem() instanceof ItemBlock)
                {
                    stateNew = ((ItemBlock) stack.getItem()).getBlock().getStateForPlacement(world, pos.offset(side),
                                    side, (float) hitVec.x, (float) hitVec.y, (float) hitVec.z, meta, mc.player, EnumHand.MAIN_HAND);
                }
                else if (stack.getItem() instanceof ItemBlockSpecial)
                {
                    stateNew = ((IMixinItemBlockSpecial) stack.getItem()).getBlock().getStateForPlacement(world, pos.offset(side),
                                    side, (float) hitVec.x, (float) hitVec.y, (float) hitVec.z, 0, mc.player, EnumHand.MAIN_HAND);
                }
                else if (ToolMode.REBUILD.getPrimaryBlock() != null)
                {
                    stateNew = ToolMode.REBUILD.getPrimaryBlock();
                }

                return new ReplacementInfo(pos, side, hitVec, stateOriginal, stateNew);
            }
        }

        return null;
    }

    private static BlockPos getReplacementBoxEndPos(BlockPos startPos, EnumFacing direction)
    {
        return getReplacementBoxEndPos(startPos, direction, 10000);
    }

    private static BlockPos getReplacementBoxEndPos(BlockPos startPos, EnumFacing direction, int maxBlocks)
    {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        LayerRange range = DataManager.getRenderLayerRange();
        IBlockState stateStart = world.getBlockState(startPos);
        BlockPos.MutableBlockPos posMutable = new BlockPos.MutableBlockPos(startPos);

        while (maxBlocks-- > 0)
        {
            posMutable.move(direction);

            if (range.isPositionWithinRange(posMutable) == false ||
                world.getChunkProvider().isChunkGeneratedAt(posMutable.getX() >> 4, posMutable.getZ() >> 4) == false ||
                world.getBlockState(posMutable) != stateStart)
            {
                posMutable.move(direction.getOpposite());
                break;
            }
        }

        return posMutable.toImmutable();
    }

    public static boolean setTargetedSchematicBlockState(Minecraft mc, IBlockState state)
    {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        RayTraceWrapper traceWrapper = RayTraceUtils.getGenericTrace(mc.world, mc.player, 20, true);

        if (world != null && traceWrapper != null && traceWrapper.getHitType() == RayTraceWrapper.HitType.SCHEMATIC_BLOCK)
        {
            RayTraceResult trace = traceWrapper.getRayTraceResult();
            BlockPos pos = trace.getBlockPos();
            return setTargetedSchematicBlockState(pos, state);
        }

        return false;
    }

    private static boolean setTargetedSchematicBlockState(BlockPos pos, IBlockState state)
    {
        if (pos != null)
        {
            SubChunkPos cpos = new SubChunkPos(pos);
            List<PlacementPart> list = DataManager.getSchematicPlacementManager().getAllPlacementsTouchingSubChunk(cpos);

            if (list.isEmpty() == false)
            {
                for (PlacementPart part : list)
                {
                    if (part.getBox().containsPos(pos))
                    {
                        SchematicPlacement placement = part.getPlacement();
                        LitematicaSchematic schematic = placement.getSchematic();
                        String regionName = part.getSubRegionName();
                        LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
                        BlockPos posSchematic = getSchematicContainerPositionFromWorldPosition(pos, schematic,
                                regionName, placement, placement.getRelativeSubRegionPlacement(regionName), container);

                        if (posSchematic != null)
                        {
                            state = getUntransformedBlockState(state, placement, regionName);

                            IBlockState stateOriginal = container.get(posSchematic.getX(), posSchematic.getY(), posSchematic.getZ());

                            SchematicMetadata metadata = schematic.getMetadata();
                            int totalBlocks = metadata.getTotalBlocks();
                            int increment = 0;

                            if (stateOriginal.getBlock() != Blocks.AIR)
                            {
                                increment = state.getBlock() != Blocks.AIR ? 0 : -1;
                            }
                            else
                            {
                                increment = state.getBlock() != Blocks.AIR ? 1 : 0;
                            }

                            totalBlocks += increment;

                            container.set(posSchematic.getX(), posSchematic.getY(), posSchematic.getZ(), state);

                            metadata.setTotalBlocks(totalBlocks);
                            metadata.setTimeModifiedToNow();
                            metadata.setModifiedSinceSaved();

                            DataManager.getSchematicPlacementManager().markChunkForRebuild(new ChunkPos(cpos.getX(), cpos.getZ()));

                            return true;
                        }

                        return false;
                    }
                }
            }
        }

        return false;
    }

    private static boolean setSchematicBlockStates(BlockPos posStart, BlockPos posEnd, IBlockState state)
    {
        if (posStart != null && posEnd != null)
        {
            SubChunkPos cpos = new SubChunkPos(posStart);
            List<PlacementPart> list = DataManager.getSchematicPlacementManager().getAllPlacementsTouchingSubChunk(cpos);

            if (list.isEmpty() == false)
            {
                for (PlacementPart part : list)
                {
                    if (part.getBox().containsPos(posStart))
                    {
                        String regionName = part.getSubRegionName();
                        SchematicPlacement schematicPlacement = part.getPlacement();
                        SubRegionPlacement placement = schematicPlacement.getRelativeSubRegionPlacement(regionName);
                        LitematicaSchematic schematic = schematicPlacement.getSchematic();
                        LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
                        BlockPos posStartSchematic = getSchematicContainerPositionFromWorldPosition(posStart, schematic,
                                regionName, schematicPlacement, placement, container);
                        BlockPos posEndSchematic = getSchematicContainerPositionFromWorldPosition(posEnd, schematic,
                                regionName, schematicPlacement, placement, container);

                        if (posStartSchematic != null && posEndSchematic != null)
                        {
                            BlockPos posMin = PositionUtils.getMinCorner(posStartSchematic, posEndSchematic);
                            BlockPos posMax = PositionUtils.getMaxCorner(posStartSchematic, posEndSchematic);
                            final int minX = Math.max(posMin.getX(), 0);
                            final int minY = Math.max(posMin.getY(), 0);
                            final int minZ = Math.max(posMin.getZ(), 0);
                            final int maxX = Math.min(posMax.getX(), container.getSize().getX() - 1);
                            final int maxY = Math.min(posMax.getY(), container.getSize().getY() - 1);
                            final int maxZ = Math.min(posMax.getZ(), container.getSize().getZ() - 1);
                            int totalBlocks = schematic.getMetadata().getTotalBlocks();
                            int increment = 0;

                            state = getUntransformedBlockState(state, schematicPlacement, regionName);

                            for (int y = minY; y <= maxY; ++y)
                            {
                                for (int z = minZ; z <= maxZ; ++z)
                                {
                                    for (int x = minX; x <= maxX; ++x)
                                    {
                                        IBlockState stateOriginal = container.get(x, y, z);

                                        if (stateOriginal.getBlock() != Blocks.AIR)
                                        {
                                            increment = state.getBlock() != Blocks.AIR ? 0 : -1;
                                        }
                                        else
                                        {
                                            increment = state.getBlock() != Blocks.AIR ? 1 : 0;
                                        }

                                        totalBlocks += increment;

                                        container.set(x, y, z, state);
                                    }
                                }
                            }

                            SchematicMetadata metadata = schematic.getMetadata();
                            metadata.setTotalBlocks(totalBlocks);
                            metadata.setTimeModifiedToNow();
                            metadata.setModifiedSinceSaved();

                            DataManager.getSchematicPlacementManager().markAllPlacementsOfSchematicForRebuild(schematic);

                            return true;
                        }

                        return false;
                    }
                }
            }
        }

        return false;
    }

    private static boolean setAllIdenticalSchematicBlockStates(BlockPos posStart, IBlockState stateOriginal, IBlockState stateNew)
    {
        if (posStart != null)
        {
            SubChunkPos cpos = new SubChunkPos(posStart);
            SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
            List<PlacementPart> list = manager.getAllPlacementsTouchingSubChunk(cpos);

            if (list.isEmpty() == false)
            {
                for (PlacementPart part : list)
                {
                    if (part.getBox().containsPos(posStart))
                    {
                        if (replaceAllIdenticalBlocks(manager, part, stateOriginal, stateNew))
                        {
                            manager.markAllPlacementsOfSchematicForRebuild(part.getPlacement().getSchematic());
                            return true;
                        }

                        return false;
                    }
                }
            }
        }

        return false;
    }

    private static boolean replaceAllIdenticalBlocks(SchematicPlacementManager manager, PlacementPart part,
            IBlockState stateOriginalIn, IBlockState stateNewIn)
    {
        SchematicPlacement schematicPlacement = part.getPlacement();
        LitematicaSchematic schematic = schematicPlacement.getSchematic();
        String selected = schematicPlacement.getSelectedSubRegionName();
        List<String> regions = new ArrayList<>();

        // Some sub-region selected, only replace in that region
        if (selected != null)
        {
            regions.add(selected);
        }
        // The entire placement is selected, replace in all sub-regions
        else if (manager.getSelectedSchematicPlacement() == schematicPlacement)
        {
            regions.addAll(schematicPlacement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED).keySet());
        }
        // Nothing from the targeted placement is selected, don't replace anything
        else
        {
            InfoUtils.showInGameMessage(MessageType.WARNING, 20000, "litematica.message.warn.schematic_rebuild_placement_not_selected");
            return false;
        }

        LayerRange range = DataManager.getRenderLayerRange();

        int totalBlocks = schematic.getMetadata().getTotalBlocks();
        int increment = 0;

        if (stateOriginalIn.getBlock() != Blocks.AIR)
        {
            increment = stateNewIn.getBlock() != Blocks.AIR ? 0 : -1;
        }
        else
        {
            increment = stateNewIn.getBlock() != Blocks.AIR ? 1 : 0;
        }

        for (String regionName : regions)
        {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
            SubRegionPlacement placement = schematicPlacement.getRelativeSubRegionPlacement(regionName);
            BlockPos regionSize = schematic.getAreaSize(regionName);

            if (container == null || placement == null || regionSize == null)
            {
                continue;
            }

            Pair<Vec3i, Vec3i> pair = getLayerRangeClampedSubRegion(range, schematicPlacement, placement, regionSize);

            if (pair == null)
            {
                return false;
            }

            Vec3i containerStart = pair.getLeft();
            Vec3i containerEnd = pair.getRight();
            Vec3i size = container.getSize();

            final int startX = containerStart.getX();
            final int startY = containerStart.getY();
            final int startZ = containerStart.getZ();
            final int endX = containerEnd.getX();
            final int endY = containerEnd.getY();
            final int endZ = containerEnd.getZ();

            //System.out.printf("DEBUG == region: %s, sx: %d, sy: %s, sz: %d, ex: %d, ey: %d, ez: %d - size x: %d y: %d z: %d =============\n",
            //        regionName, startX, startY, startZ, endX, endY, endZ, container.getSize().getX(), container.getSize().getY(), container.getSize().getZ());

            if (startX < 0 || startY < 0 || startZ < 0 ||
                endX >= size.getX() ||
                endY >= size.getY() ||
                endZ >= size.getZ())
            {
                System.out.printf("OUT OF BOUNDS == region: %s, sx: %d, sy: %s, sz: %d, ex: %d, ey: %d, ez: %d - size x: %d y: %d z: %d =============\n",
                        regionName, startX, startY, startZ, endX, endY, endZ, size.getX(), size.getY(), size.getZ());
                return false;
            }

            //System.out.printf("DEBUG == region: %s, sx: %d, sy: %s, sz: %d, ex: %d, ey: %d, ez: %d - size x: %d y: %d z: %d =============\n",
            //        regionName, startX, startY, startZ, endX, endY, endZ, size.getX(), size.getY(), size.getZ());

            IBlockState stateOriginal = getUntransformedBlockState(stateOriginalIn, schematicPlacement, regionName);
            IBlockState stateNew = getUntransformedBlockState(stateNewIn, schematicPlacement, regionName);

            for (int y = startY; y <= endY; ++y)
            {
                for (int z = startZ; z <= endZ; ++z)
                {
                    for (int x = startX; x <= endX; ++x)
                    {
                        if (container.get(x, y, z) == stateOriginal)
                        {
                            container.set(x, y, z, stateNew);
                            totalBlocks += increment;
                        }
                    }
                }
            }
        }

        SchematicMetadata metadata = schematic.getMetadata();
        metadata.setTotalBlocks(totalBlocks);
        metadata.setTimeModifiedToNow();
        metadata.setModifiedSinceSaved();

        return true;
    }

    public static void moveCurrentlySelectedWorldRegionToLookingDirection(int amount, EntityPlayer player, Minecraft mc)
    {
        SelectionManager sm = DataManager.getSelectionManager();
        AreaSelection area = sm.getCurrentSelection();

        if (area != null && area.getAllSubRegionBoxes().size() > 0)
        {
            BlockPos pos = area.getEffectiveOrigin().offset(EntityUtils.getClosestLookingDirection(player), amount);
            moveCurrentlySelectedWorldRegionTo(pos, mc);
        }
    }

    public static void moveCurrentlySelectedWorldRegionTo(BlockPos pos, Minecraft mc)
    {
        if (mc.player == null || mc.player.capabilities.isCreativeMode == false)
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.error.generic.creative_mode_only");
            return;
        }

        TaskScheduler scheduler = TaskScheduler.getServerInstanceIfExistsOrClient();
        long currentTime = System.currentTimeMillis();

        // Add a delay from the previous move operation, to allow time for
        // server -> client chunk/block syncing, otherwise a subsequent move
        // might wipe the area before the new blocks have arrived on the
        // client and thus the new move schematic would just be air.
        if ((currentTime - areaMovedTime) < 400 ||
            scheduler.hasTask(TaskSaveSchematic.class) ||
            scheduler.hasTask(TaskDeleteArea.class) ||
            scheduler.hasTask(TaskPasteSchematicSetblock.class) ||
            scheduler.hasTask(TaskPasteSchematicDirect.class))
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.message.error.move.pending_tasks");
            return;
        }

        SelectionManager sm = DataManager.getSelectionManager();
        AreaSelection area = sm.getCurrentSelection();

        if (area != null && area.getAllSubRegionBoxes().size() > 0)
        {
            final LayerRange range = DataManager.getRenderLayerRange().copy();
            LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, "");
            TaskSaveSchematic taskSave = new TaskSaveSchematic(schematic, area, true);
            taskSave.disableCompletionMessage();
            areaMovedTime = System.currentTimeMillis();

            taskSave.setCompletionListener(() ->
            {
                SchematicPlacement placement = SchematicPlacement.createFor(schematic, pos, "-", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);

                TaskDeleteArea taskDelete = new TaskDeleteArea(area.getAllSubRegionBoxes(), true);
                taskDelete.disableCompletionMessage();
                areaMovedTime = System.currentTimeMillis();

                taskDelete.setCompletionListener(() ->
                {
                    TaskBase taskPaste;

                    if (mc.isSingleplayer())
                    {
                        taskPaste = new TaskPasteSchematicDirect(placement, range);
                    }
                    else
                    {
                        taskPaste = new TaskPasteSchematicSetblock(placement, range, false);
                    }

                    taskPaste.disableCompletionMessage();
                    areaMovedTime = System.currentTimeMillis();

                    taskPaste.setCompletionListener(() ->
                    {
                        SchematicHolder.getInstance().removeSchematic(schematic);
                        area.moveEntireSelectionTo(pos, false);
                        areaMovedTime = System.currentTimeMillis();
                    });

                    scheduler.scheduleTask(taskPaste, 1);
                });

                scheduler.scheduleTask(taskDelete, 1);
            });

            scheduler.scheduleTask(taskSave, 1);
        }
        else
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.message.error.no_area_selected");
        }
    }

    public static void cloneSelectionArea(Minecraft mc)
    {
        SelectionManager sm = DataManager.getSelectionManager();
        AreaSelection area = sm.getCurrentSelection();

        if (area != null && area.getAllSubRegionBoxes().size() > 0)
        {
            LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, mc.player.getName());
            TaskSaveSchematic taskSave = new TaskSaveSchematic(schematic, area, true);
            taskSave.disableCompletionMessage();

            taskSave.setCompletionListener(() ->
            {
                SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
                String name = schematic.getMetadata().getName();
                BlockPos origin = RayTraceUtils.getTargetedPosition(mc.world, mc.player, 6, false);

                if (origin == null)
                {
                    origin = new BlockPos(mc.player);
                }

                SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, name, true, true);

                manager.addSchematicPlacement(placement, false);
                manager.setSelectedSchematicPlacement(placement);

                if (mc.player.capabilities.isCreativeMode)
                {
                    DataManager.setToolMode(ToolMode.PASTE_SCHEMATIC);
                }
            });

            TaskScheduler.getServerInstanceIfExistsOrClient().scheduleTask(taskSave, 10);
        }
        else
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.message.error.no_area_selected");
        }
    }

    @Nullable
    public static BlockPos getSchematicContainerPositionFromWorldPosition(BlockPos worldPos, LitematicaSchematic schematic, String regionName,
            SchematicPlacement schematicPlacement, SubRegionPlacement regionPlacement, LitematicaBlockStateContainer container)
    {
        BlockPos boxMinRel = getReverseTransformedWorldPosition(worldPos, schematic, schematicPlacement, regionPlacement, schematic.getAreaSize(regionName));

        if (boxMinRel == null)
        {
            return null;
        }

        final int startX = boxMinRel.getX();
        final int startY = boxMinRel.getY();
        final int startZ = boxMinRel.getZ();
        Vec3i size = container.getSize();

        /*
        if (startX < 0 || startY < 0 || startZ < 0 || startX >= size.getX() || startY >= size.getY() || startZ >= size.getZ())
        {
            System.out.printf("DEBUG ============= OUT OF BOUNDS - region: %s, startX: %d, startY %s, startZ: %d - size x: %d y: %s z: %d =============\n",
                    regionName, startX, startY, startZ, size.getX(), size.getY(), size.getZ());
            return null;
        }

        return boxMinRel;
        */

        return new BlockPos(MathHelper.clamp(startX, 0, size.getX() - 1),
                            MathHelper.clamp(startY, 0, size.getY() - 1),
                            MathHelper.clamp(startZ, 0, size.getZ() - 1));
    }

    @Nullable
    private static BlockPos getReverseTransformedWorldPosition(BlockPos worldPos, LitematicaSchematic schematic,
            SchematicPlacement schematicPlacement, SubRegionPlacement regionPlacement, Vec3i regionSize)
    {
        BlockPos origin = schematicPlacement.getOrigin();
        BlockPos regionPos = regionPlacement.getPos();

        // These are the untransformed relative positions
        BlockPos posEndRel = PositionUtils.getRelativeEndPositionFromAreaSize(regionSize).add(regionPos);
        BlockPos posMinRel = PositionUtils.getMinCorner(regionPos, posEndRel);

        // The transformed sub-region origin position
        BlockPos regionPosTransformed = PositionUtils.getTransformedBlockPos(regionPos, schematicPlacement.getMirror(), schematicPlacement.getRotation());

        // The relative offset of the affected region's corners, to the sub-region's origin corner
        BlockPos relPos = new BlockPos(worldPos.getX() - origin.getX() - regionPosTransformed.getX(),
                                       worldPos.getY() - origin.getY() - regionPosTransformed.getY(),
                                       worldPos.getZ() - origin.getZ() - regionPosTransformed.getZ());

        // Reverse transform that relative offset, to get the untransformed orientation's offsets
        relPos = PositionUtils.getReverseTransformedBlockPos(relPos, regionPlacement.getMirror(), regionPlacement.getRotation());

        relPos = PositionUtils.getReverseTransformedBlockPos(relPos, schematicPlacement.getMirror(), schematicPlacement.getRotation());

        // Get the offset relative to the sub-region's minimum corner, instead of the origin corner (which can be at any corner)
        relPos = relPos.subtract(posMinRel.subtract(regionPos));

        return relPos;
    }

    @Nullable
    public static Pair<Vec3i, Vec3i> getLayerRangeClampedSubRegion(LayerRange range,
            SchematicPlacement schematicPlacement, SubRegionPlacement placement, Vec3i regionSize)
    {
        int minX = range.getClampedValue(LayerRange.getWorldMinValueForAxis(EnumFacing.Axis.X), EnumFacing.Axis.X);
        int minY = range.getClampedValue(LayerRange.getWorldMinValueForAxis(EnumFacing.Axis.Y), EnumFacing.Axis.Y);
        int minZ = range.getClampedValue(LayerRange.getWorldMinValueForAxis(EnumFacing.Axis.Z), EnumFacing.Axis.Z);
        int maxX = range.getClampedValue(LayerRange.getWorldMaxValueForAxis(EnumFacing.Axis.X), EnumFacing.Axis.X);
        int maxY = range.getClampedValue(LayerRange.getWorldMaxValueForAxis(EnumFacing.Axis.Y), EnumFacing.Axis.Y);
        int maxZ = range.getClampedValue(LayerRange.getWorldMaxValueForAxis(EnumFacing.Axis.Z), EnumFacing.Axis.Z);

        BlockPos posMinRange = new BlockPos(minX, minY, minZ);
        BlockPos posMaxRange = new BlockPos(maxX, maxY, maxZ);

        LitematicaSchematic schematic = schematicPlacement.getSchematic();
        BlockPos pos1 = getReverseTransformedWorldPosition(posMinRange, schematic, schematicPlacement, placement, regionSize);
        BlockPos pos2 = getReverseTransformedWorldPosition(posMaxRange, schematic, schematicPlacement, placement, regionSize);

        if (pos1 == null || pos2 == null)
        {
            return null;
        }

        BlockPos posMinReversed = PositionUtils.getMinCorner(pos1, pos2);
        BlockPos posMaxReversed = PositionUtils.getMaxCorner(pos1, pos2);

        final int startX = Math.max(posMinReversed.getX(), 0);
        final int startY = Math.max(posMinReversed.getY(), 0);
        final int startZ = Math.max(posMinReversed.getZ(), 0);
        final int endX = Math.min(posMaxReversed.getX(), Math.abs(regionSize.getX()) - 1);
        final int endY = Math.min(posMaxReversed.getY(), Math.abs(regionSize.getY()) - 1);
        final int endZ = Math.min(posMaxReversed.getZ(), Math.abs(regionSize.getZ()) - 1);

        return Pair.of(new Vec3i(startX, startY, startZ), new Vec3i(endX, endY, endZ));
    }

    public static IBlockState getUntransformedBlockState(IBlockState state, SchematicPlacement schematicPlacement, String subRegionName)
    {
        SubRegionPlacement placement = schematicPlacement.getRelativeSubRegionPlacement(subRegionName);

        if (placement != null)
        {
            final Rotation rotationCombined = PositionUtils.getReverseRotation(schematicPlacement.getRotation().add(placement.getRotation()));
            final Mirror mirrorMain = schematicPlacement.getMirror();
            Mirror mirrorSub = placement.getMirror();

            if (mirrorSub != Mirror.NONE &&
                (schematicPlacement.getRotation() == Rotation.CLOCKWISE_90 ||
                 schematicPlacement.getRotation() == Rotation.COUNTERCLOCKWISE_90))
            {
                mirrorSub = mirrorSub == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
            }

            if (rotationCombined != Rotation.NONE)
            {
                state = state.withRotation(rotationCombined);
            }

            if (mirrorSub != Mirror.NONE)
            {
                state = state.withMirror(mirrorSub);
            }

            if (mirrorMain != Mirror.NONE)
            {
                state = state.withMirror(mirrorMain);
            }
        }

        return state;
    }

    private static class ReplacementInfo
    {
        public final BlockPos pos;
        public final EnumFacing side;
        public final Vec3d hitVec;
        public final IBlockState stateOriginal;
        public final IBlockState stateNew;

        public ReplacementInfo(BlockPos pos, EnumFacing side, Vec3d hitVec, IBlockState stateOriginal, IBlockState stateNew)
        {
            this.pos = pos;
            this.side = side;
            this.hitVec = hitVec;
            this.stateOriginal = stateOriginal;
            this.stateNew = stateNew;
        }
    }

    public static class SchematicVersionCreator implements IStringConsumerFeedback
    {
        @Override
        public boolean setString(String string)
        {
            return DataManager.getSchematicProjectsManager().commitNewVersion(string);
        }
    }
}
