package li.cil.oc.common.block

import java.util

import li.cil.oc.common.block.property.PropertyCableConnection
import li.cil.oc.common.capabilities.Capabilities
import li.cil.oc.common.tileentity
import li.cil.oc.util.Color
import li.cil.oc.util.ExtendedWorld._
import net.minecraft.block.AbstractBlock.Properties
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.{Entity, LivingEntity}
import net.minecraft.item.{DyeColor, ItemStack}
import net.minecraft.state.StateContainer
import net.minecraft.tileentity.TileEntity
import net.minecraft.util.Direction
import net.minecraft.util.math.{BlockPos, RayTraceResult}
import net.minecraft.util.math.shapes.ISelectionContext
import net.minecraft.util.math.shapes.VoxelShape
import net.minecraft.util.math.shapes.VoxelShapes
import net.minecraft.world.IBlockReader
import net.minecraft.world.IWorld
import net.minecraft.world.World
import net.minecraft.world.server.ServerWorld
import net.minecraftforge.common.extensions.IForgeBlock

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

class Cable(props: Properties)(protected implicit val tileTag: ClassTag[tileentity.Cable]) extends SimpleBlock(props) with IForgeBlock with traits.CustomDrops[tileentity.Cable] {
  // For Immibis Microblock support.
  val ImmibisMicroblocks_TransformableBlockMarker = null

  // For FMP part coloring.
  var colorMultiplierOverride: Option[Int] = None

  // ----------------------------------------------------------------------- //

  override protected def createBlockStateDefinition(builder: StateContainer.Builder[Block, BlockState]) = {
    builder.add(PropertyCableConnection.DOWN, PropertyCableConnection.UP,
      PropertyCableConnection.NORTH, PropertyCableConnection.SOUTH,
      PropertyCableConnection.WEST, PropertyCableConnection.EAST)
  }

  registerDefaultState(defaultBlockState.
    setValue(PropertyCableConnection.DOWN, PropertyCableConnection.Shape.NONE).
    setValue(PropertyCableConnection.UP, PropertyCableConnection.Shape.NONE).
    setValue(PropertyCableConnection.NORTH, PropertyCableConnection.Shape.NONE).
    setValue(PropertyCableConnection.SOUTH, PropertyCableConnection.Shape.NONE).
    setValue(PropertyCableConnection.WEST, PropertyCableConnection.Shape.NONE).
    setValue(PropertyCableConnection.EAST, PropertyCableConnection.Shape.NONE))

  override def getPickBlock(state: BlockState, target: RayTraceResult, world: IBlockReader, pos: BlockPos, player: PlayerEntity) =
    world.getBlockEntity(pos) match {
      case t: tileentity.Cable => t.createItemStack()
      case _ => createItemStack()
    }

  override def getShape(state: BlockState, world: IBlockReader, pos: BlockPos, ctx: ISelectionContext): VoxelShape = Cable.shape(state)

  override def updateShape(state: BlockState, fromSide: Direction, fromState: BlockState, world: IWorld, pos: BlockPos, fromPos: BlockPos): BlockState =
    Cable.updateState(state, fromSide, fromState, world, pos, fromPos)

  // Connecting to other blocks requires this cable to have a TE set, so wait until next tick and update.
  override def onPlace(state: BlockState, world: World, pos: BlockPos, prevState: BlockState, moved: Boolean): Unit = {
    // Only schedule this update if this cable was newly placed (not if it changed shape).
    if (!prevState.is(this)) {
      world match {
        case srvWorld: ServerWorld if !srvWorld.isClientSide => srvWorld.getBlockTicks.scheduleTick(pos, this, 1)
        case _ =>
      }
    }
  }

  override def tick(state: BlockState, world: ServerWorld, pos: BlockPos, rand: util.Random) {
    val newState = Block.updateFromNeighbourShapes(state, world, pos)
    Block.updateOrDestroy(state, newState, world, pos, 3)
  }

  // ----------------------------------------------------------------------- //

  override def newBlockEntity(world: IBlockReader) = new tileentity.Cable(tileentity.TileEntityTypes.CABLE)

  // ----------------------------------------------------------------------- //

  override protected def doCustomInit(tileEntity: tileentity.Cable, player: LivingEntity, stack: ItemStack): Unit = {
    super.doCustomInit(tileEntity, player, stack)
    if (!tileEntity.world.isClientSide) {
      tileEntity.fromItemStack(stack)
    }
  }

  override protected def doCustomDrops(tileEntity: tileentity.Cable, player: PlayerEntity, willHarvest: Boolean): Unit = {
    super.doCustomDrops(tileEntity, player, willHarvest)
    if (!player.isCreative) {
      Block.popResource(tileEntity.world, tileEntity.getBlockPos, tileEntity.createItemStack())
    }
  }
}

object Cable {
  final val MIN = 0.375
  final val MAX = 1 - MIN

