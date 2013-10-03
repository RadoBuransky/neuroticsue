package models

import akka.actor.Actor

class NeuroticActor extends Actor {  
  def receive = {
    case NeuroticActor.HeartbeatMsg => NeuroticSueService.beatIt
  }
}

object NeuroticActor {
  val HeartbeatMsg = "heartbeat"  
}