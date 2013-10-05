package models

import java.net.URL
import scala.Mutable
import scala.collection.mutable.Queue
import scala.collection.mutable.SynchronizedQueue
import scala.concurrent._
import scala.concurrent.Future
import org.joda.time.DateTime
import play.api.Logger
import play.api.http.Status
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.ws.WS
import play.libs.Akka
import akka.actor.Props
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import akka.actor.Cancellable
import scala.concurrent.duration.FiniteDuration
import org.joda.time.Period
import scala.annotation.tailrec

case class NeuroticResult(hasChanged: Option[Boolean], baseline: Option[Int], error: Option[String])

object NeuroticSueService {
  private type Heartbeat = Long
  
	private case class NeuroticServer(host: String, lastChecked: Option[DateTime])
	private case class NeuroticResource(url: URL, contents: Option[Int],
	    lastChecked: DateTime, lastError: Option[String], var lastRequested: DateTime)	
	    
  private case class QueuedServer(server: NeuroticServer, resources: Queue[NeuroticResource]) {
    def addResource(resource: NeuroticResource): Unit = {
      resources.find(r => r.url == resource.url) match {
        case Some(r) => throw new IllegalStateException("Resource with the same URL is already in the queue!")
        case None => {
          // Push new resource
          resources.enqueue(resource)
        }
      }
    }    
  }
	
	// Minimal duration between two checks for the same server
	private val MinServerDelay = Duration.create(10, TimeUnit.SECONDS)
	
	// Minimal duration between two checks for the same URL
	private val MinResourceDelay = Duration.create(1, TimeUnit.MINUTES)
	private val ResourceTolerance = MinResourceDelay / 4
	
	// Maximal duration between two checks for the same URL
	private val MaxResourceDelay = Duration.create(2, TimeUnit.MINUTES)
	
	// Maximal heartbeat (in seconds) 
	private val MaxHeartbeat = Duration.create(1, TimeUnit.SECONDS)
	
	// Cleanup resource after 5 minutes
	private val TimeToLive = Duration.create(5, TimeUnit.MINUTES)
	
	// Maximal number of resources allowed for a single client
	private val MaxResourcesPerClient = 2
	
	private val MessageTooBusy = "Sorry, I am too busy now. Try again later."

  // Queue of servers with queues of resources
  private val servers = new SynchronizedQueue[QueuedServer]
  
  // Queue of clients (remote address -> URL)
  private val clients = new SynchronizedQueue[(String, URL)]
	
	// Current heartbeat
	private var heartbeat: Duration = Duration.Undefined
	
	// Scheduler for the neurotic actor
	private var scheduler: Option[Cancellable] = None
  
  def hasChanged(url: URL, baseline: String): NeuroticResult = {
    require(url != null, "url is required!")
    require(baseline != null, "baseline is required!")
    
    findResource(url) match {
      case Some(res) => {
        res.lastRequested = DateTime.now
        
        val hasChanged = res.lastError match {
        	case Some(error) => None
        	case None => Option(res.contents != baseline)
    		}
        
        NeuroticResult(hasChanged, res.contents, res.lastError)
      }
      case None => throw new IllegalStateException("URL is not in queued!")
    }
  }
  
  def getBaseline(url: URL, remoteAddress: String): Future[NeuroticResult] = {
    require(url != null, "url is required!")
    
    findResource(url) match {
      case Some(res) => future { NeuroticResult(None, res.contents, None) }
      case None => addResource(url, remoteAddress)
    }
  }
  
  private[models] def beatIt = {
    pumpServers(servers)
  }
  
  @tailrec
  private def pumpServers(servers: Queue[QueuedServer]): Unit = {
    if (servers.isEmpty) {
      // This can happen if all resources are being downloaded
      return;
    }
    
    // Remove server from the queue
    val qs = servers.dequeue()
    
    if ((isEligible(qs.server.lastChecked, heartbeat)) &&
        (isEligible(Option(qs.resources.head.lastChecked), ResourceTolerance))) {
      // Remove resource from the queue
      val resource = qs.resources.dequeue()
      
      if (isTimeToDie(resource)) {
        if (!qs.resources.isEmpty) {
          // Put the server back to the queue
          servers.enqueue(qs)
        }
          
        // Number of resources and servers has changed 
        updateHeartbeat()
      }
      else {      
	      // Download resource and enqueue it once it's downloaded
	      getResource(resource.url, resource.lastRequested) map { resource =>
	        qs.resources.enqueue(resource)
	        servers.enqueue(qs)
	      }
      }
    }
    else {
      servers.enqueue(qs)
      
      // Pump the queue
      pumpServers(servers)
    }
  }
  
  private def isTimeToDie(resource: NeuroticResource): Boolean = {
    new Period(resource.lastRequested, DateTime.now).getMillis() > TimeToLive.toMillis
  }
  
  private def isEligible(lastChecked: Option[DateTime], tolerance: Duration): Boolean = {
    lastChecked match {
      case None => true
      case Some(lastChecked) => {
        // We are pretty tolerant here
      	new Period(lastChecked, DateTime.now()).getMillis() <= (tolerance.toMillis / 2)
      }
    }      
  }
  
