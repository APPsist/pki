package de.appsist.service.pki.bpmn;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.vertx.java.core.Vertx;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import de.appsist.commons.process.ProcessDefinition;
import de.appsist.commons.process.ProcessInstance;
import de.appsist.commons.process.TriggerAnnotation;
import de.appsist.commons.process.bpmn.BPMNProcessDefinition;
import de.appsist.service.pki.ProcessService;
import de.appsist.service.pki.ProcessTriggerEventHandler;
import de.appsist.service.pki.ServiceCallHelper;

/**
 * Basic process service for BPMN processes.
 * This version of a process service executes processes locally with the build-in BPMN engine.
 * 
 * @author simon.schwantzer(at)im-c.de
 */
public class BasicBPMNProcessService implements ProcessService {
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(BasicBPMNProcessService.class);
	
	private final Map<String, ProcessDefinition> processDefinitions;
	private final Map<String, ProcessInstance> processInstances;
	private final Map<String, BasicBPMNProcessInstanceUpdateListener> instanceUpdateListeners;
	private final ServiceCallHelper serviceCallHelper;
	private final Vertx vertx;
	private final Map<String, List<ProcessTriggerEventHandler>> triggers; // <Process-ID, Triggers>
	
	/**
	 * Creates the process service.
	 * @param vertx Vertx instance. Handed over to update listeners.
	 * @param logger Logger instance. Handed over to update listeners.
	 * @param serviceCallHelper Helper for performing serivce calls. Handed over to update listeners.
	 */
	public BasicBPMNProcessService(Vertx vertx, ServiceCallHelper serviceCallHelper) {
		this.processDefinitions = new HashMap<>();
		this.processInstances = new HashMap<>();
		this.instanceUpdateListeners = new HashMap<>();
		this.vertx = vertx;
		this.serviceCallHelper = serviceCallHelper;
		triggers = new HashMap<>();
	}

	@Override
	public List<String> getAllProcessIds() {
		return new ArrayList<String>(processDefinitions.keySet());
	}

	@Override
	public List<? extends ProcessDefinition> getAllProcesses() {
		return new ArrayList<ProcessDefinition>(processDefinitions.values());
	}

	@Override
	public ProcessDefinition getProcessById(String processId) throws IllegalArgumentException {
		if (!processDefinitions.containsKey(processId)) {
			throw new IllegalArgumentException("No process with ID '" + processId + "' available.");
		}
		return processDefinitions.get(processId);
	}

	@Override
	public void registerProcess(final ProcessDefinition processDefinition) throws IllegalArgumentException {
		String processId = processDefinition.getId();
		if (processDefinitions.containsKey(processId)) {
			throw new IllegalArgumentException("Failed to register process: A process with ID '" + processId + "' already exists.");
		}
		this.processDefinitions.put(processId, processDefinition);
		List<? extends TriggerAnnotation> triggers = processDefinition.getAnnotations().getTriggerAnnotations(); 
		List<ProcessTriggerEventHandler> triggerHandlers = new ArrayList<>();
		for (final TriggerAnnotation trigger : triggers) {
			ProcessTriggerEventHandler handler = new ProcessTriggerEventHandler(trigger, processDefinition, this);
			vertx.eventBus().registerHandler("appsist:event:" + trigger.getEventId(), handler);
			triggerHandlers.add(handler);
		}
		this.triggers.put(processId, triggerHandlers);
	}

	@Override
	public void unregisterProcess(String processId) throws IllegalArgumentException {
		if (!processDefinitions.containsKey(processId)) {
			throw new IllegalArgumentException("Failed to unregister process: No process with ID '" + processId + "' available.");
		}
		for (ProcessTriggerEventHandler triggerHandler : triggers.get(processId)) {
			vertx.eventBus().unregisterHandler("appsist:event:" + triggerHandler.getTriggerAnnotation().getEventId(), triggerHandler); 
		}
		triggers.remove(processId);
		this.processDefinitions.remove(processId);
	}

