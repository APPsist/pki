package de.appsist.service.pki.util;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.vertx.java.core.json.DecodeException;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import de.appsist.commons.event.AppsistEvent;
import de.appsist.commons.process.EventAnnotation;
import de.appsist.commons.process.EventAnnotation.Type;
import de.appsist.commons.process.ProcessDefinition;
import de.appsist.commons.process.ProcessElement;
import de.appsist.commons.process.ProcessElementInstance;
import de.appsist.commons.process.ProcessInstance;
import de.appsist.commons.process.ServiceCallAnnotation;
import de.appsist.commons.process.ServiceCallAnnotation.HttpMethod;
import de.appsist.commons.process.TriggerAnnotation;
import de.appsist.commons.process.bpmn.BPMNEventAnnotation;
import de.appsist.commons.process.bpmn.BPMNProcessDefinition;
import de.appsist.commons.process.bpmn.BPMNServiceCallAnnotation;
import de.appsist.commons.process.bpmn.BPMNTriggerAnnotation;
import de.appsist.commons.process.bpmn.exception.InvalidBPMNFragmentException;
import de.appsist.commons.process.bpmn.exception.InvalidBPMNProcessStructure;
import de.appsist.commons.util.ProcessConverterUtil;
import de.appsist.service.pki.ProcessService;

/**
 * Utility class for creating json objects for data model instances.
 * @author simon.schwantzer(at)im-c.de
 */
public class JsonObjectUtil {
	private static final Logger logger = LoggerFactory.getLogger(JsonObjectUtil.class); 
	
	/**
	 * Generate a list of ids of the available processes. 
	 * @param processService Process service to retrieve process information from.
	 * @return JSON object with result array.
	 */
	public static JsonObject encodeProcessList(ProcessService processService) {
		JsonObject obj = new JsonObject();
		JsonArray ids = new JsonArray();
		for (String id : processService.getAllProcessIds()) {
			ids.addString(id);
		}
		obj.putArray("processDefinitions", ids);
		return obj;
	}
	
	/**
	 * Generates a list of ids for instances of a specific process.
	 * @param processId ID of a process definition.
	 * @param onlyRunning Show only running instances.
	 * @param processService Process service to retrieve information from.
	 * @return JsonObject with field "processInstances" containing an string array with the instance ids. 
	 * @throws IllegalArgumentException No process definition with the given id exists.
	 */
	public static JsonObject encodeProcessInstanceIds(String processId, boolean onlyRunning, ProcessService processService) throws IllegalArgumentException {
		JsonObject obj = new JsonObject();
		obj.putString("processId", processId);
		JsonArray instances = new JsonArray();
		for (ProcessInstance processInstance : processService.getRunningInstancesForProcess(processId)) {
			if (onlyRunning) {
				if (processInstance.isRunning()) {
					instances.addString(processInstance.getId());
				}
			} else {
				instances.addString(processInstance.getId());
			}
		}
		obj.putArray("processInstances", instances);
		return obj;
	}
	

	/**
	 * Encodes a list of the identifiers of all process instances which match the filter critera.
	 * @param processService Process service to retrieve information from.
	 * @param processId If not <code>null</code>, only instances of the given process are returned.
	 * @param userId Id not <code>null</code>, only instances created by the given user are returned.
	 * @return JSON object with a field "instances", 
	 * @throws IllegalArgumentException Unknown process.
	 */
	public static JsonObject encodeInstanceList(ProcessService processService, String processId, String userId) throws IllegalArgumentException {
		JsonObject obj = new JsonObject();
		JsonArray processInstanceIds = new JsonArray();
		if (processId != null) {
			obj.putString("processId", processId);
			for (ProcessInstance processInstance : processService.getRunningInstancesForProcess(processId)) {
				if (userId != null && !userId.equals(processInstance.getUserId())) continue;
				processInstanceIds.addString(processInstance.getId());
			}
		} else {
			for (ProcessInstance processInstance : processService.getRunningProcessInstances()) {
				if (userId != null && !userId.equals(processInstance.getUserId())) continue;
				processInstanceIds.addString(processInstance.getId());
			}
		}
		obj.putArray("processInstances", processInstanceIds);
		return obj;
	}
	
