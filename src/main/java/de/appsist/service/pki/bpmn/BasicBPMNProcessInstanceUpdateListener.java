package de.appsist.service.pki.bpmn;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import de.appsist.commons.event.AppsistEvent;
import de.appsist.commons.event.AppsistEvent.Type;
import de.appsist.commons.event.CallActivityEvent;
import de.appsist.commons.event.ManualTaskEvent;
import de.appsist.commons.event.ProcessCancelledEvent;
import de.appsist.commons.event.ProcessCompleteEvent;
import de.appsist.commons.event.ProcessErrorEvent;
import de.appsist.commons.event.ProcessStartEvent;
import de.appsist.commons.event.ProcessTerminateEvent;
import de.appsist.commons.event.ServiceTaskEvent;
import de.appsist.commons.event.TaskEvent;
import de.appsist.commons.event.UserTaskEvent;
import de.appsist.commons.process.Annotations;
import de.appsist.commons.process.EventAnnotation;
import de.appsist.commons.process.ProcessCallingElement;
import de.appsist.commons.process.ProcessDefinition;
import de.appsist.commons.process.ProcessElement;
import de.appsist.commons.process.ProcessElementInstance;
import de.appsist.commons.process.ProcessInstance;
import de.appsist.commons.process.ProcessInstanceUpdateListener;
import de.appsist.commons.process.ServiceCallAnnotation;
import de.appsist.commons.process.TriggerAnnotation;
import de.appsist.commons.process.bpmn.elements.BPMNManualTask;
import de.appsist.commons.process.bpmn.elements.BPMNServiceTask;
import de.appsist.commons.process.bpmn.elements.BPMNTask;
import de.appsist.commons.process.bpmn.elements.BPMNUserTask;
import de.appsist.commons.process.exception.AmbiguousFlowException;
import de.appsist.service.pki.ResultAggregationHandler;
import de.appsist.service.pki.ServiceCallHelper;
import de.appsist.service.pki.ServiceCallTriggerHandler;
import de.appsist.service.pki.exception.UnsolvedReferenceException;
import de.appsist.service.pki.util.EventUtil;

/**
 * Listener for process instance state changes.
 * This listener is used by the {@link BasicBPMNProcessService} and responsible for
 * (1) performing the process and
 * (2) handle actions indicated by process annotations
 * 
 * @author simon.schwantzer(at)im-c.de
 */
public class BasicBPMNProcessInstanceUpdateListener implements ProcessInstanceUpdateListener {
	private static final Logger logger = LoggerFactory.getLogger(BasicBPMNProcessInstanceUpdateListener.class);
	private ProcessInstance processInstance;
	private final Map<String, BPMNElementTriggerEventHandler> processFlowTriggers;
	private final Vertx vertx;
	private final ServiceCallHelper serviceCallHelper;
	private final List<ServiceCallTriggerHandler> serviceCallTriggerHandlers, localServiceCallTriggerHandlers;
	private Date creationTime;
	private Date completionTime;
	
	/**
	 * Creates a new update listener.
	 * @param processInstance Process instance this listener is registered for.
	 * @param processService Process service object. Used to instantiate processes when call activities are called.
	 * @param vertx Vertx instance to access the event bus and HTTP client instances.
	 * @param serviceCallHelper Helper to perform service calls.
	 */
	public BasicBPMNProcessInstanceUpdateListener(ProcessInstance processInstance, Vertx vertx, ServiceCallHelper serviceCallHelper) {
		this.processInstance = processInstance;
		this.processFlowTriggers = new HashMap<String, BPMNElementTriggerEventHandler>();
		this.vertx = vertx;
		this.serviceCallHelper = serviceCallHelper;
		this.serviceCallTriggerHandlers = new ArrayList<>();
		this.localServiceCallTriggerHandlers = new ArrayList<>();
	}
	
	/**
	 * Publishes an event on the event bus.
	 * @param event Event to publish.
	 */
	private void publishEvent(String prefix, AppsistEvent event) {
		JsonObject eventObject = new JsonObject(event.asMap());
		vertx.eventBus().publish(prefix + event.getModelId(), eventObject);
	}
	
