# Domain-driven event sourcing with Akka Typed
![enter image description here](https://mdaltd.ca/wp-content/uploads/2017/08/Abrasive-Wear-300x225.jpg)

*By Jonas Chapuis and Michal Tomanski*

When following a domain-driven design approach, it is considered good practice to have the domain code at the core of the application, self-contained and with no dependencies on platform-specific aspects ([onion](https://www.infoq.com/news/2014/10/ddd-onion-architecture/) architecture). Domain code is as business-centric as possible, maximizing the ratio of business logic versus "plumbing" code. This makes business logic expressive and resilient to changes.

Although at first glance related to persistence and thus infrastructure aspects, adoption of event sourcing has a deep impact on domain expression. More than a particular technology, it is a design philosophy with an emphasis on immutable data and state evolution. In event sourcing, the state of entities is the consequence of a sequence of events applied to an initial state. Events are generated by commands sent to the entity, which represent actions in the system. This has the following implications on the "shape" of domain code:
-   business logic is expressed in the form of command processing and event handling
-   system data is described by entity state, commands and persistent events

This article introduces light abstractions that allow coding domain entities without any direct reference on Akka. This makes it possible to structure the application with separate domain and infrastructure packages. Within infrastructure code, domain entities and repositories are implemented using [Akka Persistence Typed](https://doc.akka.io/docs/akka/current/typed/persistence.html) together with [Cluster Sharding Typed](https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html). Infrastructure boilerplate is minimized thanks to a couple of generic base classes that take advantage of the recently introduced persistent entity DSL.
Much of the code presented here is inspired by this excellent [article](https://blog.softwaremill.com/keep-your-domain-clean-in-event-sourcing-5db6ddc26fe4)  by [Andrzej Ludwikowski](https://blog.softwaremill.com/@andrzej.ludwikowski?source=post_page-----5db6ddc26fe4----------------------).

## Commands, events, and state

As mentioned above, event sourcing revolves around three main [concepts](https://docs.microsoft.com/en-us/previous-versions/msp-n-p/jj591559%28v=pandp.10%29) which allow describing operations for any domain with an emphasis on evolution:

- **Command**: commands are "agents of change" in the system, and represent concrete business actions (e.g. create a booking). Commands entail a reply to the emitter, as well the generation of one or several events. 
- **Event**: events are the result of commands, and always describe a past occurrence (e.g. booking was accepted). Once generated, the event never disappears nor changes.
- **State**: the current system state is the result of unfolding events sequentially and iteratively applying event handlers on the state of entities.

Let's go ahead and capture these concepts in Scala:

### Command
A command is directed to a specific entity and thus bears a target entity ID. Along with command data, it defines the reply to the command emitter. Formulating the reply needs to be quick so that the system stays responsive. Reply formulation typically consists of validation code leading to either acceptation or rejection of the command. Purely introspective commands need to be supported in this model as well, allowing for state inspection. For such read-only commands, the command typically doesn't entail any event and the reply consists of some element of the current entity state.

Two cases can arise when receiving a command: the entity already exists, or it's the first time we're getting a command for that ID. 

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

Commands generate events via a *command processor* (see below). As is the case with commands, events reference a certain entity and thus bear an entity identifier. We also include a timestamp in the definition below, to represent the intrinsic chronological ordering of events.

```scala
trait EntityEvent[ID] {  
  def entityID: ID  
  def timestamp: Instant  
}
``` 

### Command processor
As mentioned above, command processing is about creation of one or several events to carry out the command. We define a dedicated single-function trait for this aspect:
  
```scala
trait CommandProcessor[S, C[R] <: EntityCommand[_, _, R], E <: EntityEvent[_]] {  
 def process(state: S, command: C[_]): List[E]  
}
```

The type parameters are:

- `S`: the entity state
- `C`: the command type. It has the constraint `<: EntityCommand[_, _, R]`,  representing the fact that it is an entity command. We add an `R`  type parameter to `C` itself representing the reply, which will help us when wiring this with Akka Typed.
-  `E`: the event type, with the constraint `<: EntityEvent[_]`

#### Initial command processor
When the entity hasn't been created yet (before the first command), the command processor has no entity state to work with. To allow for a clear distinction of this initial scenario, we define a slightly different trait, which makes no mention of entity state:

```scala
trait InitialCommandProcessor[C[R] <: EntityCommand[_, _, R], E <: EntityEvent[_]] {    
  def process(command: C[_]): List[E]  
}
```

### Event applier
Similarly, we define traits for event handling. This is a function of two parameters: the current state and the event, leading to a new version of the state:

```scala
trait EventApplier[S, E <: EntityEvent[_]] {  
  def apply(state: S, event: E): S  
}
```

Quite intuitively, type parameters are:

- `S`: entity state
- `E`: entity event (with the relevant constraint `<: EntityEvent[_]`
  
#### Initial event applier
In the same way as for command processing, there is always a first event for "bootstrapping" the entity. For this initial event, there is no state parameter as the entity doesn't yet exist. We capture this with a dedicated trait as well:
 
```scala
trait InitialEventApplier[E <: EntityEvent[_], S] {  
     def apply(event: E): Option[S]  
 }
 ```

## Example: "Ride" entity

Let's now put these definitions in practice with an example entity themed in the mobility space: `Ride`. This entity represents an imaginary booking for a vehicle ride. After ride creation, a vehicle from the fleet must be assigned to carry the passenger. Once assigned and in position, the vehicle picks the passenger, at which point the ride starts. When the passenger is finally dropped off, the ride completes. 

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
As can be seen above, possible replies are:
```scala
sealed trait BookReply  
case class RideAccepted(rideID: Ride.ID) extends BookReply  
case class RideAlreadyExists(rideID: Ride.ID) extends BookReply
```
The generated event will be:
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
      case _: RideBooked => 
	      logError(s"Ride ${ride.id} already booked")
	      ride
  }

  // ... 
}
```

#### AssignVehicle command
Once the booking is created, we need to select a vehicle to service the ride. For the sake of simplicity in this article, we're not delving into details, but one can imagine a sophisticated algorithm that would select the optimal vehicle. This algorithm could be monitoring `RideBooked` events from the Akka event journal to launch an optimization asynchronously, and send a command to the ride entity once a vehicle has been matched (note that event projections can also be abstracted in the domain, this could be the topic of a subsequent article).
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
And the corresponding event : 

```scala
case class VehicleAssigned(entityID: Ride.ID, timestamp: Instant, vehicleID: Vehicle.ID) extends RideEvent
```
The command processor creates the event. Since we reject commands whenever the vehicle has already been assigned, this case should not arise here so we log that as an error:
```scala
implicit def commandProcessor(  
  implicit timestampProvider: TimestampProvider  
): CommandProcessor[Ride, RideCommand, RideEvent] = (state, command) => command match {  
  case AssignVehicle(rideID, vehicleID) => 
	  if (state.vehicle.nonEmpty) {  
		  Logger.error(s"Vehicle already assigned for ride $rideID")  
		  Nil  
	  } else List(VehicleAssigned(rideID, timestampProvider.timestamp, vehicleID))  
  case _: RideBooked => Nil  
}  
```
The event applier simply sets the `vehicle` field:
```scala  
implicit val eventApplier: EventApplier[Ride, RideEvent] = (ride, event) =>  
  event match {  
  case VehicleAssigned(_, _, vehicleID) => ride.copy(vehicle = Option(vehicleID), status = Assigned)  
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
Notice above how we could recycle errors common to multiple commands by making them extend multiple reply types.

Extension of command processor and event handler is equally trivial:
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
In domain-driven design, a [repository](https://martinfowler.com/eaaCatalog/repository.html) is an abstraction for a collection of persistent entities. It is the entry point to access and manipulate entities of a certain type. We can capture this with a simple trait, whose implementation will make use of our commands and replies together with the Akka mappings, as we'll see next. This makes our ride operations easy to integrate in the rest of domain code, since it won't have to deal directly with commands. Here's an example definition for our `RideRepository`, defined in *tagless-final* style:  
```scala
trait RideRepository[F[_]] {  
  def bookRide(rideID: Ride.ID, origin: Address, destination: Address, pickupTime: Instant): F[BookReply]  
  def assignVehicle(rideID: Ride.ID, vehicle: Vehicle.ID): F[AssignVehicleReply]  
  def startRide(rideID: Ride.ID): F[StartRideReply]  
  def completeRide(rideID: Ride.ID): F[CompleteRideReply]  
}
```
We have kept this example very simple and directly transposed our command "language" into a set of functions, and kept the reply types. In a real case, we would typically transpose command reply types into some other types, typically distinguishing error from success more clearly with `Either`.  
 

### Testing
Thanks to abstraction and separation of concerns, our commands and events logic requires minimal test setup and the distinct behavioral aspects can be covered in isolation:
 - command replies according to command parameters and entity states
 - generated events according to entity state and commands
 - resulting entity states from stimulating event applier with events 

## Implementation with Akka Persistence Typed
Let's take a plunge now and dive into the *infrastructure* layer. This is where we'll wire our abstractions with Akka. Thanks to the expressiveness of Akka Persistence Typed API, this mapping is surprisingly short, albeit a bit complicated on the typing side.     

### PersistentEntity
The bulk of the code is in an abstract `PersistentEntity` class, which exposes a `eventSourcedEntity(id: String): EventSourcedBehavior` public function. This will be used by the repository implementation as the actor behavior. This class is typed like so:
```scala
abstract class PersistentEntity[ID, InnerState, C[R] <: EntityCommand[ID, InnerState, R], E <: EntityEvent[ID]]
```
  - `ID`: entity ID
  - `InnerState`: entity state - we call this *inner* because the actual entity state is a sealed trait, acting basically like `Option[InnerState]` (since before the first event the entity is empty)
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

#### RidePersistentEntity

Defining the persistent entity behavior is now easily done by extending `PersistentEntity`. This is were everything comes together:
```scala
sealed class RidePersistentEntity()(implicit timestampProvider: TimestampProvider)
  extends PersistentEntity[Ride.ID, Ride, RideCommand, RideEvent](RidePersistentEntity.entityName) {
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
  def apply()(implicit timestampProvider: TimestampProvider): RidePersistentEntity = new RidePersistentEntity
  val entityName = "ride"
}
```
Definitions for implicit parameters `initialProcessor`, `processor`, `initialApplier`, `applier` are picked up automatically from `Ride` companion object, which is why we had published them in implicit scope earlier.
The only significant logic here is the additional persistent behavior configuration in `configureEntityBehavior`. This lets us take advantage of tweaking options, e.g. to [install an event adapter](https://doc.akka.io/docs/akka/current/typed/persistence.html#event-adapters), define event tags, etc.

###  TypedActorEntityRepository
As mentioned earlier, the repository is implemented by sending commands an decoding replies. Here's the trait definition:
```scala
trait TypedActorEntityRepository[ID, S, C[R] <: EntityCommand[ID, S, R], Entity <: PersistentEntity[ID, S, C, _]]
```
Type parameters are:
 - `ID`: entity id
 - `S`: entity state
 - `C`: entity command top type
 - `Entity`: the concrete persistent entity class

The repository trait takes care of initializing sharding with the persistent entity behavior. It requires the definition of the `PersistentEntity` instance, and exposes a def for looking up entities for a certain ID and sending them commands:
```scala
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

  def sendCommand[R](command: C[R]): Future[R] =
    entityFor(command.entityID) ? CommandExpectingReply(command)
    
  private def entityFor(id: ID) =
    sharding.entityRefFor(persistentEntity.entityTypeKey, persistentEntity.entityIDToString(id))
}
```
#### TypedRideEntityRepository
Implementation of this trait for `Ride` is where instantiation of `RidePersistentEntity` happens. Implementation of repository methods is simply about sending the relevant command:

```scala
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
  def startRide(rideID: ID): Future[RideCommand.StartRideReply] = sendCommand(StartRide(rideID))
  def completeRide(rideID: ID): Future[RideCommand.CompleteRideReply] = sendCommand(CompleteRide(rideID))
}
```
## Command Flow
Let's make this a bit more concrete by previewing how the flow for the `bookRide` command will unfold when calling `bookRide` on the repository:
 1. Akka Cluster Sharding extension attempts to resolve an actor reference for the specified `rideID`.
 2. Since none already exists in the cluster, entity actor is created on some node and fed with the initial `BookRide` command.
 3. `initialCommandProcessor` is invoked, generating a `RideBooked` event.
 4.  `BookRide.uninitializedReply` function is called, leading to the `RideAccepted`  reply. 
 5. The event is picked up and `initialEventApplier` initialises entity state with it. 
 6. The entity is now ready to receive subsequent commands, which will be processed with `commandProcessor` and whose events will be handled using `eventApplier`.  

Note that command processing and event application proceeds strictly sequentially: the actor processes the command, stores the events, replies to the sender, applies the event leading to a new state, then processes the next command and so on. This makes for comprehensible state machine descriptions. Rather than leading to complicated program flow, asynchronicity is harnessed by the inherent distributive nature of domain entities. 

This concludes our implementation tour. Event journal and adapter configuration aside, we now have a fully functional repository for rides!

## DDD :heart: actor model
Although very simplified, this example illustrates the "good fit" of the actor model to domain-driven-design: aggregates are represented by entities with well defined sequential state transitions and a command and event "language" to represent actions and facts.
We have shown an approach to describe event-sourced entities in the domain using such an abstract language, and the required infrastructure plumbing code to map these pure definitions to an Akka Persistence Typed implementation.

Supporting code for this article can be found in its entirety [here](https://github.com/jchapuis/akka-persistent-entity). We hope this was useful and would love your feedback! Feel free to reach out to the authors for more information.
<!--stackedit_data:
eyJoaXN0b3J5IjpbLTkwNjg3NzI5NiwxMjExODg4ODgyLDM3Nj
I3MDIyLC0xNjA0MDAzNjAsLTE0MjEwOTM3MzYsLTM4MTQxNDg5
MywzMjU5NzA4MjYsMjExOTM4MTA0MiwxNjM4MTMxMzAzLDE0Mz
c0NDkwNDksMTQxMDU4NjEwMywtNDMzNDc3MTM0LDYzMjU0MjA1
LC0zOTA1NTA1MDIsMTQxMTIxNTIyMCwtNTE4MDI4NDgxLC00NT
c5NTc0MTYsNDE4NjM1MDgzLC05OTk0Nzc3Myw0ODQ3OTkzNDVd
fQ==
-->
