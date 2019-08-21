package entity.akka

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.util.Timeout
import entity.EntityCommand
import entity.akka.PersistentEntity.CommandExpectingReply

import scala.concurrent.Future
import scala.language.higherKinds

trait TypedActorEntityRepository[ID, S, C[R] <: EntityCommand[ID, S, R], Entity <: PersistentEntity[ID, S, C, _]] {
  implicit def sharding: ClusterSharding
  implicit def actorSystem: ActorSystem[_]
  implicit def askTimeout: Timeout
  def persistentEntity: Entity

  sharding.init(
    Entity(
      persistentEntity.entityTypeKey,
      context => persistentEntity.eventSourcedEntity(context.entityId)
    )
  )

  private def entityFor(id: ID) =
    sharding.entityRefFor(persistentEntity.entityTypeKey, persistentEntity.entityIDToString(id))

  def sendCommand[R](command: C[R]): Future[R] =
    entityFor(command.entityID) ? CommandExpectingReply(command)
}
