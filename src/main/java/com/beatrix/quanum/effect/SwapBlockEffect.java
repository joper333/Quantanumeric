package com.beatrix.quanum.effect;

import com.hypixel.hytale.builtin.triggervolumes.TriggerVolumesPlugin;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEventType;
import com.hypixel.hytale.builtin.triggervolumes.effect.builtin.TaggedVolumeEffectUtil;
import com.hypixel.hytale.builtin.triggervolumes.effect.builtin.TaggedVolumeEffectUtil.Center;
import com.hypixel.hytale.builtin.triggervolumes.manager.TriggerVolumeManager;
import com.hypixel.hytale.builtin.triggervolumes.manager.VolumeEntry;
import com.hypixel.hytale.builtin.triggervolumes.shape.TriggerVolumeShape;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.prefab.selection.mask.BlockFilter;
import com.hypixel.hytale.server.core.prefab.selection.mask.BlockFilter.BlocksAndFluids;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

public class SwapBlockEffect extends TriggerEffect {

   @Nonnull
   private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

   @Nonnull
   private static final String[] ALLOWED_BLOCKS = {
           //hardcoded cause like, who else gonna be using this, sorry about that, if you are reading this
           "Red_Qbit",
           "Purple_Qbit",
           "Green_Qbit",
           "Orange_Qbit",
           "Blue_Qbit",
           "Yellow_Qbit",

   };

   @Nullable
   private static volatile IntSet allowedBlocks;

   @Nonnull
   public static final BuilderCodec<SwapBlockEffect> CODEC = BuilderCodec.builder(SwapBlockEffect.class, SwapBlockEffect::new, BASE_CODEC) // BASE_CODEC VERY IMPORTANT!!!!
      .append(new KeyedCodec<>("MatchKey", Codec.STRING, false), (o, v) -> o.matchKey = v != null ? v : "", o -> o.matchKey).add()
      .append(new KeyedCodec<>("MatchValue", Codec.STRING, false), (o, v) -> o.matchValue = v, o -> o.matchValue).add()
      .append(new KeyedCodec<>("Radius", Codec.DOUBLE, false), (o, v) -> o.radius = v != null ? v : 50.0, o -> o.radius).add()
      .append(new KeyedCodec<>("Center", new EnumCodec<>(Center.class), false), (o, v) -> o.center = v != null ? v : Center.VOLUME, o -> o.center)
      .add()
      .build();

   @Nonnull
   private String matchKey = "";
   @Nullable
   private String matchValue;
   private double radius = 50.0;
   @Nonnull
   private Center center = Center.VOLUME;

   @Override
   public void execute(@Nonnull TriggerContext context) {

      World world = context.getStore().getExternalData().getWorld();
      if (world == null) {
         return;
      }

      IntSet allowed = allowedBlocks();
      if (allowed.isEmpty()) {
         LOGGER.atInfo().log("[SwapBlock] aborting: no whitelisted blocks resolved from ALLOWED_BLOCKS (check the block ids)");
         return;
      }

      String tagFilter = TaggedVolumeEffectUtil.composeTagFilter(this.matchKey, this.matchValue);

      Vector3d min = new Vector3d();
      Vector3d max = new Vector3d();
      Vector3d blockCenter = new Vector3d();
      List<Cell> swappable = new ArrayList<>();

      List<VolumeEntry> targets = TaggedVolumeEffectUtil.collectTargets(context, tagFilter, this.radius, this.center);
      LOGGER.atInfo().log("[SwapBlock] collectTargets returned %d volume(s)", targets.size());

      for (VolumeEntry volume : targets) {
         Cell cell = this.findSingleAllowedBlock(world, volume, allowed, min, max, blockCenter);
         if (cell != null) {
            swappable.add(cell);
         }
      }

      LOGGER.atInfo().log("[SwapBlock] %d volume(s) hold a single whitelisted block (need exactly 2)", swappable.size());

      if (swappable.size() != 2) {
         if (swappable.size() > 2) {
            LOGGER.atInfo().log("[SwapBlock] found %d swappable volumes, expected exactly 2; doing nothing", swappable.size());
         }

         return;
      }

      Cell a = swappable.get(0);
      Cell b = swappable.get(1);
      BlockType assetA = BlockType.getAssetMap().getAsset(a.blockId());
      BlockType assetB = BlockType.getAssetMap().getAsset(b.blockId());
      if (assetA == null || assetB == null) {
         return;
      }

      // Read both rotations before writing so the blocks (and their rotation) travel with the swap.
      int rotationA = a.chunk().getRotationIndex(a.x(), a.y(), a.z());
      int rotationB = b.chunk().getRotationIndex(b.x(), b.y(), b.z());

      a.chunk().setBlock(a.x(), a.y(), a.z(), b.blockId(), assetB, rotationB, 0, 256);
      b.chunk().setBlock(b.x(), b.y(), b.z(), a.blockId(), assetA, rotationA, 0, 256);

      // Writing straight to the chunk doesn't raise the block events the trigger system listens for,
      // so report each swapped cell as a break of the old block plus a place of the new block. This
      // makes volumes with BLOCK_BROKEN / BLOCK_PLACED conditions react to the swap.
      String idA = assetA.getId();
      String idB = assetB.getId();
      if (!idA.equals(idB)) {
         this.emitBlockChange(context, a, idA, idB); // cell A: broke A, placed B
         this.emitBlockChange(context, b, idB, idA); // cell B: broke B, placed A
      }

   }