	/**
	 * Generates a task event to be published during process execution.
	 * This method can be used for arbitrary BPMN task types.
	 * @param taskElement Task to announce.
	 * @return Event to publish.
	 */
	private AppsistEvent generateTaskEvent(BPMNTask taskElement) {
		String id = UUID.randomUUID().toString();
		String processId = processInstance.getProcessDefinition().getId();
		String processInstanceId = processInstance.getId();
		String elementId = taskElement.getId();
		String taskTitle = taskElement.getLabel();
		String taskDescription = taskElement.getDescription();
		String userId = processInstance.getUserId();
		String sessionId = processInstance.getSessionId();
		
		TaskEvent event;
		switch (taskElement.getType()) {
		case BPMNManualTask.ELEMENT_TYPE:
			event = new ManualTaskEvent(id, processId, processInstanceId, elementId, taskTitle);
			break;
		case BPMNServiceTask.ELEMENT_TYPE:
			event = new ServiceTaskEvent(id, processId, processInstanceId, elementId);
			event.setTaskTitle(taskTitle);
			break;
		case BPMNUserTask.ELEMENT_TYPE:
			event = new UserTaskEvent(id, processId, processInstanceId, elementId, taskTitle);
			break;
		default:
			throw new IllegalArgumentException("Invalid task element: " + taskElement.getType());
		}
		if (taskDescription != null) event.setTaskDescription(taskDescription);
		if (userId != null) event.setUserId(userId);
		if (sessionId != null) event.setSessionId(sessionId);

		event.setProgress(calculateProgress());
		event.setRootProcessId(processInstance.getTopMostParent().getProcessDefinition().getId());
		
		return event;
	}
	
	private double calculateProgress() {
		ProcessInstance topMostInstance = processInstance.getTopMostParent();
		ProcessDefinition processDefinition = topMostInstance.getProcessDefinition();
		ProcessElement currentElement = topMostInstance.getCurrentState().getProcessElement();
		int distanceFromStart = processDefinition.getDistanceFromStart(currentElement);
		int distanceToEnd = processDefinition.getMaxDistanceToEnd(currentElement);
		return (double) distanceFromStart / (distanceFromStart + distanceToEnd);
	}
	
	@Override
	public void start() {
		ProcessDefinition processDefinition = processInstance.getProcessDefinition();
		Map<String, Object> contextData = processInstance.getProcessInstanceContext();
		Map<String, Object> processData = processDefinition.getAnnotations().getLocalDataAnnotations();
		Map<String, Object> combinedStore = EventUtil.combineMaps(contextData, processData);
		
		creationTime = new Date();
		
		@SuppressWarnings("unchecked")
		Map<String, Object> executionInfoMap = (Map<String, Object>) contextData.get("executionInfo");
		JsonObject executionInfo = executionInfoMap != null ? new JsonObject(executionInfoMap) : new JsonObject();
		executionInfo.putString("started", EventUtil.getDateAsISOString(creationTime));
		processInstance.getProcessInstanceContext().put("executionInfo", executionInfo.toMap());
		
		String id = UUID.randomUUID().toString();
		String userId = processInstance.getUserId();
		String sessionId = processInstance.getSessionId();
		ProcessStartEvent event = new ProcessStartEvent(id, processDefinition.getId(), processInstance.getId());
		if (userId != null) event.setUserId(userId);
		if (sessionId != null) event.setSessionId(sessionId);
		ProcessInstance parent = processInstance.getParent(); 
		if (parent != null) event.setParentInstance(parent.getId());
		event.setProgress(0.0d);
		publishEvent("appsist:event:", event);
		
		for (EventAnnotation eventAnnotation : processDefinition.getAnnotations().getEventAnnotations()) {
			if (eventAnnotation.getType() == EventAnnotation.Type.START) {
				try {
					Map<String, Object> payload = EventUtil.resolveReferenceMap(eventAnnotation.getProperties(), combinedStore);
					AppsistEvent newEvent = new AppsistEvent(UUID.randomUUID().toString(), eventAnnotation.getEventId(), Type.SERVICE, payload);
					if (sessionId != null) newEvent.setSessionId(sessionId);
					publishEvent("appsist:event:", newEvent);
				} catch (UnsolvedReferenceException e) {
					logger.warn("Failed to publish event on process start: " + processDefinition.getId() + " -> " + eventAnnotation.getEventId(), e);
				}
			}
		}
		
		for (ServiceCallAnnotation serviceCallAnnotation : processDefinition.getAnnotations().getServiceCallAnnotations()) {
			switch (serviceCallAnnotation.getType()) {
			case START:
				serviceCallHelper.performServiceCall(serviceCallAnnotation, combinedStore, contextData, null);
				break;
			case TRIGGER:
				ServiceCallTriggerHandler triggerHandler = new ServiceCallTriggerHandler(serviceCallAnnotation, combinedStore, contextData, serviceCallHelper);
				TriggerAnnotation triggerAnnotation = serviceCallAnnotation.getTrigger();
				serviceCallTriggerHandlers.add(triggerHandler);
				vertx.eventBus().registerHandler("appsist:event:" + triggerAnnotation.getEventId(), triggerHandler);
				break;
			default:
				// do nothing for END here
			}
		}
		
		try {
			processInstance.stepForward();
		} catch (AmbiguousFlowException e) {
			logger.warn("Failed to proceed process instance.", e);
		}
	}

