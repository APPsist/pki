package de.appsist.service.pki;

import java.util.Map;

import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import de.appsist.commons.event.AppsistEvent;
import de.appsist.commons.process.ServiceCallAnnotation;
import de.appsist.service.pki.exception.UnsolvedReferenceException;
import de.appsist.service.pki.util.EventUtil;

/**
 * Event bus handler for service call triggers.
 * @author simon.schwantzer(at)im-c.de
 */
public class ServiceCallTriggerHandler implements Handler<Message<JsonObject>> {
	private static final Logger logger = LoggerFactory.getLogger(ServiceCallTriggerHandler.class);
	private final ServiceCallAnnotation serviceCallAnnotation;
	private final Map<String, Object> dataStore, outputStore;
	private final ServiceCallHelper serviceCallHelper;
	
	/**
	 * Creates the handler.
	 * @param serviceCallAnnotation Service call annotation observed by this handler.
	 * @param dataStore Data store to retrieve data for the comparison with incoming events.
	 * @param outputStore Data store to store output from service call.
	 * @param serviceCallHelper Helper to perform service calls.
	 */
	public ServiceCallTriggerHandler(ServiceCallAnnotation serviceCallAnnotation, Map<String, Object> dataStore, Map<String, Object> outputStore, ServiceCallHelper serviceCallHelper) {
		this.serviceCallAnnotation = serviceCallAnnotation;
		this.dataStore = dataStore;
		this.outputStore = outputStore;
		this.serviceCallHelper = serviceCallHelper;
	}
	
	/**
	 * Returns the service call observed by this handler.
	 * @return Service call annotation.
	 */
	public ServiceCallAnnotation getServiceCallAnnotation() {
		return serviceCallAnnotation;
	}

	@Override
	public void handle(Message<JsonObject> message) {
		JsonObject body = message.body();
		try {
			AppsistEvent event = de.appsist.commons.util.EventUtil.parseEvent(body.toMap());
			if (EventUtil.doesEventMatch(event, serviceCallAnnotation.getTrigger(), dataStore)) {
				serviceCallHelper.performServiceCall(serviceCallAnnotation, dataStore, outputStore, null);
			}
		} catch (IllegalArgumentException e) {
			logger.info("Failed to parse event.", e);
		} catch (UnsolvedReferenceException e) {
			logger.warn("Failed to handle event.", e);
		}
	}
}