   // block breaking and placing trigger worky thinhy
   private void emitBlockChange(@Nonnull TriggerContext context, @Nonnull Cell cell, @Nonnull String brokenBlockId, @Nonnull String placedBlockId) {
      TriggerVolumeManager manager = context.getStore().getResource(TriggerVolumesPlugin.get().getManagerResourceType());
      if (manager == null) {
         return;
      }

      Ref<EntityStore> actorRef = context.getEntityRef();
      UUIDComponent uuidComponent = context.getStore().getComponent(actorRef, UUIDComponent.getComponentType());
      UUID actorUuid = uuidComponent != null ? uuidComponent.getUuid() : new UUID(0L, 0L);

      manager.enqueueBlockEvent(
         TriggerEventType.BLOCK_BROKEN, actorRef, actorUuid, new Vector3d(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5), brokenBlockId
      );
      manager.enqueueBlockEvent(
         TriggerEventType.BLOCK_PLACED, actorRef, actorUuid, new Vector3d(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5), placedBlockId
      );
   }

   @Nullable
   private Cell findSingleAllowedBlock(
      @Nonnull World world, @Nonnull VolumeEntry volume, @Nonnull IntSet allowed, @Nonnull Vector3d min, @Nonnull Vector3d max, @Nonnull Vector3d blockCenter
   ) {
      TriggerVolumeShape shape = volume.getShape();
      Vector3d origin = volume.getPosition();
      shape.getWorldAABB(origin, min, max);

      int minBlockX = MathUtil.floor(min.x());
      int minBlockY = MathUtil.floor(min.y());
      int minBlockZ = MathUtil.floor(min.z());
      int maxBlockX = MathUtil.floor(max.x());
      int maxBlockY = MathUtil.floor(max.y());
      int maxBlockZ = MathUtil.floor(max.z());

      Cell found = null;

      for (int blockX = minBlockX; blockX <= maxBlockX; blockX++) {
         for (int blockY = minBlockY; blockY <= maxBlockY; blockY++) {
            for (int blockZ = minBlockZ; blockZ <= maxBlockZ; blockZ++) {
               blockCenter.set(blockX + 0.5, blockY + 0.5, blockZ + 0.5);
               if (!shape.contains(origin, blockCenter)) {
                  continue;
               }

               WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(blockX, blockZ));
               if (chunk == null || chunk.getFiller(blockX, blockY, blockZ) != 0) {
                  continue;
               }

               int blockId = chunk.getBlock(blockX, blockY, blockZ);
               if (blockId == 0) {
                  continue; // empty cell, not a block
               }

               if (found != null) {
                  LOGGER.atInfo().log("[SwapBlock] volume at %s contains more than one block; doing nothing", origin);
                  return null;
               }

               found = new Cell(chunk, blockX, blockY, blockZ, blockId);
            }
         }
      }

      if (found == null) {
         return null;
      }

      if (!allowed.contains(found.blockId())) {
         LOGGER.atInfo().log("[SwapBlock] volume at %s holds block %d which is not whitelisted; skipping", origin, found.blockId());
         return null;
      }

      return found;
   }

   @Nonnull
   private static IntSet allowedBlocks() {
      IntSet set = allowedBlocks;
      if (set != null) {
         return set;
      }

      synchronized (SwapBlockEffect.class) {
         set = allowedBlocks;
         if (set != null) {
            return set;
         }

         set = buildAllowedBlocks();
         // Only cache once the block assets actually resolved, so an early call before assets are loaded doesn't permanently cache an empty set.
         if (!set.isEmpty()) {
            allowedBlocks = set;
         }

         return set;
      }
   }

   @Nonnull
   private static IntSet buildAllowedBlocks() {
      IntOpenHashSet set = new IntOpenHashSet();

      for (String blockType : ALLOWED_BLOCKS) {
         int id = resolveSingleBlock(blockType);
         if (id >= 0) {
            set.add(id);
         }
      }

      return set;
   }

   private static int resolveSingleBlock(@Nonnull String blockType) {
      BlocksAndFluids resolved = BlockFilter.parseBlocksAndFluids(new String[]{blockType});
      if (resolved.hasInvalidBlocks() || resolved.blocks() == null || resolved.blocks().size() != 1) {
         LOGGER.atInfo().log("SwapBlock: '%s' must resolve to exactly one block type", blockType);
         return -1;
      }

      IntIterator iterator = resolved.blocks().iterator();
      return iterator.nextInt();
   }

   /** A located block inside a volume: the chunk it lives in, its coordinates, and its block id. */
   private record Cell(@Nonnull WorldChunk chunk, int x, int y, int z, int blockId) {
   }
}
