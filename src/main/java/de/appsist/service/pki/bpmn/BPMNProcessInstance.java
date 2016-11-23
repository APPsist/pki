package de.appsist.service.pki.bpmn;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.appsist.commons.process.ProcessDefinition;
import de.appsist.commons.process.ProcessElementInstance;
import de.appsist.commons.process.ProcessInstance;
import de.appsist.commons.process.ProcessInstanceUpdateListener;
import de.appsist.commons.process.bpmn.BPMNProcessDefinition;
import de.appsist.commons.process.bpmn.BPMNProcessElement;
import de.appsist.commons.process.bpmn.elements.BPMNCallActivity;
import de.appsist.commons.process.bpmn.elements.BPMNEndEvent;
import de.appsist.commons.process.bpmn.elements.BPMNStartEvent;
import de.appsist.commons.process.exception.AmbiguousFlowException;
import de.appsist.service.pki.ProcessService;

/**
 * BPMN implementation for process instances.
 * @author simon.schwantzer(at)im-c.de
 */
public class BPMNProcessInstance implements ProcessInstance {
	private final String id;
	private final BPMNProcessInstance parentInstance;
	private final BPMNProcessDefinition processDefinition;
	private final Map<String, Object> context;
	private final List<BPMNProcessElementInstance> history;
	private final Set<ProcessInstanceUpdateListener> updateListeners;
	private final ProcessService processService;
	private BPMNProcessElementInstance currentState;
	private String userId;
	private String sessionId;
	
	/**
	 * Creates a process instance.
	 * A instance is not directly executed (started), see {@link BPMNProcessInstance#run()} for details. 
	 * @param id Identifier for the instance. 
	 * @param processDefinition Process definition to instantiate.
	 * @param parentInstance Process instance if this instance is a sub process (call activity), otherwise <code>null</code>.
	 * @param processService Service to handle subprocess creation. 
	 */
	public BPMNProcessInstance(String id, BPMNProcessDefinition processDefinition, BPMNProcessInstance parentInstance, ProcessService processService) {
		this.id = id;
		this.processDefinition = processDefinition;
		this.parentInstance = parentInstance;
		this.processService = processService;
		this.context = new LinkedHashMap<String, Object>();
		context.put("processInstanceId", id);
		this.history = new ArrayList<>();
		this.updateListeners = new HashSet<>();
		this.userId = null;
		this.sessionId = null;
	}
	
	/**
	 * Starts the process instance.
	 */
	public void run() {
		BPMNProcessElement startEvent = processDefinition.getProcessStartElement();
		changeState(new BPMNProcessElementInstance(this, startEvent, null));
	}
	
	@Override
	public void addUpdateListener(ProcessInstanceUpdateListener listener) {
		this.updateListeners.add(listener);
	}
	
	@Override
	public void removeUpdateListener(ProcessInstanceUpdateListener listener) {
		this.updateListeners.remove(listener);
	}
	
	/**
	 * Processes a change of state of the process instance.
	 * @param newState BPMN element instance which should become active. If <code>null</code>, the process instance will be terminated.
	 */
	private void changeState(BPMNProcessElementInstance newState) {
		BPMNProcessElementInstance oldState = currentState;
		if (oldState != null) {
			oldState.end(null);
			history.add(0, oldState);
		}
		if (newState != null) {
			currentState = newState;
			context.put("elementId", newState.getProcessElement().getId());
			currentState.start();
		} else {
			context.remove("elementId");
			currentState = null;
		}
		for (ProcessInstanceUpdateListener listener : updateListeners) {
			if (newState != null) {
				switch (newState.getProcessElement().getType()) {
				case BPMNStartEvent.ELEMENT_TYPE:
					listener.start();
					break;
				case BPMNEndEvent.ELEMENT_TYPE:
					BPMNEndEvent endEvent = (BPMNEndEvent) newState.getProcessElement();
					if (endEvent.isError()) {
						listener.error(endEvent, 500, endEvent.getLabel());
					} else if (endEvent.isTerminate()) {
						listener.terminate();
					} else {
						listener.end();
					}
					break;
				case BPMNCallActivity.ELEMENT_TYPE:
					BPMNCallActivity callActivity = (BPMNCallActivity) newState.getProcessElement();
					listener.activityCalled(callActivity);
					break;
				}
			}
			listener.stepPerformed(oldState, newState);
		}
	}