	/**
	 * Encodes a process definition as JSON object.
	 * @param processDefinition Process definition to encode.
	 * @return JSON representation of the process definition.
	 */
	public static JsonObject encodeProcessDefinition(ProcessDefinition processDefinition) {
		Map<String, Object> localData = processDefinition.getAnnotations().getLocalDataAnnotations(); 
		
		JsonObject obj = new JsonObject();
		obj.putString("id", processDefinition.getId());
		if (processDefinition instanceof BPMNProcessDefinition) {
			obj.putString("type", "bpmn");
		} else {
			obj.putString("type", "other");
		}
		String label = processDefinition.getLabel();
		if (label != null) obj.putString("label", label);
		String description = (String) localData.get("processDescription");
		if (description != null) obj.putString("description", description);
		obj.putString("startElement", processDefinition.getProcessStartElement().getId());
		// obj.putString("raw", processDefinition.toString());
		if (localData.size() > 0) {
			obj.putObject("localData", new JsonObject(localData));
		}
		
		List<? extends TriggerAnnotation> triggerAnnotations = processDefinition.getAnnotations().getTriggerAnnotations();
		if (triggerAnnotations.size() > 0) {
			JsonArray triggers = new JsonArray();
			for (TriggerAnnotation triggerAnnotation : triggerAnnotations) {
				JsonObject trigger = encodeTrigger(triggerAnnotation); 
				triggers.add(trigger);
			}
			obj.putArray("triggers", triggers);
		}
		
		List<? extends EventAnnotation> eventAnnotations = processDefinition.getAnnotations().getEventAnnotations();
		if (eventAnnotations.size() > 0) {
			JsonArray events = new JsonArray();
			for (EventAnnotation eventAnnotation : eventAnnotations) {
				JsonObject event = encodeEvent(eventAnnotation);
				events.addObject(event);
			}
			obj.putArray("events", events);
		}
		
		List<? extends ServiceCallAnnotation> serviceCallAnnotations = processDefinition.getAnnotations().getServiceCallAnnotations();
		if (serviceCallAnnotations.size() > 0) {
			JsonArray serviceCalls = new JsonArray();
			for (ServiceCallAnnotation serviceCallAnnotation : serviceCallAnnotations) {
				JsonObject serviceCall = encodeServiceCall(serviceCallAnnotation);
				serviceCalls.addObject(serviceCall);
			}
			obj.putArray("serviceCalls", serviceCalls);
		}
		
		return obj;
	}
	
	/**
	 * Generates a process definition from a JSON object.
	 * @param obj JSON object containing a <code>type</code> and a the <code>raw</code> process definition.
	 * @return Process definition object.
	 * @throws IllegalArgumentException The notation is not supported or the raw data is invalid.
	 */
	public static ProcessDefinition decodeProcessDefinition(JsonObject obj) {
		ProcessDefinition processDefinition = null;
		
		String type = obj.getString("type");
		String raw = obj.getString("raw");
		
		if (raw == null) {
			throw new IllegalArgumentException("Missing 'raw' field containing process definition.");
		}
		if (type != null) switch (type) {
		case "bpmn":
			try {
				processDefinition = ProcessConverterUtil.bpmnProcess2ProcessDefinition(raw);
			} catch (InvalidBPMNFragmentException | InvalidBPMNProcessStructure e) {
				throw new IllegalArgumentException(e.getMessage(), e);
			}
		} else {
			throw new IllegalArgumentException("Unsupported process notation."); 
		}
		return processDefinition;
	}
	
