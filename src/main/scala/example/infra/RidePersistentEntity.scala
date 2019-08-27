package example.infra

import java.util.UUID

import akka.actor.typed.SupervisorStrategy
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.{RecoveryCompleted, RecoveryFailed}
import entity.Logger
import entity.akka.PersistentEntity
import example.domain.{Ride, RideCommand, RideEvent, TimestampProvider}

import scala.concurrent.duration._

sealed class RidePersistentEntity()(implicit timestampProvider: TimestampProvider)
  extends PersistentEntity[Ride.ID, Ride, RideCommand, RideEvent]("ride") {
  def entityIDFromString(id: String): Ride.ID = UUID.fromString(id)
  def entityIDToString(id: Ride.ID): String   = id.toString

  override def configureEntityBehavior(
    id: Ride.ID,
    behavior: EventSourcedBehavior[Command, RideEvent, OuterState]
  ): EventSourcedBehavior[Command, RideEvent, OuterState] =
    behavior
      .receiveSignal {
        case (Initialized(state), RecoveryCompleted) =>
          Logger.info(s"Successful recovery of ride entity $id in state $state")
        case (Uninitialized(_), _) =>
          Logger.info(s"Ride entity $id created in uninitialized state")
        case (state, RecoveryFailed(error)) =>
          Logger.error(s"Failed recovery of ride entity $id in state $state: $error")
      }
      .onPersistFailure(
        SupervisorStrategy
          .restartWithBackoff(
            minBackoff =10 seconds,
            maxBackoff = 60 seconds,
            randomFactor = 0.1
          )
          .withMaxRestarts(5)
      )
}

object RidePersistentEntity {
  def apply()(
    implicit
    timestampProvider: TimestampProvider
  ): RidePersistentEntity = new RidePersistentEntity
}
