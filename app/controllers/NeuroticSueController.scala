package controllers

import play.api._
import play.api.mvc._
import java.net.URL
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import play.api.libs.json.JsResult
import play.api.libs.json.JsError
import models.NeuroticSueService

object NeuroticSueController extends Controller {  
  /**
   * GET method to check the URL for any changes compared to the
   * provided baseline. 
   */
  def check(url: String, baseline: Option[String] = None) = Action { implicit request =>
    try {
	    // Validate input
      val validationResult = validate(url, baseline);
	    
      validationResult match {
        // Return validation error
      	case Some(validationError) => BadRequest(validationError)
	      
      	// No validation errors
      	case None => {
      	  val parsedUrl = new URL(url)
      	  
      	  val neuroticResult = baseline match {
      	    // Check URL for changes
      	    case Some(baseline) => NeuroticSueService.hasChanged(parsedUrl, baseline)
      	    
      	    // This is the first call, so get the baseline
      	    case None => NeuroticSueService.getBaseline(parsedUrl, request.remoteAddress)
      	  }
      	  
      	  // Serialize to JSON and return result
      	  Ok
      	}
	    }
    }
    catch {
      case ex: Throwable => {
        Logger.error("Oh shit! [" + url + ", " + baseline + "]", ex)
        BadRequest("Something really bad has happened! Sorry for that.")
      }
    }
  }
  
//  private def neuroticResultToJson(neuroticResult: NeuroticResult): JsObject = {
//    Json.obj("hasChanged" -> neuroticResult.hasChanged,
//        "baseline" -> neuroticResult.baseline)
//  }
    
  private def validate(url: String, baseline: Option[String]): Option[String] = {
    if (url == null || url.isEmpty()) {
      Some("URL is required!")
    }
    else {
      try {
      	val parsedUrl = new URL(url)
      	None
      }
      catch {
        case ex: Exception => Some(ex.getMessage())
      }
    }
  }
}