	/**
	 * Encodes a service call annotation as JSON object.
	 * @param serviceCall Service call annotation to encode.
	 * @return JSON object representing the annotation.
	 */
	public static JsonObject encodeServiceCall(ServiceCallAnnotation serviceCall) {
		JsonObject obj = new JsonObject();
		switch (serviceCall.getType()) {
		case START:
			obj.putString("type", "onStart");
			break;
		case END:
			obj.putString("type", "onEnd");
			break;
		case TRIGGER:
			obj.putString("type", "onTrigger");
			break;
		}
		obj.putString("service", serviceCall.getService());
		obj.putString("method", serviceCall.getMethod());
		Map<String, String> inputMapping = serviceCall.getInputMapping();
		JsonObject input = new JsonObject();
		for (String key : inputMapping.keySet()) {
			input.putString(key, inputMapping.get(key));
		}
		obj.putObject("inputMapping", input);
		Map<String, String> outputMapping = serviceCall.getOutputMapping();
		JsonObject output = new JsonObject();
		for (String key : outputMapping.keySet()) {
			output.putString(key, outputMapping.get(key));
		}
		obj.putObject("outputMapping", output);
		String outputReference = serviceCall.getOutputReference();
		if (outputReference != null) {
			obj.putString("outputReference", outputReference);
		}
		TriggerAnnotation triggerAnnotation = serviceCall.getTrigger();
		if (triggerAnnotation != null) {
			JsonObject trigger = encodeTrigger(triggerAnnotation);
			obj.putObject("trigger", trigger);
		}
		return obj;
	}
	
	/**
	 * Decodes a JSON object as service call annotation.
	 * @param obj JSON object encoding a service call annotation.
	 * @return BPMN service call annotation.
	 * @throws The given JSON object does not represent a service call annotation.
	 */
	public static BPMNServiceCallAnnotation decodeBPMNServiceCall(JsonObject obj) throws DecodeException {
		String typeString = obj.getString("type");
		String service = obj.getString("service");
		String method = obj.getString("method");
		if (typeString == null || service == null || method == null) {
			throw new DecodeException("Type, service, and/or method is missing.");
		}
		JsonObject trigger = obj.getObject("trigger");
		ServiceCallAnnotation.Type type;
		switch (typeString) {
		case "onEnd":
			type = ServiceCallAnnotation.Type.END;
			break;
		case "onTrigger":
			type = ServiceCallAnnotation.Type.TRIGGER;
			if (trigger == null) throw new DecodeException("Type is set onTrigger, but a trigger object is missing.");
			break;
		case "onStart":
		default:
			type = ServiceCallAnnotation.Type.START;
		}
		String httpMethodString = obj.getString("httpMethod", "UKNOWN");
		HttpMethod httpMethod;
		switch (httpMethodString) {
		case "GET":
			httpMethod = HttpMethod.GET;
			break;
		case "POST":
			httpMethod = HttpMethod.POST;
			break;
		case "PUT":
			httpMethod = HttpMethod.PUT;
			break;
		default:
			logger.warn("No or wrong http method for service call given. Default to POST.");
			httpMethod = HttpMethod.POST;
		}
		BPMNServiceCallAnnotation serviceCallAnnotation = new BPMNServiceCallAnnotation(service, method, type, httpMethod);
		if (trigger != null) {
			serviceCallAnnotation.setTrigger(decodeBPMNTrigger(trigger));
		}
		JsonObject inputMap = obj.getObject("inputMapping");
		if (inputMap != null) {
			Map<String, String> inputMapping = new TreeMap<String, String>();
			for (String key : inputMap.getFieldNames()) {
				String reference = inputMap.getString(key);
				if (reference != null) inputMapping.put(key, reference);
			}
			serviceCallAnnotation.setInputMapping(inputMapping);
		}
		JsonObject outputMap = obj.getObject("outputMapping");
		if (outputMap != null) {
			Map<String, String> outputMapping = new TreeMap<String, String>();
			for (String key : outputMap.getFieldNames()) {
				String reference = outputMap.getString(key);
				if (reference != null) outputMapping.put(key, reference);
			}
			serviceCallAnnotation.setOutputMapping(outputMapping);
		}
		serviceCallAnnotation.setOutputReference(obj.getString("outputReference"));
		
		return serviceCallAnnotation;
	}
	
	/**
	 * Encodes a trigger annotation as JSON object.
	 * @param trigger Trigger annotation to encode.
	 * @return JSON object representing the annotation.
	 */
	public static JsonObject encodeTrigger(TriggerAnnotation trigger) {
		JsonObject obj = new JsonObject();
		obj.putString("eventId", trigger.getEventId());
		obj.putObject("references", new JsonObject(trigger.getReferences()));
		return obj;
	}
	