	@Override
	public void end() {
		ProcessDefinition processDefinition = processInstance.getProcessDefinition();
		Map<String, Object> contextData = processInstance.getProcessInstanceContext();
		Map<String, Object> processData = processDefinition.getAnnotations().getLocalDataAnnotations();
		Map<String, Object> combinedStore = EventUtil.combineMaps(contextData, processData);
		
		completionTime = new Date();
		@SuppressWarnings("unchecked")
		Map<String, Object> executionInfoMap = (Map<String, Object>) contextData.get("executionInfo");
		JsonObject executionInfo = executionInfoMap != null ? new JsonObject(executionInfoMap) : new JsonObject();
		executionInfo.putString("ended", EventUtil.getDateAsISOString(completionTime));
		processInstance.getProcessInstanceContext().put("executionInfo", executionInfo.toMap());
		
		String id = UUID.randomUUID().toString();
		String userId = processInstance.getUserId();
		String sessionId = processInstance.getSessionId();
		ProcessCompleteEvent event = new ProcessCompleteEvent(id, processDefinition.getId(), processInstance.getId());
		if (userId != null) event.setUserId(userId);
		if (sessionId != null) event.setSessionId(sessionId);
		ProcessInstance parent = processInstance.getParent(); 
		if (parent != null) event.setParentInstance(parent.getId());
		event.setProgress(1.0d);
		publishEvent("appsist:event:", event);
		
		for (EventAnnotation eventAnnotation : processDefinition.getAnnotations().getEventAnnotations()) {
			if (eventAnnotation.getType() == EventAnnotation.Type.END) {
				try {
					Map<String, Object> payload = EventUtil.resolveReferenceMap(eventAnnotation.getProperties(), combinedStore);
					AppsistEvent newEvent = new AppsistEvent(UUID.randomUUID().toString(), eventAnnotation.getEventId(), Type.SERVICE, payload);
					if (sessionId != null) newEvent.setSessionId(sessionId);
					publishEvent("appsist:event:", newEvent);
				} catch (UnsolvedReferenceException e) {
					logger.warn("Failed to publish event on process end: " + processDefinition.getId() + " -> " + eventAnnotation.getEventId(), e);
				}
			}
		}
		
		for (ServiceCallAnnotation serviceCallAnnotation : processDefinition.getAnnotations().getServiceCallAnnotations()) {
			switch (serviceCallAnnotation.getType()) {
			case END:
				serviceCallHelper.performServiceCall(serviceCallAnnotation, combinedStore, contextData, null);
				break;
			default:
				// do nothing
			}
		}
		
		for (ServiceCallTriggerHandler triggerHandler : serviceCallTriggerHandlers) {
			String channel = "appsist:event:" + triggerHandler.getServiceCallAnnotation().getTrigger().getEventId();
			vertx.eventBus().unregisterHandler(channel, triggerHandler);
		}
		serviceCallTriggerHandlers.clear();
		
		try {
			processInstance.stepForward();
		} catch (AmbiguousFlowException e) {
			logger.warn("Failed to proceed process instance.", e);
		}
		
		try {
			processInstance.stepForward();
		} catch (AmbiguousFlowException e) {
			logger.warn("Failed to end process.", e);
		}
	}
	
