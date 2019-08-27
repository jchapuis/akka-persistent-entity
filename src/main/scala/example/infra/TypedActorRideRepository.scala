package example.infra

import java.time.Instant

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import entity.akka.TypedActorEntityRepository
import example.domain.Ride.ID
import example.domain.RideCommand.{ AssignVehicle, BookRide, CompleteRide, StartRide }
import example.domain.{ Address, Ride, RideCommand, RideRepository, TimestampProvider }

import scala.concurrent.{ ExecutionContext, Future }

class TypedActorRideRepository()(
  implicit val sharding: ClusterSharding,
  val actorSystem: ActorSystem[_],
  val askTimeout: Timeout,
  timestampProvider: TimestampProvider
) extends TypedActorEntityRepository[ID, Ride, RideCommand, RidePersistentEntity]
  with RideRepository[Future] {
  implicit val executionContext: ExecutionContext = actorSystem.executionContext
  lazy val persistentEntity                       = RidePersistentEntity()

  def bookRide(rideID: ID, origin: Address, destination: Address, pickupTime: Instant): Future[RideCommand.BookReply] =
    sendCommand(BookRide(rideID, origin, destination, pickupTime))
  def assignVehicle(rideID: ID, vehicle: ID): Future[RideCommand.AssignVehicleReply] =
    sendCommand(AssignVehicle(rideID, vehicle))
  def startRide(rideID: ID): Future[RideCommand.StartRideReply]       = sendCommand(StartRide(rideID))
  def completeRide(rideID: ID): Future[RideCommand.CompleteRideReply] = sendCommand(CompleteRide(rideID))
}