	/**
	 * Decodes a trigger annotation from a JSON object.
	 * @param trigger Json object encoding the trigger.
	 * @return Trigger annotation object.
	 * @throws DecodeException The given json object does not represent a trigger annotation. 
	 */
	public static BPMNTriggerAnnotation decodeBPMNTrigger(JsonObject trigger) throws DecodeException {
		String eventId = trigger.getString("eventId");
		JsonObject references = trigger.getObject("references");
		if (eventId == null) {
			throw new DecodeException("A trigger requires an eventId.");
		}
		if (references == null) {
			references = new JsonObject();
		}
		BPMNTriggerAnnotation triggerAnnotation = new BPMNTriggerAnnotation(eventId, references.toMap());
		return triggerAnnotation;
	}
	
	/**
	 * Encodes a event annotation as JSON object.
	 * @param event Event annotation to encode.
	 * @return JSON object representing the annotation.
	 */
	public static JsonObject encodeEvent(EventAnnotation event) {
		JsonObject obj = new JsonObject();
		obj.putString("eventId", event.getEventId());
		switch (event.getType()) {
		case START:
			obj.putString("type", "onStart");
			break;
		case END:
			obj.putString("type", "onEnd");
			break;
		}
		obj.putObject("properties", new JsonObject(event.getProperties()));
		return obj;
	}
	
	/**
	 * Decodes a BPMN event annotation from a JSON object.
	 * @param obj JSON object encoding a event annotation.
	 * @return BPMN event annotation.
	 * @throws DecodeException The JSON object does not encode a event annotation.
	 */
	public static BPMNEventAnnotation decodeBPMNEvent(JsonObject obj) throws DecodeException {
		String eventId = obj.getString("eventId");
		String typeString = obj.getString("type");
		JsonObject properties = obj.getObject("properties");
		
		if (eventId == null || typeString == null) {
			throw new DecodeException("EventId and/or type is missing.");
		}
		BPMNEventAnnotation.Type type;
		switch (typeString) {
		case "onEnd":
			type = Type.END;
			break;
		case "onStart":
		default:
			type = Type.START;
		}
		
		BPMNEventAnnotation eventAnnotation = new BPMNEventAnnotation(eventId, type, properties.toMap());
		return eventAnnotation;
	}
	
	/**
	 * Encodes a list of process element IDs for the given process definition.
	 * @param processDefintion Process definition to retrieve elements from.
	 * @return JSON object with a field <code>processElements</code> containing an array of IDs.
	 */
	public static JsonObject encodeProcessElementIds(ProcessDefinition processDefintion) {
		JsonObject obj = new JsonObject();
		obj.putString("processId", processDefintion.getId());
		JsonArray processElementIds = new JsonArray();
		for (ProcessElement processElement : processDefintion.getAllProcessElements()) {
			processElementIds.addString(processElement.getId());
		}
		obj.putArray("processElements", processElementIds);
		return obj;
	}
	
	/**
	 * Encodes a process element as JSON object.
	 * @param processElement Process element to encode.
	 * @return JSON object representing the process element.
	 */
	public static JsonObject encodeProcessElement(ProcessElement processElement) {
		JsonObject obj = new JsonObject();
		obj.putString("id", processElement.getId());
		String label = processElement.getLabel();
		if (label != null) obj.putString("label", label);
		obj.putString("type", processElement.getType());
		
		Map<String, Object> localData = processElement.getAnnotations().getLocalDataAnnotations();
		if (localData.size() > 0) {
			obj.putObject("localData", new JsonObject(localData));
		}
		
		List<? extends TriggerAnnotation> triggerAnnotations = processElement.getAnnotations().getTriggerAnnotations(); 
		if (triggerAnnotations.size() > 0) {
			JsonArray triggers = new JsonArray();
			for (TriggerAnnotation triggerAnnotation : triggerAnnotations) {
				JsonObject trigger = encodeTrigger(triggerAnnotation); 
				triggers.add(trigger);
			}
			obj.putArray("triggers", triggers);
		}
		
		List<? extends EventAnnotation> eventAnnotations = processElement.getAnnotations().getEventAnnotations();
		if (eventAnnotations.size() > 0) {
			JsonArray events = new JsonArray();
			for (EventAnnotation eventAnnotation : eventAnnotations) {
				JsonObject event = encodeEvent(eventAnnotation);
				events.addObject(event);
			}
			obj.putArray("events", events);
		}
		
		List<? extends ServiceCallAnnotation> serviceCallAnnotations = processElement.getAnnotations().getServiceCallAnnotations();
		if (serviceCallAnnotations.size() > 0) {
			JsonArray serviceCalls = new JsonArray();
			for (ServiceCallAnnotation serviceCallAnnotation : serviceCallAnnotations) {
				JsonObject serviceCall = encodeServiceCall(serviceCallAnnotation);
				serviceCalls.addObject(serviceCall);
			}
			obj.putArray("serviceCalls", serviceCalls);
		}
		
		return obj;
	}

