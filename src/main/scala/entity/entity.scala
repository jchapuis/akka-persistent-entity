import java.time.Instant

import scala.language.higherKinds

package object entity {
  trait EntityCommand[ID, S, R] {
    def entityID: ID
    def initializedReply: S => R
    def uninitializedReply: R
  }

  trait EntityEvent[ID] {
    def entityID: ID
    def timestamp: Instant
  }

  trait InitialCommandProcessor[C[R] <: EntityCommand[_, _, R], E <: EntityEvent[_]] {

    /**
      * Process the command and create resulting events
      *
      * @param command incoming command
      * @return list of resulting events
      */
    def process(command: C[_]): List[E]
  }

  trait CommandProcessor[S, C[R] <: EntityCommand[_, _, R], E <: EntityEvent[_]] {

    /**
      * Process the command and create resulting events
      *
      * @param state current state, none if initial command
      * @param command incoming command
      * @return list of resulting events
      */
    def process(state: S, command: C[_]): List[E]
  }

  trait InitialEventApplier[S, E <: EntityEvent[_]] {

    /**
      * Consume the event
      *
      * @param event incoming event
      * @return updated state
      */
    def apply(event: E): Option[S]
  }

  trait EventApplier[S, E <: EntityEvent[_]] {

    /**
      * Consume the event
      *
      * @param state current state
      * @param event incoming event
      * @return updated state
      */
    def apply(state: S, event: E): S
  }

}