  private def addResource(url: URL, remoteAddress: String): Future[NeuroticResult] = {
    val checkResult = check(url, remoteAddress,
        checkMaxResourcesPerClient,
        checkMaxResourcesPerServer,
        checkMaxResourcesTotal)
        
		checkResult match {
      case Some(error) => future { NeuroticResult(None, None, Option(error)) }
      case None => {
        // Everything's fine, add it to the queue ...
        getResource(url, DateTime.now) map { resource =>
          resource.lastError match {
            case Some(error) => NeuroticResult(None, None, Option(error))
            case None => {
              // Add host to the list of servers (if not there yet)
              val queuedServer = addServer(url.getHost)
              
              // Add resource to the server's queue
              queuedServer.addResource(resource)
                 
              // Add to the list of clients
              clients.enqueue((remoteAddress, resource.url))
              
              // Update heartbeat
              updateHeartbeat()
              
              // Return result
              NeuroticResult(None, resource.contents, None)
            }
          }
        }
      }
    }
  }
  
  private def updateHeartbeat(): Unit = {
    // Compute new heartbeat duration
    val newHeartbeat = computeHeartbeat()
    
    if (newHeartbeat != heartbeat) {
      // Save new heartbeat value
      heartbeat = newHeartbeat
      
	    scheduler match {
	      // Cancel old scheduler
	      case Some(c) => c.cancel
	      case None => Unit
	    }
      
      heartbeat match {
        case finiteHeartbeat: FiniteDuration => {	    
			    // Get actor reference
			    val neuroticActor = Akka.system.actorOf(Props[NeuroticActor], name = "neuroticactor")
			    
			    //  Schedule actor
			    scheduler = Option(Akka.system.scheduler.schedule(Duration.Zero, finiteHeartbeat,
			        neuroticActor, NeuroticActor.HeartbeatMsg))
		    }
        case _ => Unit
      }
    }
  }
  
  private def computeHeartbeat(): Duration = {
    servers.size match {
    	case 0 => Duration.Undefined
    	case _ => {
    	  // Get minimal server duration
    	  val minServerDuration = servers map { server =>
    	    MinServerDelay / server.resources.size
  	    } min
  	    
  	    // The more servers, the faster heartbeat
  	    minServerDuration / servers.size
    	}
    }
  }
  
  private def addServer(host: String): QueuedServer = {
    servers.find(qs => qs.server.host == host) match {
      case Some(qs) => qs
      case None => {
        // Create new queue item
        val qs = QueuedServer(NeuroticServer(host, None), new SynchronizedQueue[NeuroticResource])
        
        // Push it to the queue
        servers.enqueue(qs)
        
        qs
      }
    }
  }
  
  private def getResource(url: URL, lastRequested: DateTime): Future[NeuroticResource] = {
    val result = WS.url(url.toString).get().map { response =>
      response.status match {
        case Status.OK => {  
          NeuroticResource(url, Option(getBodyHash(response.body)), DateTime.now, None, lastRequested)
        }
        case _ => NeuroticResource(url, None, DateTime.now, Option(response.statusText), lastRequested)
      }
    }
    
    result onFailure {
      case t => Logger.error("Cannot get resource!", t)
    }
    
    result
  }
  
  private def getBodyHash(body: String): Int = {
    // Well, let's see if this works reliably...
    body.hashCode
  }
  
  private def check(url: URL, remoteAddress: String, checkers: (URL, String) => Option[String]*):
  	Option[String] = {
    checkers flatMap {
      checker => {
        checker(url, remoteAddress) match {
		      case Some(error) => Option(error)
		      case None => None
		    }
      }
    } headOption
  }
  
  private def checkMaxResourcesTotal(url: URL, remoteAddress: String): Option[String] = {
    servers synchronized {
      servers.flatMap(s => s.resources).length >= MaxResourceDelay / MaxHeartbeat match {
        case true => Option(MessageTooBusy)
        case false => None
      }
    }
  }
  
  private def checkMaxResourcesPerServer(url: URL, remoteAddress: String): Option[String] = {
    servers synchronized {
      servers find {
        server => server.server.host == url.getHost
      } match {
        case Some(queuedServer) => {
          queuedServer.resources.length >= MaxResourceDelay / MinServerDelay match {
            case true => Option(MessageTooBusy)
            case false => None
          }
        }
        case None => None
      }
    }
  }
  
  private def checkMaxResourcesPerClient(url: URL, remoteAddress: String): Option[String] = {
    clients synchronized {
	    // Check remote address limit
	    clients.filter(client => client._1 == remoteAddress).length == MaxResourcesPerClient match {
	      case true => Option("I cannot watch more than " + MaxResourcesPerClient + " pages for you.")
	      case false => None
	    }
    }
  }
  
  private def findResource(url: URL): Option[NeuroticResource] = {
    servers synchronized {
	    val allResources = servers flatMap {
	      server => server.resources 
	    }    
	    allResources find {
	      res => res.url == url
	    }
    }
  }
}