
# Domain-driven event sourcing with Akka Typed
![enter image description here](https://mdaltd.ca/wp-content/uploads/2017/08/Abrasive-Wear-300x225.jpg)
When following a domain-driven design approach, it is considered good practice to have the domain code at the core of the application, self-contained and with no dependencies on platform-specific aspects ([onion](https://www.infoq.com/news/2014/10/ddd-onion-architecture/) architecture). Domain code is as business-centric as possible, maximising the ratio of business logic versus "plumbing" code. This makes business logic very expressive, and resilient to changes.

Adoption of of event sourcing however has a deep impact on domain expression. More than a particular technology, is it a design philosophy that sets evolution of system at the center, rather than current state. In event sourcing, the state of entities is merely the consequence of a sequence of events, which are generated by commands. This has the following implications on the "shape" of domain code:
-   business logic is expressed in the form of command processing and event application functions
-   business data is captured by entity state, commands and persistent events

This article introduces light abstractions that allow coding domain entities without any direct reference on Akka. This makes it possible to structure the application with separate domain and infrastructure packages. Within the infrastructure, domain entities and repositories are implemented using [Akka Persistence Typed](https://doc.akka.io/docs/akka/current/typed/persistence.html) together with [Cluster Sharding Typed](https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html). Infrastructure boilerplate is minimised thanks to a couple of generic traits that take advantage of the recently introduced persistent entity DSL.
Much of the code presented here is inspired by this excellent [article](https://blog.softwaremill.com/keep-your-domain-clean-in-event-sourcing-5db6ddc26fe4)  by [Andrzej Ludwikowski](https://blog.softwaremill.com/@andrzej.ludwikowski?source=post_page-----5db6ddc26fe4----------------------).

## Commands, events and state

As mentioned above, event sourcing revolves around three main [concepts](https://docs.microsoft.com/en-us/previous-versions/msp-n-p/jj591559%28v=pandp.10%29), which allow describing operations for any domain, with an emphasis on evolution:

- **Command**:  commands are "agents of change" in the system, and represent  concrete business actions (e.g. create a booking). Commands entail a reply to the emitter, as well generation of one or several events. 
- **Event**: events are the result of commands, and always describe a past occurence (e.g. booking was accepted). Once generated, the event never disappears nor changes.
- **State**: current system state is the result of unfolding events sequentially and iteratively applying event handlers on the state of entities.

Let's go ahead and capture these concepts in Scala:

### Command
A command is directed to a specific entity, and thus bears a target entity ID. Along with command data, we need to define what to reply to the command emitter. Formulating the reply need to be quick, so that the system stays responsive. Reply formulation typically consist in validation code leading to either acceptation or rejection of the command. 

Two cases can arise when receiving a command: the entity already exists, or it's the first time we're getting a command for that ID. Purely introspective commands need to be supported in this model as well, allowing state inspection. For such read-only commands, the command typically doesn't entail any event and the reply consists of some element of current entity state.

Here's a trait for entity commands:    

```scala
trait EntityCommand[ID, S, R] {  
  def entityID: ID  
  def initializedReply: S => R  
  def uninitializedReply: R  
}
```

The type parameters are:

- `ID`: entity identifier
- `S`: entity state
- `R`: command reply

Along with the `entityID`, we define a function `initializedReply: S => R`  and `uninitializedReply: R` for reply formulation when the entity already exists or doesn't respectively.

### Event

Commands generate events by means of a *command processor* (see below). As is the case with commands, events reference a certain entity and thus bear an entity identifier. We also include a timestamp in the definition below, in order to implement the natural chronological ordering of events.

```scala
trait EntityEvent[ID] {  
  def entityID: ID  
  def timestamp: Instant  
}
``` 

### Command processor
As mentioned above, command processing designates creation of one or several events from a command. We define a dedicated single-function trait for this aspect:
  
```scala
trait CommandProcessor[S, C[R] <: EntityCommand[_, _, R], E <: EntityEvent[_]] {  
 def process(state: S, command: C[_]): List[E]  
}
```

The type parameters are:

- `S`: the entity state
- `C`: the command type. It has the constraint `<: EntityCommand[_, _, R]`,  representing the fact that it is an entity command. We add a `R`  type parameter to `C` itself representing the reply, which will help us with implementation with Akka Typed.
-  `E`: the event type, with the constraint `<: EntityEvent[_]]`

#### Initial command processor
Obviously, when the entity hasn't been created yet (before the first command), the command processor has no entity state to work with. In order to allow for a clear distinction of this initial scenario, we define a slightly different trait, which makes no mention of entity state:

```scala
trait InitialCommandProcessor[C[R] <: EntityCommand[_, _, R], E <: EntityEvent[_]] {    
  def process(command: C[_]): List[E]  
}
```

### Event applier
Similarly, we define traits for event application. When the entity exists, this is a function of two parameters: the current state and the event, leading to a new version of the state:

```scala
trait EventApplier[S, E <: EntityEvent[_]] {  
  def apply(state: S, event: E): S  
}
```

Quite intuitively, type parameters are:

- `S`: entity state
- `E`: entity event (with the relevant constraint `<: EntityEvent[_]`
  
#### Initial event applier
In the same way as for command processing, there is always a first event for "bootstrapping" the entity. For this initial event, there is obviously no state parameter as the entity doesn't yet exist. We capture this with a dedicated trait as well:
 
```scala
trait InitialEventApplier[E <: EntityEvent[_], S] {  
     def apply(event: E): Option[S]  
 }
 ```

## Example: "Ride" entity

Let's now put these definitions in practice with an example entity themed in the mobility space: `Ride`. This entity represents an imaginary booking for a vehicle ride. Once created, some vehicle from the fleet must be assigned to carry the passenger. Once assigned and in position, the vehicle picks the passenger, at which point the ride starts. When the passenger is finally dropped off, the ride completes. 

### Ride entity state
We start by defining the entity state with a plain case class (we are omitting some types for simplicity):
```scala
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
}
```
### Ride commands and events
We can now define commands relevant to this example. Let's start by refining the `EntityCommand` trait into `RideCommand`: 
```scala 
sealed trait RideCommand[R] extends EntityCommand[Ride.ID, Ride, R]
```
We fix the `ID` and `S` parameters while keeping the `R` open to benefit from precise reply types, this will be helpful when wiring these commands with Akka Typed.

We do the same for events:
```scala
sealed trait RideEvent extends EntityEvent[Ride.ID]
``` 
#### BookRide command
Let's start with the command leading to ride creation, `BookRide`:
```scala 
case class BookRide(entityID: Ride.ID, origin: Address, destination: Address, pickupTime: Instant)  
  extends RideCommand[BookReply] {  
  def initializedReply: Ride => BookReply = _ => RideAlreadyExists(entityID)  
  def uninitializedReply: BookReply = RideAccepted(entityID)  
}
```
Possible replies are:
```scala
sealed trait BookReply  
case class RideAccepted(rideID: Ride.ID) extends BookReply  
case class RideAlreadyExists(rideID: Ride.ID) extends BookReply
```
And the generated persistent event will be:
```scala
case class RideBooked(entityID: Ride.ID,  
  timestamp: Instant,  
  origin: Address,  
  destination: Address,  
  pickupTime: Instant)  
  extends RideEvent
```
Let's place corresponding command processing and event application logic directly within the entity companion object as implicit definitions (we will benefit from this when wiring up infrastructure code - more on than later):

```scala
object Ride {
 
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
      Some(Ride(rideID, origin, destination, pickupTime, vehicle = None, status = Pending))
    case otherEvent =>
      logError(s"Received ride event $otherEvent before actual ride booking")
      None
  }

  implicit val eventApplier: EventApplier[Ride, RideEvent] = (ride, event) =>
    event match {
      case _: RideBooked => ride
  }

  // ... 
}
```
##### Command Flow
Let's jump ahead a little and make this more concrete by previewing how the flow for this command will unfold when mapping these definitions with Akka Persistence:
 1. Akka Cluster Sharding extension attempts resolving an actor reference for the specified `rideID`.
 2. Since none already exists in the cluster, entity actor is created on some node and fed with the initial `BookRide` command.
 3. `initialCommandProcessor` is invoked, generating a `RideBooked` event.
 4.  `BookRide.uninitializedReply` function is called, leading to the `RideAccepted`  reply. 
 5. The event is picked up and `initialEventApplier` initialises entity state with it. 
 6. The entity is now ready to receive subsequent commands, which will be processed with `commandProcessor` and whose events will be handled using `eventApplier`.  

Note that command processing and event application proceeds strictly sequentially: the actor processes the command, stores the events, replies to the sender, applies the event leading to new state, then processes the next command and so on. This makes for comprehensible state machine descriptions. Rather than with complicated program flow, asynchronicity is harnessed by the inherent distributive nature of domain entities. 

#### AssignVehicle command
Once the booking is created, we need to select a vehicle to service the ride. For the sake of simplicity in this article we're going to grossly gloss that over, but one can imagine a sophisticated algorithm which would select the optimal vehicle according to a set of criteria. This algorithm could be monitoring `RideBooked` events from the Akka event journal to launch an optimization asynchronously, and send a command back to the ride entity once a vehicle has been matched (note that event projections can also be abstracted in the domain, this could be the topic of a subsequent article).
Let's name this command `AssignVehicle`. Here's a simple definition for it:   

```scala
case class AssignVehicle(entityID: Ride.ID, vehicle: Vehicle.ID) extends RideCommand[AssignVehicleReply] {  
  def initializedReply: Ride => AssignVehicleReply =  
  _.vehicle.map(VehicleAlreadyAssigned).getOrElse(VehicleAssignmentReceived)  
  def uninitializedReply: AssignVehicleReply = RideNotFound(entityID)  
}

sealed trait AssignVehicleReply  
case object VehicleAssignmentReceived extends AssignVehicleReply  
case class VehicleAlreadyAssigned(vehicleID: Vehicle.ID) extends AssignVehicleReply  
case class RideNotFound(rideID: Ride.ID) extends AssignVehicleReply
```
Here's the corresponding event : 

```scala
case class VehicleAssigned(entityID: Ride.ID, timestamp: Instant, vehicleID: Vehicle.ID) extends RideEvent
```
And command processor as well as event applier:
```scala
implicit def commandProcessor(  
  implicit timestampProvider: TimestampProvider  
): CommandProcessor[Ride, RideCommand, RideEvent] = (_, command) => command match {  
  case AssignVehicle(rideID, vehicleID) => List(VehicleAssigned(rideID, timestampProvider.timestamp, vehicleID))  
  case _: RideBooked => Nil  
}  
  
implicit val eventApplier: EventApplier[Ride, RideEvent] = (ride, event) =>  
  event match {  
  case VehicleAssigned(_, _, vehicleID) => ride.copy(vehicle = Some(vehicleID), status = Assigned)  
  case _: RideBooked => ride  
}
```
#### StartRide and CompleteRide commands
The two remaining commands in our simplified description of a ride lifecycle are `StartRide` and `CompleteRide`, which describe passenger pickup and dropoff respectively. The  corresponding definitions are given below:
```scala
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

sealed trait StartRideReply  
case object StartRecorded extends StartRideReply  
case object RideAlreadyStarted extends StartRideReply  
  
sealed trait CompleteRideReply  
case object CompletionRecorded extends CompleteRideReply  
case object NotStarted extends CompleteRideReply  
  
case object AlreadyCompleted extends CompleteRideReply with StartRideReply  
case object NoVehicleAssigned extends StartRideReply with CompleteRideReply  
case class RideNotFound(rideID: Ride.ID) extends AssignVehicleReply with StartRideReply with CompleteRideReply
```
Notice above how we could recycle errors common to multiple commands by making them extend multiple reply types. Extension of command processor and event handler is equally trivial:
```scala
implicit def commandProcessor(  
  implicit timestampProvider: TimestampProvider  
): CommandProcessor[Ride, RideCommand, RideEvent] = (_, command) => command match {
  ... 
  case StartRide(rideID)    => List(RideStarted(rideID, timestampProvider.timestamp))  
  case CompleteRide(rideID) => List(RideCompleted(rideID, timestampProvider.timestamp))  
}  
  
implicit val eventApplier: EventApplier[Ride, RideEvent] = (ride, event) =>  
  event match {  
  ...
  case RideStarted(_, _)    => ride.copy(status = Started)  
  case RideCompleted(_, _)  => ride.copy(status = Completed)    
}
``` 
### Ride repository
The concept of repository can be traditionally captured in the domain by a trait, nothing special here. Here's an example definition for our `RideRepository`, defined in *tagless-final* style:  
```scala
trait RideRepository[F[_]] {  
  def bookRide(rideID: Ride.ID, origin: Address, destination: Address, pickupTime: Instant): F[BookReply]  
  def assignVehicle(rideID: Ride.ID, vehicle: Vehicle.ID): F[AssignVehicleReply]  
  def startRide(rideID: Ride.ID): F[StartRideReply]  
  def completeRide(rideID: Ride.ID): F[CompleteRideReply]  
}
```
Note how we have made it very simple here and directly transposed our command "language" into a set of functions, and kept the reply types. Such a repository trait is the entry point for the rest of domain code to send command and deal with replies. In a real case, we would typically transpose command reply types into some other types, typically distinguishing error from success more clearly with `Either`.  
The implementation of this trait will make use of commands and replies together with the Akka mappings, as we'll see next. 

### DDD :heart: actor model
Although very simplified, this example illustrates the "good fit" of the actor model to domain-driven-design: aggregates are represented by entities with well defined sequential state transitions and a command and event "language" to represent actions and facts.
### Testing
It follows from this abstraction and separation of concerns that entity business logic requires minimal test setup, and distinct behavioural aspects can be covered in isolation:
 - command replies according to command parameters and entity states
 - generated events according to entity state and commands
 - resulting entity states from stimulating event applier with events 

## Implementation with Akka Persistence Typed
Let's take a plunge now and dive into the *infrastructure* layer. This is where we'll wire our abstractions with Akka. Thanks to the expressiveness of Akka Persistence Typed API, this mapping is surprisingly short, albeit a bit complicated on the typing side.     

The bulk of the code is in an abstract `PersistentEntity` class, which exposes a `eventSourcedEntity(id: String): EventSourcedBehavior` public function which will be used by the repository implementation. This class is typed like so:
```scala
abstract class PersistentEntity[ID, InnerState, C[R] <: EntityCommand[ID, InnerState, R], E <: EntityEvent[ID]]
```
  - `ID`: entity ID
  - `InnerState`: entity state - we call this *inner* because the actual entity state is a sealed trait acting basically like `Option[InnerState]` since before the first event the entity is empty
  - `C`: command type, with corresponding type constraint
  - `E`: event type, also with relevant type constraint

Here's the full class definition:
```scala
abstract class PersistentEntity[ID, InnerState, C[R] <: EntityCommand[ID, InnerState, R], E <: EntityEvent[ID]](
  val entityName: String
)(implicit
  initialProcessor: InitialCommandProcessor[C, E],
  processor: CommandProcessor[InnerState, C, E],
  initialApplier: InitialEventApplier[InnerState, E],
  applier: EventApplier[InnerState, E]) {
  sealed trait OuterState
  case class Initialized(state: InnerState) extends OuterState
  case class Uninitialized(id: ID)          extends OuterState

  type Command = CommandExpectingReply[_, InnerState, C]

  val entityTypeKey: EntityTypeKey[Command] =
    EntityTypeKey[Command](entityName)

  private val commandHandler: (OuterState, Command) => ReplyEffect[E, OuterState] =
    (entityState, command) => {
      entityState match {
        case _: Uninitialized =>
          val events = initialProcessor.process(command.command)
          command.uninitializedReplyAfter(if (events.nonEmpty) Effect.persist(events) else Effect.none)
        case Initialized(innerState) =>
          val events = processor.process(innerState, command.command)
          command.initializedReplyAfter(if (events.nonEmpty) Effect.persist(events) else Effect.none, innerState)
      }
    }

  private val eventHandler: (OuterState, E) => OuterState = { (entityState, event) =>
    entityState match {
      case uninitialized @ Uninitialized(_) =>
        initialApplier.apply(event).map(Initialized).getOrElse[OuterState](uninitialized)
      case Initialized(state) => Initialized(applier.apply(state, event))
    }
  }

  def entityIDFromString(id: String): ID
  def entityIDToString(id: ID): String

  def eventSourcedEntity(id: String): EventSourcedBehavior[Command, E, OuterState] = {
    val entityID = entityIDFromString(id)
    configureEntityBehavior(entityID, createEventSourcedEntity(entityID))
  }

  protected def configureEntityBehavior(
    id: ID,
    behavior: EventSourcedBehavior[Command, E, OuterState]
  ): EventSourcedBehavior[Command, E, OuterState]

  private def createEventSourcedEntity(entityID: ID) =
    EventSourcedEntity(
      entityTypeKey,
      entityID.toString,
      Uninitialized(entityID),
      commandHandler,
      eventHandler,
    )
}

object PersistentEntity {
  case class CommandExpectingReply[R, InnerState, C[Reply] <: EntityCommand[_, InnerState, Reply]](command: C[R])(
    val replyTo: ActorRef[R]
  ) extends ExpectingReply[R] {
    def initializedReplyAfter[E, S](effect: EffectBuilder[E, S], state: InnerState): ReplyEffect[E, S] =
      effect.thenReply(this)(_ => command.initializedReply(state))
    def uninitializedReplyAfter[E, S](effect: EffectBuilder[E, S]): ReplyEffect[E, S] =
      effect.thenReply(this)(_ => command.uninitializedReply)
  }
}
```  
Notice how we left `protected  def configureEntityBehavior()` open for extension, this allows refining the persistence behaviour in subclasses.

### RidePersistentEntity

Definition of a persistent entity behaviour is now easily done by extending `PersistentEntity`. This is were everything comes together:
```scala
sealed class RidePersistentEntity()(implicit timestampProvider: TimestampProvider)
  extends PersistentEntity[Ride.ID, Ride, RideCommand, RideEvent]("ride") {
  def entityIDFromString(id: String): Ride.ID = UUID.fromString(id)
  def entityIDToString(id: Ride.ID): String   = id.toString

  override def configureEntityBehavior(
    id: Ride.ID,
    behavior: EventSourcedBehavior[Command, RideEvent, OuterState]
  ): EventSourcedBehavior[Command, RideEvent, OuterState] =
    behavior
      .eventAdapter(TransformerBasedEventAdapter[RideEvent, proto.RideEvent]())
      .withTagger(_ => Set(entityName))
      .receiveSignal {
        case (Initialized(state), RecoveryCompleted) =>
          Logger.info(s"Successful recovery of ride entity $id in state $state")
        case (Uninitialized(_), _) =>
          Logger.info(s"Ride entity $id created in uninitialized state")
        case (state, RecoveryFailed(error)) =>
          Logger.error(s"Failed recovery of ride entity $id in state $state: $error")
      }
      .onPersistFailure(
        serviceConfig.persistence.restart.toSupervisorStrategy
      )
}

object RidePersistentEntity {
  def apply()(
    implicit
    timestampProvider: TimestampProvider
  ): RidePersistentEntity = new RidePersistentEntity
}
```

*Mention persistence (what's missing from the picture)*
<!--stackedit_data:
eyJoaXN0b3J5IjpbMjA1OTY1Mzc1MiwtOTk5NDc3NzMsNDg0Nz
k5MzQ1LC0xODY1NTQyOTgyXX0=
-->