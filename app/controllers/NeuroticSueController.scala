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

object NeuroticSueController extends Controller {  
  /**
   * GET method to check the URL for any changes compared to the
   * provided baseline. 
   */
  def check(url: String, baseline: Option[String] = None) = Action { implicit request =>
    try {
      val baselineNorm = emptyStringIsNone(baseline)
        
	    // Validate input
      val validationResult = validate(url, baselineNorm);
	    
      validationResult match {
        // Return validation error
      	case Some(validationError) => BadRequest(validationError)
	      
      	// No validation errors
      	case None => {
      	  val parsedUrl = new URL(url)
      	  
      	  baselineNorm match {
      	    // Check URL for changes
      	    case Some(baselineNorm) => {
      	      neuroticToResult(NeuroticSueService.hasChanged(parsedUrl, baselineNorm))
      	    }
      	    
      	    // This is the first call, so get the baseline
      	    case None => Async {
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
        BadRequest("Something really bad has happened! Sorry for that.")
      }
    }
  }
  
  private def emptyStringIsNone(string: Option[String]): Option[String] = {
    string match {
      case None => None
      case Some(str) => str.isEmpty match {
        case true => None
        case _ => Option(str)
      }
    }
  }
  
  private def neuroticResultToJson(neuroticResult: NeuroticResult): JsObject = {
    Json.obj("hasChanged" -> neuroticResult.hasChanged,
        "baseline" -> neuroticResult.baseline,
        "error" -> neuroticResult.error)
  }
  
  private def neuroticToResult(neuroticResult: NeuroticResult): Result = {
    Ok(neuroticResultToJson(neuroticResult))
  }
    
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