package example.domain

import java.time.Instant

import example.domain.RideCommand._

trait RideRepository[F[_]] {
  def bookRide(rideID: Ride.ID, origin: Address, destination: Address, pickupTime: Instant): F[BookReply]
  def assignVehicle(rideID: Ride.ID, vehicle: Vehicle.ID): F[AssignVehicleReply]
  def startRide(rideID: Ride.ID): F[StartRideReply]
  def completeRide(rideID: Ride.ID): F[CompleteRideReply]
}
