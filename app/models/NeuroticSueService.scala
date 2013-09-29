package models

import java.net.URL
import org.joda.time.DateTime
import scala.collection.mutable.Queue
import scala.Mutable
import scala.collection.mutable.SynchronizedQueue

case class NeuroticResult(hasChanged: Option[Boolean], baseline: Option[String], error: Option[String])

object NeuroticSueService {
  private type Heartbeat = Long
  
	private case class NeuroticServer(host: String, lastChecked: Heartbeat)
	private case class NeuroticResource(url: URL, contents: String,
	    lastChecked: Heartbeat, lastError: Option[String], var lastRequested: DateTime)	
  private case class QueuedServer(server: NeuroticServer, resources: Queue[NeuroticResource])
	
	// Minimal duration between two checks for the same server (in seconds)
	private val MinServerDelay = 10
	
	// Minimal duration between two checks for the same URL (in seconds)
	private val MinResourceDelay = 60
	
	// Maximal duration between two checks for the same URL (in seconds)
	private val MaxResourceDelay = 120
	
	// Maximal heartbeat (in seconds) 
	private val MaxHeartbeat = 1
	
	// Cleanup resource after 5 minutes
	private val TimeToLive = 300
	
	// Maximal number of resources allowed for a single client
	private val MaxResourcesPerClient = 2
	
	private val MessageTooBusy = "Sorry, I am too busy now. Try again later."

  // Queue of servers with queues of resources
  private val servers = new SynchronizedQueue[QueuedServer]
  
  // Queue of clients (remote address -> URL)
  private val clients = new SynchronizedQueue[(String, URL)]
  
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
        
        NeuroticResult(hasChanged, Option(res.contents), res.lastError)
      }
      case None => throw new IllegalStateException("URL is not in queued!")
    }
  }
  
  def getBaseline(url: URL, remoteAddress: String): NeuroticResult = {
    require(url != null, "url is required!")
    
    findResource(url) match {
      case Some(res) => NeuroticResult(None, Option(res.contents), None)
      case None => addResource(url, remoteAddress)
    }
  }
  
  private def addResource(url: URL, remoteAddress: String): NeuroticResult = {
    val checkResult = check(url, remoteAddress,
        checkMaxResourcesPerClient,
        checkMaxResourcesPerServer,
        checkMaxResourcesTotal)
        
		checkResult match {
      case Some(error) => NeuroticResult(None, None, Option(error))
      case None => {
        // Everything's fine, add it to the queue ...
        //TODO: ...
        NeuroticResult(Option(false), None, None)
      }
    }
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