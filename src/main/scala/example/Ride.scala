package example

import java.time.Instant
import java.util.UUID

import entity.{ EntityCommand, EntityEvent }

case class Ride(id: Ride.ID,
                origin: Address,
                destination: Address,
                pickupTime: Instant,
                vehicle: Option[Vehicle.ID],
                status: Ride.Status)

object Ride {
  sealed trait Status
  case object Pending   extends Status
  case object Matched   extends Status
  case object Started   extends Status
  case object Completed extends Status

  type ID = UUID
}

sealed trait RideCommand[R] extends EntityCommand[Ride.ID, Ride, R]
object RideCommand {
  case class BookRide(entityID: Ride.ID, timestamp: Instant, origin: Address, destination: Address, pickupTime: Instant)
    extends RideCommand[BookReply] {
    def initializedReply: Ride => BookReply = _ => RideAlreadyExists(entityID)
    def uninitializedReply: BookReply       = RideAccepted(entityID)
  }

  sealed trait BookReply
  case class RideAccepted(rideID: Ride.ID)      extends BookReply
  case class RideAlreadyExists(rideID: Ride.ID) extends BookReply
}

sealed trait RideEvent extends EntityEvent[Ride.ID]
object RideEvent {
  case class RideBooked(entityID: Ride.ID,
                        timestamp: Instant,
                        origin: Address,
                        destination: Address,
                        pickupTime: Instant)
    extends RideEvent
}