	@Override
	public String getId() {
		return id;
	}
	
	/**
	 * Sets the ID of the user running the process instance.
	 * @param userId User identifier. 
	 */
	public void setUserId(String userId) {
		this.userId = userId;
	}
	
	@Override
	public String getUserId() {
		return userId;
	}
	
	/**
	 * Sets the ID of the session in whose context the process was instantiated.
	 * @param sessionId Session identifier.
	 */
	public void setSessionId(String sessionId) {
		this.sessionId = sessionId;
	}
	
	@Override
	public String getSessionId() {
		return sessionId;
	};

	@Override
	public ProcessDefinition getProcessDefinition() {
		return processDefinition;
	}
	
	@Override
	public BPMNProcessInstance getParent() {
		return parentInstance;
	}
	
	@Override
	public BPMNProcessInstance getTopMostParent() {
		BPMNProcessInstance instance = this;
		while (instance.getParent() != null) {
			instance = instance.getParent();
		}
		return instance;
	}

	@Override
	public Map<String, Object> getProcessInstanceContext() {
		return context;
	}

	@Override
	public boolean isRunning() {
		return currentState != null;
	}

	@Override
	public ProcessElementInstance getCurrentState() {
		return currentState;
	}

	@Override
	public List<? extends ProcessElementInstance> getHistory() {
		return history;
	}

	@Override
	public boolean hasNextStep() {
		if (currentState == null) {
			return false;
		}
		return processDefinition.getSuccessors(currentState.getProcessElement()).size() > 0;
	}

	@Override
	public BPMNProcessElementInstance stepForward() throws AmbiguousFlowException {
		if (currentState == null) {
			return null;
		}
		List<BPMNProcessElement> successors = processDefinition.getSuccessors(currentState.getProcessElement());
		BPMNProcessElementInstance nextStep;
		if (successors.size() > 1) {
			throw new AmbiguousFlowException();
		} else if (successors.size() == 1) {
			nextStep = new BPMNProcessElementInstance(this, successors.get(0), currentState);
		} else {
			nextStep = null;
		}
		changeState(nextStep);
		return nextStep;
	}
	
	@Override
	public BPMNProcessElementInstance stepForward(String processElementId) throws IllegalArgumentException {
		if (currentState == null) {
			return null;
		}
		List<BPMNProcessElement> successors = processDefinition.getSuccessors(currentState.getProcessElement());
		BPMNProcessElement selectedElement = null;
		for (BPMNProcessElement successor : successors) {
			if (successor.getId().equals(processElementId)) {
				selectedElement = successor;
				break;
			}
		}
		if (selectedElement == null) {
			throw new IllegalArgumentException("No successor with the given id available.");
		}
		BPMNProcessElementInstance nextStep = new BPMNProcessElementInstance(this, selectedElement, currentState); 
		changeState(nextStep);
		return nextStep;
	}
	
	@Override
	public BPMNProcessInstance enterSubprocess() throws IllegalStateException, IllegalArgumentException {
		if (currentState == null || !currentState.getProcessElement().getType().equals(BPMNCallActivity.ELEMENT_TYPE)) {
			// No subprocess to enter.
			throw new IllegalStateException("Invalid operation for the current process state.");
		}
		
		BPMNCallActivity activity = (BPMNCallActivity) currentState.getProcessElement();
		String activityProcessId = activity.getCalledProcess();
		BPMNProcessInstance callee = (BPMNProcessInstance) processService.instantiateProcess(activityProcessId, userId, sessionId, context, this);
		callee.run();
		return callee;
	}

	@Override
	public BPMNProcessElementInstance stepBackward() {
		if (history.size() == 0) {
			return null;
		}
		BPMNProcessElementInstance lastStep = currentState.getPredecessor();
		BPMNProcessElementInstance nextStep = new BPMNProcessElementInstance(this, lastStep.getProcessElement(), lastStep.getPredecessor()); 
		changeState(nextStep);
		return nextStep;
	}

	@Override
	public void cancel() {
		changeState(null);
		for (ProcessInstanceUpdateListener listener : updateListeners) {
			listener.cancel();
		}
	}
	
	@Override
	public void terminate() {
		changeState(null);
		for (ProcessInstanceUpdateListener listener : updateListeners) {
			listener.terminate();
		}
	}
	
}