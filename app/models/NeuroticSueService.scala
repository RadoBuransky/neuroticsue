package models

import java.net.URL
import org.joda.time.DateTime
import scala.collection.mutable.Queue

case class NeuroticResult(hasChanged: Boolean, baseline: String, nextCheckAfter: Int)

object NeuroticSueService {
	private case class NeuroticResource(url: String, baseline: String, lastChecked: DateTime)
	
	// Minimal duration between two checks for the same server (in seconds)
	private val MinServerDelay = 10
	
	// Minimal duration between two checks for the same URL (in seconds)
	private val MinResourceDelay = 60
	
	// Cleanup resource after 5 minutes
	private val TimeToLive = 300

  // Map of server host name -> queue of resources on that server to be checked
  private val servers = collection.mutable.Map[String, Queue[NeuroticResource]]()
  
  def hasChanged(url: URL, baseline: String): NeuroticResult = {
    require(url != null, "url is required!")
    require(baseline != null, "baseline is required!")
    
    hasChanged(url, Some(baseline))
  }
  
  def getBaseline(url: URL): NeuroticResult = {
    require(url != null, "url is required!")
    
    hasChanged(url, None)
  }
  
  private def hasChanged(url: URL, baseline: Option[String]): NeuroticResult = {    
    // Try to get it from existing list of resources
    resources.get(url.toString) match {
      // We have this URL in our map already
    	case Some(contents) => {
      }
      
    	// This is a new URL, we haven't checked it yet
      case None => {
        baseline match {
          // This doesn't make much sense
          case Some(baselineVal) => {
            throw new IllegalStateException("Baseline is probably too old!")
          }
          
          case None => {
		        // Get contents of the resource
		        val contents = get(url)
		        
		        // Put contents to the map
		        resources.put(url.toString, NeuroticResourceContents(contents, DateTime.now))
		        
		        // Update    
    				NeuroticResult(false, contents)            
          }
        }
      }
    }
    
  }
  
  private def get(url: URL): String = {
    ""
  }
  
  private def cleanup = {
    
  }
}