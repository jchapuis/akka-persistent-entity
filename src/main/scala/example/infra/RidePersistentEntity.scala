package example.infra

import java.util.UUID

import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.{RecoveryCompleted, RecoveryFailed}
import entity.akka.PersistentEntity
import example.domain.{Ride, RideCommand, RideEvent, TimestampProvider}

sealed class RidePersistentEntity()(implicit serviceConfig: RideServiceConfig, timestampProvider: TimestampProvider)
  extends PersistentEntity[model.Ride.ID, Ride, RideCommand, RideEvent](serviceConfig.persistence.entityName)
    with Logging {
  def entityIDFromString(id: String): model.Ride.ID = model.Ride.RideID(UUID.fromString(id))
  def entityIDToString(id: model.Ride.ID): String   = id.uuid.toString

  override def configureEntityBehavior(
                                        id: ID,
                                        behavior: EventSourcedBehavior[Command, RideEvent, OuterState]
                                      ): EventSourcedBehavior[Command, RideEvent, OuterState] =
    behavior
      .eventAdapter(TransformerBasedEventAdapter[RideEvent, proto.RideEvent]())
      .withTagger(_ => Set(entityName))
      .receiveSignal {
        case (Initialized(state), RecoveryCompleted) =>
          logger.info(L(id, state), s"Successful recovery of ride entity $id in state $state")
        case (Uninitialized(_), _) =>
          logger.info(L(id), s"Ride entity $id created in uninitialized state")
        case (state, RecoveryFailed(error)) =>
          logger.error(L(id), s"Failed recovery of ride entity $id in state $state", error)
      }
      .onPersistFailure(
        serviceConfig.persistence.restart.toSupervisorStrategy
      )
}

object RidePersistentEntity {
  def apply()(
    implicit rideServiceConfig: RideServiceConfig,
    timestampProvider: TimestampProvider
  ): RidePersistentEntity = new RidePersistentEntity
}
