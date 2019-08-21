package example.domain

import java.time.Instant
import java.util.UUID

import entity.Logger._
import entity._
import example.domain.RideCommand._
import example.domain.RideEvent._
import example.domain

case class Ride(id: Ride.ID,
                origin: Address,
                destination: Address,
                pickupTime: Instant,
                vehicle: Option[Vehicle.ID],
                status: Ride.Status)

object Ride {
  sealed trait Status
  case object Pending   extends Status
  case object Assigned  extends Status
  case object Started   extends Status
  case object Completed extends Status

  type ID = UUID

  implicit def initialCommandProcessor(
    implicit timestampProvider: TimestampProvider
  ): InitialCommandProcessor[RideCommand, RideEvent] = {
    case BookRide(rideID, origin, destination, pickupTime) =>
      List(RideBooked(rideID, timestampProvider.timestamp, origin, destination, pickupTime))
    case otherCommand =>
      logError(s"Received erroneous initial command $otherCommand for entity")
      Nil
  }

  implicit val initialEventApplier: InitialEventApplier[Ride, RideEvent] = {
    case RideBooked(rideID, _, origin, destination, pickupTime) =>
      Some(domain.Ride(rideID, origin, destination, pickupTime, vehicle = None, status = Pending))
    case otherEvent =>
      logError(s"Received ride event $otherEvent before actual ride booking")
      None
  }

  implicit def commandProcessor(
    implicit timestampProvider: TimestampProvider
  ): CommandProcessor[Ride, RideCommand, RideEvent] = (_, command) => command match {
    case AssignVehicle(rideID, vehicleID) => List(VehicleAssigned(rideID, timestampProvider.timestamp, vehicleID))
    case StartRide(rideID)                => List(RideStarted(rideID, timestampProvider.timestamp))
    case CompleteRide(rideID)             => List(RideCompleted(rideID, timestampProvider.timestamp))
    case _: RideBooked                    => Nil
  }

  implicit val eventApplier: EventApplier[Ride, RideEvent] = (ride, event) =>
    event match {
      case VehicleAssigned(_, _, vehicleID) => ride.copy(vehicle = Some(vehicleID), status = Assigned)
      case RideStarted(_, _)                => ride.copy(status = Started)
      case RideCompleted(_, _)              => ride.copy(status = Completed)
      case _: RideBooked                    => ride
  }

}

sealed trait RideCommand[R] extends EntityCommand[Ride.ID, Ride, R]
object RideCommand {
  case class BookRide(entityID: Ride.ID, origin: Address, destination: Address, pickupTime: Instant)
    extends RideCommand[BookReply] {
    def initializedReply: Ride => BookReply = _ => RideAlreadyExists(entityID)
    def uninitializedReply: BookReply       = RideAccepted(entityID)
  }

  case class AssignVehicle(entityID: Ride.ID, vehicle: Vehicle.ID) extends RideCommand[AssignVehicleReply] {
    def initializedReply: Ride => AssignVehicleReply =
      _.vehicle.map(VehicleAlreadyAssigned).getOrElse(VehicleAssignmentReceived)
    def uninitializedReply: AssignVehicleReply = RideNotFound(entityID)
  }

  case class StartRide(entityID: Ride.ID) extends RideCommand[StartRideReply] {
    def initializedReply: Ride => StartRideReply =
      ride =>
        ride.status match {
          case Ride.Pending   => NoVehicleAssigned
          case Ride.Assigned  => StartRecorded
          case Ride.Started   => RideAlreadyStarted
          case Ride.Completed => AlreadyCompleted
      }
    def uninitializedReply: StartRideReply = RideNotFound(entityID)
  }

  case class CompleteRide(entityID: Ride.ID) extends RideCommand[CompleteRideReply] {
    override def initializedReply: Ride => CompleteRideReply =
      ride =>
        ride.status match {
          case Ride.Pending   => NoVehicleAssigned
          case Ride.Assigned  => NotStarted
          case Ride.Started   => CompletionRecorded
          case Ride.Completed => AlreadyCompleted
      }
    def uninitializedReply: CompleteRideReply = RideNotFound(entityID)
  }

  sealed trait BookReply
  case class RideAccepted(rideID: Ride.ID)      extends BookReply
  case class RideAlreadyExists(rideID: Ride.ID) extends BookReply

  sealed trait AssignVehicleReply
  case object VehicleAssignmentReceived                    extends AssignVehicleReply
  case class VehicleAlreadyAssigned(vehicleID: Vehicle.ID) extends AssignVehicleReply

  sealed trait StartRideReply
  case object StartRecorded      extends StartRideReply
  case object RideAlreadyStarted extends StartRideReply

  sealed trait CompleteRideReply
  case object CompletionRecorded extends CompleteRideReply
  case object NotStarted         extends CompleteRideReply

  case object AlreadyCompleted             extends CompleteRideReply with StartRideReply
  case object NoVehicleAssigned            extends StartRideReply with CompleteRideReply
  case class RideNotFound(rideID: Ride.ID) extends AssignVehicleReply with StartRideReply with CompleteRideReply
}

sealed trait RideEvent extends EntityEvent[Ride.ID]
object RideEvent {
  case class RideBooked(entityID: Ride.ID,
                        timestamp: Instant,
                        origin: Address,
                        destination: Address,
                        pickupTime: Instant)
    extends RideEvent
  case class VehicleAssigned(entityID: Ride.ID, timestamp: Instant, vehicleID: Vehicle.ID) extends RideEvent
  case class RideStarted(entityID: Ride.ID, timestamp: Instant)                            extends RideEvent
  case class RideCompleted(entityID: Ride.ID, timestamp: Instant)                          extends RideEvent
}