	/**
	 * Encodes a process instance as JSON object.
	 * @param processInstance Process instance to encode.
	 * @return JSON object encoding the process instance.
	 */
	public static JsonObject encodeProcessInstance(ProcessInstance processInstance) {
		JsonObject obj = new JsonObject();
		obj.putString("id", processInstance.getId());
		obj.putString("processId", processInstance.getProcessDefinition().getId());
		obj.putBoolean("isRunning", processInstance.isRunning());
		// TODO Implement user id.
		// obj.putString("userId", processInstance.getUserId());
		ProcessInstance parentInstance = processInstance.getParent();
		if (parentInstance != null) {
			obj.putString("parent", parentInstance.getId());
		}
		JsonObject context = new JsonObject(processInstance.getProcessInstanceContext());
		obj.putObject("context", context);
		return obj;
	}

	/**
	 * Encodes a process element instance as JSON object.
	 * Compared to a process element, the instance contains additional runtime information. 
	 * @param processElementInstance Process element instance to encode.
	 * @return JSON object representing the process element instance.
	 */
	public static JsonObject encodeProcessElementInstance(ProcessElementInstance processElementInstance) {
		ProcessInstance processInstance = processElementInstance.getProcessInstance();
		ProcessElement processElement = processElementInstance.getProcessElement();
		ProcessDefinition processDefinition = processInstance.getProcessDefinition();
		
		JsonObject obj = encodeProcessElement(processElement);
		ProcessElementInstance predecessor = processElementInstance.getPredecessor();
		if (predecessor != null) {
			obj.putString("previousElement", predecessor.getProcessElement().getId());
		}
		
		List<? extends ProcessElement> successors = processDefinition.getSuccessors(processElement);
		if (successors.size() > 0) {
			JsonArray nextElements = new JsonArray();
			for (ProcessElement successor : successors) {
				nextElements.addString(successor.getId());
			}
			obj.putArray("nextElements", nextElements);
		}
		
		JsonObject executionInfo = new JsonObject();
		Date startTime = processElementInstance.getStart();
		if (startTime != null) {
			executionInfo.putString("start", EventUtil.getDateAsISOString(startTime));
		}
		Date endTime = processElementInstance.getEnd();
		if (endTime != null) {
			executionInfo.putString("end", EventUtil.getDateAsISOString(endTime));
		}
		AppsistEvent trigger = processElementInstance.getTrigger();
		if (trigger != null) {
			executionInfo.putObject("trigger", new JsonObject(trigger.asMap()));
		}
		obj.putObject("executionInfo", executionInfo);
		
		return obj;
	}

	/**
	 * Encodes the execution history of a process instance as JSON object.
	 * @param processInstance Process instance to retrieve history from.
	 * @return Json object with a field "history" containing a list of process element instances in reverse chronological order.
	 */
	public static JsonObject encodeProcessInstanceHistory(ProcessInstance processInstance) {
		JsonObject obj = new JsonObject();
		obj.putString("processId", processInstance.getProcessDefinition().getId());
		obj.putString("processInstance", processInstance.getId());
		JsonArray history = new JsonArray();
		for (ProcessElementInstance processElementInstance : processInstance.getHistory()) {
			history.addObject(encodeProcessElementInstance(processElementInstance));
		}
		obj.putArray("history", history);
		return obj;
	}
}