	@Override
	public List<String> getRunningProcessInstanceIds() {
		List<String> runningProcessInstanceIds = new ArrayList<>();
		for (String instanceId : processInstances.keySet()) {
			ProcessInstance instance = processInstances.get(instanceId);
			if (instance.isRunning()) {
				runningProcessInstanceIds.add(instanceId);
			}
		}
		return runningProcessInstanceIds;
	}

	@Override
	public List<? extends ProcessInstance> getRunningProcessInstances() {
		List<ProcessInstance> runningProcessInstances = new ArrayList<>();
		for (String instanceId : processInstances.keySet()) {
			ProcessInstance instance = processInstances.get(instanceId);
			if (instance.isRunning()) {
				runningProcessInstances.add(instance);
			}
		}
		return runningProcessInstances;
	}

	@Override
	public List<? extends ProcessInstance> getRunningInstancesForProcess(String processId) throws IllegalArgumentException {
		List<ProcessInstance> runningProcessInstances = new ArrayList<>();
		for (String instanceId : processInstances.keySet()) {
			ProcessInstance instance = processInstances.get(instanceId);
			if (instance.getProcessDefinition().getId().equals(processId)) {
				runningProcessInstances.add(instance);
			}
		}
		return runningProcessInstances;
	}

	@Override
	public ProcessInstance getProcessInstanceById(String processInstanceId) throws IllegalArgumentException {
		if (!processInstances.containsKey(processInstanceId)) {
			throw new IllegalArgumentException("No process instance with ID '" + processInstanceId + "' available.");
		}
		return processInstances.get(processInstanceId);
	}

	@Override
	public ProcessInstance instantiateProcess(String processId, String userId, String sessionId, Map<String, Object> initialContext, ProcessInstance parent) throws IllegalArgumentException {
		ProcessDefinition processDefinition = processDefinitions.get(processId);
		if (processDefinition == null) {
			throw new IllegalArgumentException("Failed to instantiate process: No process with id '" + processId + "' available.");
		}
		String instanceId = UUID.randomUUID().toString();
		BPMNProcessInstance processInstance = new BPMNProcessInstance(instanceId, (BPMNProcessDefinition) processDefinition, (BPMNProcessInstance) parent, this);
		processInstances.put(instanceId, processInstance);
		JsonObject executionInfo = new JsonObject();
		if (userId != null) {
			processInstance.setUserId(userId);
			executionInfo.putString("userId", userId);
		}
		if (sessionId != null) {
			processInstance.setSessionId(sessionId);
			executionInfo.putString("sessionId", sessionId);
		}
		Map<String, Object> processContext = processInstance.getProcessInstanceContext();
		processContext.put("executionInfo", executionInfo.toMap());
		for (String key : initialContext.keySet()) {
			if (!"processInstanceId".equals(key)) processContext.put(key, initialContext.get(key));
		}
		
		BasicBPMNProcessInstanceUpdateListener updateListener = new BasicBPMNProcessInstanceUpdateListener(processInstance, vertx, serviceCallHelper);
		instanceUpdateListeners.put(processInstance.getId(), updateListener);
		processInstance.addUpdateListener(updateListener);
		if (parent == null) processInstance.run();
		
		purgeOldInstances();

		return processInstance;
	}
	
	/**
	 * Purges any completed process instance older than 24 hours.
	 */
	private void purgeOldInstances() {
		List<String> toRemove = new ArrayList<String>();
		Calendar cal= Calendar.getInstance();
		cal.add(Calendar.HOUR_OF_DAY, -24);
		Date referenceDate = cal.getTime();
		for (ProcessInstance processInstance : processInstances.values()) {
			BasicBPMNProcessInstanceUpdateListener listener = instanceUpdateListeners.get(processInstance.getId());
			Date completionTime = listener.getCompletionTime();
			if (completionTime != null  && completionTime.before(referenceDate)) {
				toRemove.add(processInstance.getId());
			}
		}
		for (String processInstanceId : toRemove) {
			processInstances.get(processInstanceId).removeUpdateListener(instanceUpdateListeners.get(processInstanceId));
			instanceUpdateListeners.remove(processInstanceId);
			processInstances.remove(processInstanceId);
		}
	}
}
