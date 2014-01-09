package controllers

import play.api._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc._
import java.net.URL
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import play.api.libs.json.JsResult
import play.api.libs.json.JsError
import models.NeuroticSueService
import models.NeuroticResult
import java.net.URLDecoder
import scala.concurrent.Future

object NeuroticSueController extends Controller {  
  /**
   * GET method to check the URL for any changes compared to the
   * provided baseline. 
   */
  def check(url: Option[String], baseline: Option[Int] = None) = Action.async { implicit request =>
    try {
	    // Validate input
      val validationResult = validate(url, baseline);
	    
      validationResult match {
        // Return validation error
      	case Some(validationError) => Future(BadRequest(validationError))
	      
      	// No validation errors
      	case None => {
      	  val parsedUrl = new URL(URLDecoder.decode(url.get, "UTF-8"))
      	  
      	  baseline match {
      	    // Check URL for changes
      	    case Some(baseline) => {
              Future(neuroticToResult(NeuroticSueService.hasChanged(parsedUrl, baseline)))
      	    }
      	    
      	    // This is the first call, so get the baseline
      	    case None => {
      	      NeuroticSueService.getBaseline(parsedUrl, request.remoteAddress) map { neuroticResult =>
      	        neuroticToResult(neuroticResult)
    	        }
            }
      	  }
      	}
	    }
    }
    catch {
      case ex: Throwable => {
        Logger.error("Oh shit! [" + url + ", " + baseline + "]", ex)
        Future(BadRequest("Something really bad has happened! Sorry for that."))
      }
    }
  }
  
  private def neuroticResultToJson(neuroticResult: NeuroticResult): JsObject = {
    Json.obj("hasChanged" -> neuroticResult.hasChanged,
        "baseline" -> neuroticResult.baseline,
        "error" -> neuroticResult.error)
  }
  
  private def neuroticToResult(neuroticResult: NeuroticResult): SimpleResult = {
    Ok(neuroticResultToJson(neuroticResult))
  }
    
  private def validate(url: Option[String], baseline: Option[Int]): Option[String] = {
    url match {
      case Some(urlValue) => {
        try {
          if (!urlValue.isEmpty) {
		      	val parsedUrl = new URL(URLDecoder.decode(urlValue, "UTF-8"))
		      	return None
          }
	      }
	      catch {
	        case ex: Exception => return Some(ex.getMessage())
	      }
      }
      case None =>
    }
    return Some("URL is required!")
  }
}