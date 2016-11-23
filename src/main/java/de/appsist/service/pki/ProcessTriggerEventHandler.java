package de.appsist.service.pki;

import java.util.Map;

import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import de.appsist.commons.event.AppsistEvent;
import de.appsist.commons.process.ProcessDefinition;
import de.appsist.commons.process.TriggerAnnotation;
import de.appsist.service.pki.exception.UnsolvedReferenceException;
import de.appsist.service.pki.util.EventUtil;

/**
 * Eventbus handler for process triggers.
 * @author simon.schwantzer(at)im-c.de
 */
public class ProcessTriggerEventHandler implements Handler<Message<JsonObject>> {
	private static final Logger logger = LoggerFactory.getLogger(ProcessTriggerEventHandler.class);
	private final TriggerAnnotation triggerAnnotation;
	private final ProcessService processService;
	private final ProcessDefinition processDefinition;
	
	/**
	 * Creates the handler.
	 * @param triggerAnnotation Trigger annotation specifying the event to listen on.
	 * @param processDefinition Process definition to instantiate if triggered.
	 * @param processService Process service to perform the instantiation.
	 */
	public ProcessTriggerEventHandler(TriggerAnnotation triggerAnnotation, ProcessDefinition processDefinition, ProcessService processService) {
		this.triggerAnnotation = triggerAnnotation;
		this.processService = processService;
		this.processDefinition = processDefinition;
	}
	
	/**
	 * Returns the annotation specifying the trigger. 
	 * @return Trigger annotation of a process definition.
	 */
	public TriggerAnnotation getTriggerAnnotation() {
		return triggerAnnotation;
	}

	@Override
	public void handle(Message<JsonObject> message) {
		JsonObject body = message.body();
		try {
			AppsistEvent event = de.appsist.commons.util.EventUtil.parseEvent(body.toMap());
			Map<String, Object> context = body.getObject("context", new JsonObject()).toMap();
			if (EventUtil.doesEventMatch(event, triggerAnnotation, processDefinition.getAnnotations().getLocalDataAnnotations())) {
				processService.instantiateProcess(processDefinition.getId(), body.getString("userId"), event.getSessionId(), context, null);
			}
		} catch (IllegalArgumentException e) {
			logger.info("Failed to parse event.", e);
		} catch (UnsolvedReferenceException e) {
			logger.warn("Failed to handle event.", e);
		}
	}
}
