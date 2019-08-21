package example.infra

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import example.domain.{Ride, RideCommand, RideRepository, TimestampProvider}

import scala.concurrent.{ExecutionContext, Future}

class TypedActorRideRepository()(
  implicit val sharding: ClusterSharding,
  val actorSystem: ActorSystem[_],
  val askTimeout: Timeout,
  rideServiceConfig: RideServiceConfig,
  timestampProvider: TimestampProvider
) extends TypedActorEntityRepository[ID, Ride, RideCommand, RidePersistentEntity]
  with RideRepository[Future]
  with Logging {
  implicit val executionContext: ExecutionContext = actorSystem.executionContext
  lazy val persistentEntity                       = RidePersistentEntity()

  def createRide(rideDemand: RideDemand): Future[RideAlreadyExists \/ ID] =
    sendCommand(RideCommand.Create(RideID.generate, rideDemand)) map {
      case RideCommand.Reply.DemandAccepted(rideID) => rideID.asRight
      case RideCommand.Reply.AlreadyCreated(rideID) => RideAlreadyExists(rideID).asLeft
    }

  def cancelRide(rideID: ID): Future[RideNotFound \/ Unit] =
    sendCommand(RideCommand.Cancel(rideID)) map {
      case RideCommand.Reply.CancellationAccepted => ().asRight
      case RideCommand.Reply.RideNotFound(_)      => RideNotFound(rideID).asLeft
    }
}
