package de.appsist.service.pki.bpmn;

import java.util.Arrays;
import java.util.Map;

import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import de.appsist.commons.event.AppsistEvent;
import de.appsist.commons.process.ProcessElement;
import de.appsist.commons.process.ProcessInstance;
import de.appsist.commons.process.TriggerAnnotation;
import de.appsist.commons.process.bpmn.elements.BPMNExclusiveGateway;
import de.appsist.commons.process.bpmn.elements.BPMNManualTask;
import de.appsist.commons.process.bpmn.elements.BPMNUserTask;
import de.appsist.commons.process.exception.AmbiguousFlowException;
import de.appsist.service.pki.exception.UnsolvedReferenceException;
import de.appsist.service.pki.util.EventUtil;

/**
 * Handler for triggers controlling the process flow of a BPMN process.
 * This handler reacts on incoming events when a trigger is expected to continue the process flow, e.g., for manual and user tasks and gateways.
 * 
 * @author simon.schwantzer(at)im-c.de
 */
public class BPMNElementTriggerEventHandler implements Handler<Message<JsonObject>> {
	private static final Logger logger = LoggerFactory.getLogger(BPMNElementTriggerEventHandler.class);
	private ProcessInstance processInstance;
	private ProcessElement processElement;
	private TriggerAnnotation triggerAnnotation;
	private Map<String, Object> data;
	
	/**
	 * Creates the handler.
	 * @param processInstance Instance of the process to control process flow.
	 * @param processElement Process element waiting for the trigger.
	 * @param triggerAnnotation Trigger annotation describing the trigger.
	 * @param data Data store to solve references in trigger description.
	 */
	public BPMNElementTriggerEventHandler(ProcessInstance processInstance, ProcessElement processElement, TriggerAnnotation triggerAnnotation, Map<String, Object> data) {
		this.processInstance = processInstance;
		this.processElement = processElement;
		this.triggerAnnotation = triggerAnnotation;
		this.data = data;
	}

	@Override
	public void handle(Message<JsonObject> message) {
		JsonObject body = message.body();
		try {
			AppsistEvent event = de.appsist.commons.util.EventUtil.parseEvent(body.toMap());
			if (EventUtil.doesEventMatch(event, triggerAnnotation, data)) {
				switch (processElement.getType()) {
				// case BPMNCallActivity.ELEMENT_TYPE:
				case BPMNManualTask.ELEMENT_TYPE:
				// case BPMNServiceTask.ELEMENT_TYPE:
				case BPMNUserTask.ELEMENT_TYPE:
					try {
						processInstance.stepForward();
					} catch (AmbiguousFlowException e) {
						logger.warn("Failed to continue process.", e);
					}
					break;
				case BPMNExclusiveGateway.ELEMENT_TYPE:
					BPMNExclusiveGateway gateway = (BPMNExclusiveGateway) processElement;
					String response = (String) event.getPayload().get("response");
					if (response != null) {
						String nextElementId = gateway.getOptions().get(response);
						if (nextElementId != null) {
							processInstance.stepForward(nextElementId);
						} else {
							logger.warn("Failed to select process path. Selected: " + response + ". Options: " + Arrays.toString(gateway.getOptions().entrySet().toArray()));
						}
					} else {
						logger.warn("Received user response without response.");
					}
					break;
				}
			}
		} catch (IllegalArgumentException e) {
			logger.info("Failed to parse event.", e);
		} catch (UnsolvedReferenceException e) {
			logger.warn("Failed to handle event.", e);
		}
	}

}
