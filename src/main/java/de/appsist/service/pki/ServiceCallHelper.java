package de.appsist.service.pki;

import java.util.Collections;
import java.util.Map;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.http.HttpClient;
import org.vertx.java.core.http.HttpClientRequest;
import org.vertx.java.core.http.HttpClientResponse;
import org.vertx.java.core.json.DecodeException;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import de.appsist.commons.process.ServiceCallAnnotation;
import de.appsist.service.pki.exception.UnsolvedReferenceException;
import de.appsist.service.pki.util.EventUtil;

/**
 * Helper to perform service calls.
 * @author simon.schwantzer(at)im-c.de
 */
public class ServiceCallHelper {
	private static final Logger logger = LoggerFactory.getLogger(ServiceCallHelper.class);
	private final Vertx vertx;
	private final JsonObject serviceConfig;
	
	
	/**
	 * Creates a service call helper.
	 * @param vertx Vert.x instance to create HTTP client instances.
	 * @param logger Logger for system information.
	 * @param serviceConfig Service configuration.
	 */
	public ServiceCallHelper(Vertx vertx, JsonObject serviceConfig) {
		this.vertx = vertx;
		this.serviceConfig = serviceConfig;
	}
	
	/**
	 * Performs a service call as specified in a service call annotation.
	 * @param serviceCallAnnotation Service call annotation specifying the call. 
	 * @param combinedStore Data storage containing all runtime data to resolve service call references.
	 * @param outputStore Data storage to store output in.
	 * @param resultHandler Result handler to check execution. May be <code>null</code>.
	 */
	public void performServiceCall(final ServiceCallAnnotation serviceCallAnnotation, Map<String, Object> combinedStore, final Map<String, Object> outputStore, final AsyncResultHandler<Void> resultHandler) {
		String serviceId = serviceCallAnnotation.getService();
		String methodId = serviceCallAnnotation.getMethod();
		StringBuilder pathBuilder = new StringBuilder(200);
		pathBuilder.append(serviceConfig.getString("baseUrl", "/services"));
		pathBuilder.append("/").append(serviceId);
		pathBuilder.append("/").append(methodId);
		final String path = pathBuilder.toString();
		
		JsonObject inputObject;
		Map<String, String> inputMapping = serviceCallAnnotation.getInputMapping();
		Map<String, Object> inputMap;
		try {
			inputMap = EventUtil.resolveReferenceMap(Collections.<String, Object>unmodifiableMap(inputMapping), combinedStore);
			inputObject = new JsonObject(inputMap);
		} catch (UnsolvedReferenceException e) {
			logger.warn("Failed to resolve input parameter to perform service call.", e);
			return;
		}
		
		Handler<HttpClientResponse> clientResponseHandler = new Handler<HttpClientResponse>() {
			
			@Override
			public void handle(final HttpClientResponse response) {
				response.bodyHandler(new Handler<Buffer>() {
					
					@Override
					public void handle(Buffer event) {
						final String body = event.toString();
						logger.debug("Service call to: " + path + " | Response: " + body);
						String outputReference = serviceCallAnnotation.getOutputReference();
						if (outputReference != null) {
							outputStore.put(outputReference, new JsonObject(body).toMap());
						}
						Map<String, String> outputMapping = serviceCallAnnotation.getOutputMapping();
						if (outputMapping != null) try {
							JsonObject responseObject = new JsonObject(body);
							for (String key : outputMapping.keySet()) {
								if (responseObject.containsField(key)) {
									outputStore.put(outputMapping.get(key), responseObject.getField(key));
								}
							}
						} catch (DecodeException e) {
							logger.warn("Failed to decode response.", e);
						}
						if (resultHandler != null) resultHandler.handle(new AsyncResult<Void>() {
							
							@Override
							public boolean succeeded() {
								return response.statusCode() == 200;
							}
							
							@Override
							public Void result() {
								return null;
							}
							
							@Override
							public boolean failed() {
								return !succeeded();
							}
							
							@Override
							public Throwable cause() {
								return failed() ? new Throwable("Failed with status " + response.statusCode() + ": " + body) : null;
							}
						});
					}
				});
			}
		};
		
		HttpClient httpClient = vertx.createHttpClient();
		httpClient.setHost(serviceConfig.getString("host", serviceConfig.getString("host")));
		httpClient.setPort(serviceConfig.getInteger("port", serviceConfig.getInteger("port")));
		httpClient.setSSL(serviceConfig.getBoolean("secure", serviceConfig.getBoolean("secure")));
		HttpClientRequest request;
		switch (serviceCallAnnotation.getHttpMethod()) {
		case GET:
			request = httpClient.get(path, clientResponseHandler);
			break;
		case PUT:
			request = httpClient.put(path, clientResponseHandler);
			break;
		case POST:
		default:
			request = httpClient.post(path, clientResponseHandler);
			break;
		}
		request.end(inputObject.encode());
	}
}
