name := "akka-persistent-entity"

version := "0.1"

scalaVersion := "2.13.0"

lazy val akkaVersion              = "2.5.23"
lazy val akkaActorTyped           = "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion
lazy val akkaLogging              = "com.typesafe.akka" %% "akka-slf4j" % akkaVersion
lazy val akkaPersistenceTyped     = "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion
lazy val akkaClusterTyped         = "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion
lazy val akkaClusterShardingTyped = "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion
  
libraryDependencies ++= Seq(akkaActorTyped, 
  akkaLogging, 
  akkaPersistenceTyped, 
  akkaClusterTyped,
  akkaClusterShardingTyped)
  