	@Override
	public void terminate() {
		@SuppressWarnings("unchecked")
		Map<String, Object> executionInfoMap = (Map<String, Object>) processInstance.getProcessInstanceContext().get("executionInfo");
		JsonObject executionInfo = executionInfoMap != null ? new JsonObject(executionInfoMap) : new JsonObject();
		completionTime = new Date();
		executionInfo.putString("terminated", EventUtil.getDateAsISOString(completionTime));
		processInstance.getProcessInstanceContext().put("executionInfo", executionInfo.toMap());
		
		String id = UUID.randomUUID().toString();
		String processId = processInstance.getProcessDefinition().getId();
		String processInstanceId = processInstance.getId();
		String userId = processInstance.getUserId();
		String sessionId = processInstance.getSessionId();
		ProcessInstance parent = processInstance.getParent(); 
		
		ProcessTerminateEvent event = new ProcessTerminateEvent(id, processId, processInstanceId);
		if (userId != null) event.setUserId(userId);
		if (sessionId != null) event.setSessionId(sessionId);
		if (parent != null) event.setParentInstance(parent.getId());
		event.setProgress(1.0d);
		publishEvent("appsist:event:", event);
		
		try {
			processInstance.stepForward();
		} catch (AmbiguousFlowException e) {
			logger.warn("Failed to terminate process.", e);
		}
		
		if (parent != null) {
			parent.terminate();
		}
	}
	
	@Override
	public void cancel() {
		ProcessDefinition processDefinition = processInstance.getProcessDefinition();
		Map<String, Object> contextData = processInstance.getProcessInstanceContext();
		
		completionTime = new Date();
		@SuppressWarnings("unchecked")
		Map<String, Object> executionInfoMap = (Map<String, Object>) contextData.get("executionInfo");
		JsonObject executionInfo = executionInfoMap != null ? new JsonObject(executionInfoMap) : new JsonObject();
		executionInfo.putString("cancelled", EventUtil.getDateAsISOString(completionTime));
		processInstance.getProcessInstanceContext().put("executionInfo", executionInfo.toMap());
		
		String id = UUID.randomUUID().toString();
		String userId = processInstance.getUserId();
		String sessionId = processInstance.getSessionId();
		ProcessCancelledEvent event = new ProcessCancelledEvent(id, processDefinition.getId(), processInstance.getId());
		if (userId != null) event.setUserId(userId);
		if (sessionId != null) event.setSessionId(sessionId);
		ProcessInstance parent = processInstance.getParent(); 
		if (parent != null) event.setParentInstance(parent.getId());
		event.setProgress(1.0d);
		publishEvent("appsist:event:", event);
		
		if (parent != null) {
			parent.terminate();
		}
	}
	
	@Override
	public void error(ProcessElement element, int code, String message) {
		@SuppressWarnings("unchecked")
		Map<String, Object> executionInfoMap = (Map<String, Object>) processInstance.getProcessInstanceContext().get("executionInfo");
		JsonObject executionInfo = executionInfoMap != null ? new JsonObject(executionInfoMap) : new JsonObject();
		JsonObject error = new JsonObject();
		error.putString("elementId", element.getId());
		error.putString("time", EventUtil.getDateAsISOString(new Date()));
		error.putNumber("code", code);
		error.putString("message", message);
		executionInfo.putObject("error", error);
		processInstance.getProcessInstanceContext().put("executionInfo", executionInfo.toMap());
		
		String id = UUID.randomUUID().toString();
		String processId = processInstance.getProcessDefinition().getId();
		String processInstanceId = processInstance.getId();
		String elementId = element.getId();
		String userId = processInstance.getUserId();
		String sessionId = processInstance.getSessionId();
		ProcessInstance parent = processInstance.getParent(); 
		
		ProcessErrorEvent event = new ProcessErrorEvent(id, processId, processInstanceId, elementId, code, message);
		if (userId != null) event.setUserId(userId);
		if (sessionId != null) event.setSessionId(sessionId);
		if (parent != null) event.setParentInstance(parent.getId());
		event.setProgress(1.0d);
		publishEvent("appsist:event:", event);
		
		try {
			processInstance.stepForward();
		} catch (AmbiguousFlowException e) {
			logger.warn("Failed to end process.", e);
		}
	}

