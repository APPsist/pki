package de.appsist.service.pki;

import org.vertx.java.core.Handler;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.json.JsonObject;

/**
 * Handler for unsolved HTTP requests. 
 * @author simon.schwantzer(at)im-c.de
 */
public class NotImplementedHandler implements Handler<HttpServerRequest> {
	
	@Override
	public void handle(HttpServerRequest req) {
		req.response().setStatusCode(501);
		JsonObject responseObject = new JsonObject();
		JsonObject errorObject = new JsonObject();
		errorObject.putNumber("code", 501);
		errorObject.putString("message", "The requested method is not implemented.");
		responseObject.putObject( "error", errorObject);
		JsonObject requestObject = new JsonObject();
		requestObject.putString("method", req.method());
		requestObject.putString("path", req.path());
		JsonObject paramsObject = new JsonObject();
		for (String key : req.params().names()) {
			paramsObject.putString(key, req.params().get(key));
		}
		requestObject.putObject("params", paramsObject);
		responseObject.putObject( "request", requestObject);
		req.response().end(responseObject.toString());
	}
}