  final val DefaultShape: VoxelShape = VoxelShapes.box(MIN, MIN, MIN, MAX, MAX, MAX)

  final val CachedParts: Array[VoxelShape] = Array(
    VoxelShapes.box( MIN, 0, MIN, MAX, MIN, MAX ), // Down
    VoxelShapes.box( MIN, MAX, MIN, MAX, 1, MAX ), // Up
    VoxelShapes.box( MIN, MIN, 0, MAX, MAX, MIN ), // North
    VoxelShapes.box( MIN, MIN, MAX, MAX, MAX, 1 ), // South
    VoxelShapes.box( 0, MIN, MIN, MIN, MAX, MAX ), // West
    VoxelShapes.box( MAX, MIN, MIN, 1, MAX, MAX )) // East

  final val CachedBounds = {
    // 6 directions = 6 bits = 11111111b >> 2 = 0xFF >> 2
    (0 to 0xFF >> 2).map(mask => {
      Direction.values.foldLeft(DefaultShape)((shape, side) => {
        if (((1 << side.get3DDataValue) & mask) != 0) VoxelShapes.or(shape, CachedParts(side.ordinal()))
        else shape
      })
    }).toArray
  }

  def mask(side: Direction, value: Int = 0) = value | (1 << side.get3DDataValue)

  def shape(state: BlockState): VoxelShape = {
    var result = 0
    for (side <- Direction.values) {
      val sideShape = state.getValue(PropertyCableConnection.BY_DIRECTION.get(side))
      if (sideShape != PropertyCableConnection.Shape.NONE) {
        result = mask(side, result)
      }
    }
    Cable.CachedBounds(result)
  }

  def updateState(state: BlockState, fromSide: Direction, fromState: BlockState, world: IBlockReader, pos: BlockPos, fromPos: BlockPos): BlockState = {
    val prop = PropertyCableConnection.BY_DIRECTION.get(fromSide)
    if (fromState.is(state.getBlock)) {
      state.setValue(prop, PropertyCableConnection.Shape.CABLE)
    }
    else {
      val tileEntity = world.getBlockEntity(pos)
      val hasNode = hasNetworkNode(tileEntity, fromSide)
      if (hasNode) {
        val neighborTileEntity = world.getBlockEntity(fromPos)
        if (neighborTileEntity != null && neighborTileEntity.getLevel != null) {
          val neighborHasNode = hasNetworkNode(neighborTileEntity, fromSide.getOpposite)
          val canConnectColor = canConnectBasedOnColor(tileEntity, neighborTileEntity)
          val canConnectIM = canConnectFromSideIM(tileEntity, fromSide) && canConnectFromSideIM(neighborTileEntity, fromSide.getOpposite)
          if (neighborHasNode && canConnectColor && canConnectIM) {
            return state.setValue(prop, PropertyCableConnection.Shape.DEVICE)
          }
        }
      }
      state.setValue(prop, PropertyCableConnection.Shape.NONE)
    }
  }

  private def hasNetworkNode(tileEntity: TileEntity, side: Direction): Boolean = {
    if (tileEntity != null) {
      if (tileEntity.isInstanceOf[tileentity.RobotProxy]) return false

      if (tileEntity.getCapability(Capabilities.SidedEnvironmentCapability, side).isPresent) {
        val host = tileEntity.getCapability(Capabilities.SidedEnvironmentCapability, side).orElse(null)
        if (host != null) {
          return if (tileEntity.getLevel.isClientSide) host.canConnect(side) else host.sidedNode(side) != null
        }
      }

      if (tileEntity.getCapability(Capabilities.EnvironmentCapability, side).isPresent) {
        val host = tileEntity.getCapability(Capabilities.EnvironmentCapability, side)
        if (host.isPresent) return true
      }
    }

    false
  }

  private def getConnectionColor(tileEntity: TileEntity): Int = {
    if (tileEntity != null) {
      if (tileEntity.getCapability(Capabilities.ColoredCapability, null).isPresent) {
        val colored = tileEntity.getCapability(Capabilities.ColoredCapability, null).orElse(null)
        if (colored != null && colored.controlsConnectivity) return colored.getColor
      }
    }

    Color.rgbValues(DyeColor.LIGHT_GRAY)
  }

  private def canConnectBasedOnColor(te1: TileEntity, te2: TileEntity) = {
    val (c1, c2) = (getConnectionColor(te1), getConnectionColor(te2))
    c1 == c2 || c1 == Color.rgbValues(DyeColor.LIGHT_GRAY) || c2 == Color.rgbValues(DyeColor.LIGHT_GRAY)
  }

  private def canConnectFromSideIM(tileEntity: TileEntity, side: Direction) =
    tileEntity match {
      case im: tileentity.traits.ImmibisMicroblock => im.ImmibisMicroblocks_isSideOpen(side.ordinal)
      case _ => true
    }
}