	@Override
	public void activityCalled(ProcessCallingElement caller) {
		String id = UUID.randomUUID().toString();
		String processId = processInstance.getProcessDefinition().getId();
		String processInstanceId = processInstance.getId();
		String elementId = caller.getId();
		String userId = processInstance.getUserId();
		String sessionId = processInstance.getSessionId();
		String activityTitle = caller.getLabel();
		String activityProcessId = caller.getCalledProcess();
		
		CallActivityEvent event = new CallActivityEvent(id, processId, processInstanceId, elementId, activityProcessId);
		
		if (activityTitle != null) event.setActvitiyTitle(activityTitle);
		if (userId != null) event.setUserId(userId);
		if (sessionId != null) event.setSessionId(sessionId);
		event.setProgress(calculateProgress());
		event.setRootProcessId(processInstance.getTopMostParent().getProcessDefinition().getId());
		
		publishEvent("appsist:event:", event);
	}

	@Override
	public void stepPerformed(ProcessElementInstance oldState, ProcessElementInstance newState) {
		Map<String, Object> contextData = processInstance.getProcessInstanceContext();
		Map<String, Object> processData = processInstance.getProcessDefinition().getAnnotations().getLocalDataAnnotations();
		
		if (oldState != null) {
			// Unregister old event flow triggers.
			for (String channel : processFlowTriggers.keySet()) {
				vertx.eventBus().unregisterHandler(channel, processFlowTriggers.get(channel));
			}
			processFlowTriggers.clear();
			
			ProcessElement processElement = oldState.getProcessElement();
			Annotations annotations = processElement.getAnnotations();
			Map<String, Object> elementData = annotations.getLocalDataAnnotations();
			// Combine data from context, element, and process in this priority.
			Map<String, Object> combinedData = EventUtil.combineMaps(contextData, elementData, processData);

			// Perform all "onEnd" service calls.
			for (ServiceCallAnnotation serviceCallAnnotation : annotations.getServiceCallAnnotations()) {
				if (serviceCallAnnotation.getType() == ServiceCallAnnotation.Type.END) {
					serviceCallHelper.performServiceCall(serviceCallAnnotation, combinedData, contextData, null);
				}
			}
			
			for (ServiceCallTriggerHandler triggerHandler: localServiceCallTriggerHandlers) {
				String channel = "appsist:event:" + triggerHandler.getServiceCallAnnotation().getTrigger().getEventId();
				vertx.eventBus().unregisterHandler(channel, triggerHandler);
			}
			localServiceCallTriggerHandlers.clear();

			// Publish all "onEnd" events.
			for (EventAnnotation eventAnnotation : annotations.getEventAnnotations()) {
				if (eventAnnotation.getType() == EventAnnotation.Type.END) {
					try {
						Map<String, Object> payload = EventUtil.resolveReferenceMap(eventAnnotation.getProperties(), combinedData);
						AppsistEvent newEvent = new AppsistEvent(UUID.randomUUID().toString(), eventAnnotation.getEventId(), Type.SERVICE, payload);
						String sessionId = processInstance.getSessionId();
						if (sessionId != null) newEvent.setSessionId(sessionId);
						publishEvent("appsist:event:", newEvent);
					} catch (UnsolvedReferenceException e) {
						logger.warn("Failed to generate event during process execution.", e);
					}
				}
			}
		}
		if (newState != null) {
			ProcessElement processElement = newState.getProcessElement();
			boolean proceed = false;
			@SuppressWarnings("unchecked")
			Map<String, Object> executionInfoMap = (Map<String, Object>) processInstance.getProcessInstanceContext().get("executionInfo");
			JsonObject executionInfo = executionInfoMap != null ? new JsonObject(executionInfoMap) : new JsonObject();
			AppsistEvent event;
			switch (processElement.getType()) {
			case BPMNServiceTask.ELEMENT_TYPE:
				proceed = true;
			case BPMNManualTask.ELEMENT_TYPE:
			case BPMNUserTask.ELEMENT_TYPE:
				event = generateTaskEvent((BPMNTask) processElement);
				publishEvent("appsist:event:", event);
				break;
			}
			processInstance.getProcessInstanceContext().put("executionInfo", executionInfo.toMap());

			final Annotations annotations = processElement.getAnnotations();
			Map<String, Object> elementData = annotations.getLocalDataAnnotations();
			final Map<String, Object> combinedData = EventUtil.combineMaps(contextData, elementData, processData);
			Set<ServiceCallAnnotation> serviceCallsOnStart = new HashSet<ServiceCallAnnotation>();
			Set<ServiceCallAnnotation> serviceCallsOnTrigger = new HashSet<ServiceCallAnnotation>();
			for (ServiceCallAnnotation serviceCallAnnotation : annotations.getServiceCallAnnotations()) {
				switch (serviceCallAnnotation.getType()) {
				case START:
					serviceCallsOnStart.add(serviceCallAnnotation);
					break;
				case TRIGGER:
					serviceCallsOnTrigger.add(serviceCallAnnotation);
					break;
				default:
					// later
				}
			}
			ResultAggregationHandler<ServiceCallAnnotation> resultAggregation = new ResultAggregationHandler<>(serviceCallsOnStart, new AsyncResultHandler<Void>() {

				@Override
				public void handle(AsyncResult<Void> requests) {
					if (requests.failed()) {
						logger.warn("Failed to perform one or more service calls onStart.", requests.cause());
					}
					for (EventAnnotation eventAnnotation : annotations.getEventAnnotations()) {
						if (eventAnnotation.getType() == EventAnnotation.Type.START) {
							try {
								Map<String, Object> payload = EventUtil.resolveReferenceMap(eventAnnotation.getProperties(), combinedData);
								AppsistEvent newEvent = new AppsistEvent(UUID.randomUUID().toString(), eventAnnotation.getEventId(), Type.SERVICE, payload);
								String sessionId = processInstance.getSessionId();
								if (sessionId != null) newEvent.setSessionId(sessionId);
								publishEvent("appsist:event:", newEvent);
							} catch (UnsolvedReferenceException e) {
								logger.warn("Failed to generate event during process execution.", e);
							}
						}
					}
				}
			});
			for (ServiceCallAnnotation serviceCall : serviceCallsOnStart) {
				serviceCallHelper.performServiceCall(serviceCall, combinedData, contextData, resultAggregation.getRequestHandler(serviceCall));
			}
			
			for (ServiceCallAnnotation serviceCallAnnotation : serviceCallsOnTrigger) {
				ServiceCallTriggerHandler triggerHandler = new ServiceCallTriggerHandler(serviceCallAnnotation, combinedData, contextData, serviceCallHelper);
				TriggerAnnotation triggerAnnotation = serviceCallAnnotation.getTrigger();
				localServiceCallTriggerHandlers.add(triggerHandler);
				vertx.eventBus().registerHandler("appsist:event:" + triggerAnnotation.getEventId(), triggerHandler);
			}
			
			for (TriggerAnnotation triggerAnnotation : annotations.getTriggerAnnotations()) {
				String eventBusChannel = "appsist:event:" + triggerAnnotation.getEventId();
				BPMNElementTriggerEventHandler triggerHandler = new BPMNElementTriggerEventHandler(processInstance, processElement, triggerAnnotation, combinedData);
				
				processFlowTriggers.put(eventBusChannel, triggerHandler);
				vertx.eventBus().registerHandler(eventBusChannel, triggerHandler); 
			}
			if (proceed) {
				try {
					processInstance.stepForward();
				} catch (AmbiguousFlowException e) {
					logger.warn("Failed to proceed process instance.", e);
				}
			}
		}
	}
	
	/**
	 * Returns the time when the process instance was started.
	 * @return Time the start event was executed. May be <code>null</code> (not started yet).
	 */
	public Date getCreationTime() {
		return creationTime;
	}
	
	/**
	 * Returns the time when the process instance was completed.
	 * @return Time the end event was executed. May be <code>null</code> (not ended yet).
	 */
	public Date getCompletionTime() {
		return completionTime;
	}
}