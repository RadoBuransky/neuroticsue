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

case class NeuroticResult(hasChanged: Option[Boolean], baseline: Option[Int], error: Option[String])

object NeuroticSueService {
  private type Heartbeat = Long
  
	private case class NeuroticServer(host: String, lastChecked: Heartbeat)
	private case class NeuroticResource(url: URL, contents: Option[Int],
	    lastChecked: DateTime, lastError: Option[String], var lastRequested: Option[DateTime])	
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
        res.lastRequested = Option(DateTime.now)
        
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
  
  private def addResource(url: URL, remoteAddress: String): Future[NeuroticResult] = {
    val checkResult = check(url, remoteAddress,
        checkMaxResourcesPerClient,
        checkMaxResourcesPerServer,
        checkMaxResourcesTotal)
        
		checkResult match {
      case Some(error) => future { NeuroticResult(None, None, Option(error)) }
      case None => {
        // Everything's fine, add it to the queue ...
        getResource(url) map { resource =>
          resource.lastError match {
            case Some(error) => NeuroticResult(None, None, Option(error))
            case None => {
              //TODO: Add to queues, update heartbeat...              
              NeuroticResult(None, resource.contents, None)
            }
          }
        }
      }
    }
  }
  
  private def getResource(url: URL): Future[NeuroticResource] = {
    val result = WS.url(url.toString).get().map { response =>
      response.status match {
        case Status.OK => {  
          NeuroticResource(url, Option(getBodyHash(response.body)), DateTime.now, None, None)
        }
        case _ => NeuroticResource(url, None, DateTime.now, Option(response.statusText), None)
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